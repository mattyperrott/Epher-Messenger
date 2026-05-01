# Epher V2 Mixnet Architecture

## Status

This document is the proposed privacy architecture for Epher v2.

It is a design target, not an implementation claim.

It replaces the earlier assumption that Epher should be pure direct peer-to-peer chat.

It also replaces the idea that the current single-relay prototype can be called production-anonymity-safe.

## Why V2 Exists

The current app can deliver encrypted group messages over a privacy-oriented relay, but that is still:

- a single-relay design
- not a real multi-hop mixnet
- not audited
- not strong enough to justify anonymity claims

If Epher wants real network-layer privacy, then the architecture must move from direct peer mesh or single relay toward:

- end-to-end encrypted group state
- multi-hop mix routing
- padded retrieval
- cover traffic
- explicit trust minimization

## Product Goal

Epher v2 should be a room-based Android messenger for small private groups where:

- message contents remain end-to-end encrypted
- relays cannot read room messages
- network observers have a much harder time linking sender, receiver, timing, and room activity
- room retention is still ephemeral by default
- users can operate with room-scoped pseudonymous identities

## Non-Goals

Epher v2 should not promise:

- perfect anonymity against every adversary
- low-latency media calls
- massive public groups
- zero metadata leakage under every failure mode
- safe deployment without external audit

## Primary Design Decisions

### 1. Group Crypto Uses MLS

Epher v2 should move to Messaging Layer Security for group key management and group state.

Why:

- MLS is the current IETF standard for secure group messaging.
- MLS is designed for asynchronous group membership changes.
- MLS gives a standard model for add, remove, commit, welcome, key packages, and state recovery decisions.
- MLS is a better fit for rooms than custom pairwise fanout as the long-term architecture.

Recommended implementation path:

- evaluate `mls-rs` first as the likely production candidate for a Rust core
- keep OpenMLS as an interoperability and design reference
- expose the chosen Rust MLS core to Android through a narrow JNI or UniFFI bridge

Important note:

- `mls-rs` advertises RFC 9420 conformance and a strong test suite, but its own README says it has not yet received a full third-party security audit
- therefore Epher still needs its own review and should not inherit trust automatically from the library choice

### 2. Transport Uses A Multi-Hop Mixnet

Normal room messaging should not require direct peer-to-peer connectivity.

Instead, messages should move through:

- one entry gateway
- three mix hops
- one provider or mailbox hop

This gives a five-server route similar in shape to modern mixnet systems.

Each message should be onion-routed as a fixed-size Sphinx-style packet so that:

- each hop only learns the adjacent hop
- packets have a uniform outer shape
- a passive observer cannot trivially match ingress and egress by size

### 3. Clients Retrieve Through Padded Mailboxes

Clients should not wait for direct push from another user.

Instead, each client should:

- maintain a room-scoped mailbox alias
- poll through the mixnet at fixed intervals
- receive a padded response whether or not real mail exists

This is the right place to introduce:

- padded retrieval
- cover responses
- loop decoys
- background noise

### 4. Invite Links Are Capability-Bearing, Not Plain Room Secrets

Invite links should continue to be signed and short-lived, but v2 should treat them as onboarding capabilities rather than long-term room credentials.

The invite should authorize:

- fetching the latest `GroupInfo`
- acquiring the right MLS bootstrap material
- obtaining the initial mailbox/provider routing hints

The invite should not be sent to relays as part of normal transport.

### 5. Identities Stay Room-Scoped

A user should not carry one long-lived app identity across all rooms by default.

Instead:

- each room gets its own pseudonymous room identity
- MLS credentials are room-scoped
- transport mailbox aliases are room-scoped
- leaving or expiring a room wipes the room identity and room state locally

## Threat Model

Epher v2 should be designed against the following realistic adversaries:

- passive network observers
- honest-but-curious relays
- malicious relays controlling a minority of the route
- malicious room members
- compromised delivery or directory infrastructure
- replay attempts
- message correlation attempts based on timing and size

Epher v2 should explicitly not claim protection against:

- a fully compromised endpoint device
- a global active adversary controlling most of the mix path and the user device
- deanonymization caused by the user revealing identity in message content

## High-Level Architecture

### Client

The Android client should contain:

- UI and room lifecycle
- local encrypted storage
- room-scoped pseudonymous identity manager
- MLS group state manager
- transport connector for mixnet send and retrieval
- retention and wipe enforcement

### Authentication Service

The Authentication Service should issue and validate room-scoped credentials.

It should:

- bind a credential lifetime to a room-scoped identity
- validate credential chains
- optionally revoke or expire credentials

It should not:

- store room plaintext
- store message keys

### Delivery Service

The Delivery Service should be split conceptually into:

- directory and routing metadata
- mailbox provider service
- mix route infrastructure

It should:

- deliver ciphertext
- store padded mailbox batches only for short retention windows
- distribute routing information and current provider information

It should not:

- hold room message plaintext
- hold MLS application message keys

### Mix Route

Each route should contain:

- entry gateway
- mix node A
- mix node B
- mix node C
- provider or mailbox gateway

Every hop should only know:

- the previous hop
- the next hop
- its layer of routing instructions

No single honest relay should know both sender and recipient mailbox alias.

### Mailbox Provider

The mailbox provider should:

- accept mix-routed deliveries for mailbox aliases
- return padded retrieval batches
- support reply blocks or retrieval tokens
- keep only bounded short-lived ciphertext queues

It should not:

- know room plaintext
- know sender identity from application-level payloads

## Message Flow

### Room Creation

1. Creator generates a room-scoped pseudonymous identity.
2. Creator initializes an MLS group and obtains the initial `GroupInfo`.
3. Creator creates one or more mailbox aliases for the room identity.
4. Creator publishes a signed invite capability with expiry.
5. Creator begins padded mailbox polling immediately.

### Room Join

1. Joiner opens the invite.
2. Joiner validates the invite signature and expiry.
3. Joiner retrieves the current `GroupInfo` or external-join material.
4. Joiner creates a room-scoped identity and key package.
5. Joiner performs MLS external join or is added by an existing member.
6. Joiner starts padded mailbox polling and receives the Welcome path through the mixnet.

### Message Send

1. Sender creates MLS application data.
2. Sender frames it as Epher room application content.
3. Sender seals the MLS payload.
4. Sender wraps it in a Sphinx-style packet addressed to the recipient mailbox route or provider.
5. Sender injects the packet into the mix route.
6. Provider deposits ciphertext into the recipient mailbox queue.

### Message Retrieval

1. Client sends padded mailbox retrieval requests on a schedule.
2. Provider returns a fixed-size batch whether mail exists or not.
3. Client discards cover packets and decrypts real payloads.
4. Client applies MLS state updates and renders chat events locally.

### Replies

Replies should use a SURB-style or equivalent unlinkable response mechanism where practical.

At minimum, the design should preserve:

- sender and receiver unlinkability at the transport layer
- mailbox retrieval that does not reveal whether a real message was returned

## Packet Design

### Outer Transport Packet

Every transport packet should be fixed-size.

Initial design target:

- 2 KiB fixed packet size for normal chat traffic
- chunking for larger payloads

Why larger than the current prototype:

- the current prototype uses simple padded WebSocket frames
- v2 needs room for multi-hop headers, reply material, and future extension fields

### Inner Application Envelope

The inner payload should carry:

- room alias
- message class
- MLS ciphertext
- replay token
- expiry
- optional sequencing metadata

The inner envelope should never expose the raw room UUID to relays.

## Padded Retrieval Design

Every active client should retrieve mail on a schedule even when idle.

Initial design targets:

- foreground active room: poll every 4 seconds
- foreground idle app: poll every 8 seconds
- background connected app: poll every 30 seconds

Each retrieval response should return:

- exactly 4 fixed-size packets in foreground
- exactly 2 fixed-size packets in background

If fewer real packets are available, the provider fills the batch with cover packets.

## Cover Traffic Budget

These are starting targets for a testnet, not permanent safe defaults.

### Foreground Active

- one cover loop every 6 seconds per active room identity
- one padded mailbox pull every 4 seconds
- rotate route choices regularly within a bounded window

### Foreground Idle

- one cover loop every 12 seconds
- one padded mailbox pull every 8 seconds

### Background

- one cover loop every 45 seconds
- one padded mailbox pull every 30 seconds

### Battery Guardrails

- pause aggressive cover traffic in low battery mode
- reduce cover rate when the app is backgrounded for long periods
- let the user choose a privacy profile such as Standard, Strong, or Maximum

## Room Alias And Routing Privacy

Relays should not see:

- the raw room UUID
- the invite token
- the room label

Relays should only see:

- an opaque room alias derived from room material
- mailbox aliases
- route-level packet metadata

This means the delivery plane operates on opaque identifiers, not user-facing identifiers.

## Multi-Device And Recovery

MLS architecture makes it possible to support multiple devices per user, but Epher should keep the first privacy-focused rollout simpler.

Recommended v2 policy:

- treat every device as its own MLS client
- do not promise history recovery by default
- allow external rejoin with fresh state rather than silent history replication

If history recovery is added later, document clearly that it weakens some forward secrecy and post-compromise guarantees.

## Trust Model

### What Clients Trust

Clients must trust:

- their own device
- the correctness of the MLS implementation
- the correctness of local retention and wipe code

### What Clients Do Not Need To Trust

Clients should not need to trust:

- any single gateway
- any single mix node
- any single mailbox provider
- the Delivery Service with message plaintext

### What Can Still Leak

Even in the target architecture, some metadata can still leak:

- use of the network itself
- coarse timing based on polling windows
- route failure patterns
- room membership to room participants

This is still much stronger than direct mesh or single-relay chat, but it is not magic.

## Implementation Roadmap

### Phase 0. Freeze The Current Prototype

- keep the current mix relay as a prototype path only
- stop adding new privacy claims to the current transport

### Phase 1. Build The Cryptographic Core

- choose MLS library strategy
- move room state and group state into a Rust core
- expose a narrow FFI surface to Android
- add deterministic storage, replay protection, and migration hooks

### Phase 2. Build The Connector Daemon

- separate the mix transport connector from the main UI process
- make the connector responsible for:
  - Sphinx packet composition
  - route selection
  - mailbox polling
  - decoy traffic
  - reply handling

### Phase 3. Build The Test Mixnet

- deploy entry gateway, 3 mix nodes, and provider mailbox services
- add route rotation
- add provider queue retention limits
- add monitoring that avoids logging sensitive payload metadata

### Phase 4. Replace The Current Room Transport

- keep the UI and room product model
- swap the current pairwise transport for:
  - MLS room state
  - mixnet connector
  - padded mailbox retrieval

### Phase 5. External Review

- cryptographic review
- protocol review
- mobile client review
- infrastructure review
- traffic-analysis review

Only after this phase should Epher consider stronger anonymity claims.

## Audit Checklist

Epher should not call the v2 design audited until all of the following have happened.

### Cryptography

- review of MLS configuration, ciphers, credential model, and extension use
- review of replay handling
- review of history recovery and multi-device decisions

### Transport Protocol

- review of route construction
- review of Sphinx packet encoding
- review of reply block or retrieval-token design
- review of padding invariants
- review of route rotation and failure handling

### Metadata And Anonymity

- traffic-analysis review under passive observation
- review of cover traffic budget sufficiency
- review of padded retrieval indistinguishability
- review of relay logging and telemetry

### Mobile Client

- review of local encrypted storage
- review of room wipe correctness
- review of background polling behavior
- review of battery and network side effects

### Operations

- relay deployment review
- key management review
- infrastructure hardening review
- abuse controls and denial-of-service review

## Migration Plan From The Current App

### Keep

- Android UI
- room-first UX
- invite and retention concepts
- room-scoped local identities
- leave-and-wipe behavior

### Replace

- custom pairwise room protocol
- direct Hyperswarm-first assumption
- single-relay transport assumption
- current status labels that imply direct transport

### Transitional Plan

1. Keep the current app as the user-facing shell.
2. Introduce a Rust privacy core behind the existing repository layer.
3. Add MLS-backed room state first.
4. Add mixnet connector second.
5. Deprecate the current pairwise transport after migration.

## Recommended Canonical Statement

For Epher v2, the product should say:

- messages are end-to-end encrypted with MLS
- delivery is handled by a trust-minimized multi-hop mix network
- relays do not hold room plaintext keys
- traffic is padded and mixed to reduce size and timing correlation
- local room data is ephemeral by default

It should not say:

- no servers
- zero metadata
- audited anonymity

until that claim is backed by real external review.

## Source References

- [RFC 9420: The Messaging Layer Security (MLS) Protocol](https://www.rfc-editor.org/rfc/rfc9420)
- [RFC 9750: The Messaging Layer Security (MLS) Architecture](https://datatracker.ietf.org/doc/html/rfc9750)
- [Katzenpost Threat Model](https://katzenpost.network/docs/threat_model/)
- [Katzenpost Thin Client Design](https://katzenpost.network/docs/specs/thin_client.html)
- [Nym Noise Generating Mixnet](https://nym.com/features/mixnet)
- [Nym Litepaper](https://www.nym.com/nym_litepaper.pdf)
- [awslabs/mls-rs](https://github.com/awslabs/mls-rs)
- [OpenMLS Book](https://book.openmls.tech/introduction.html)
