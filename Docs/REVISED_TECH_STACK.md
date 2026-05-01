# Epher Revised Technical Stack

## Executive Summary

If Epher is room-first and Hyperswarm is a hard requirement, the cleanest architecture is not:

- native Android UI
- plus WebView app
- plus embedded Node.js runtime

That stack is too fragmented.

The better fit is:

- native Android shell for UI and platform integration
- embedded Bare runtime for the P2P engine
- Hyperswarm and HyperDHT for discovery and direct connections
- Corestore, Hypercore, and Autobase for room state replication

This keeps the product aligned with the Holepunch ecosystem instead of fighting it.

## Recommended Architecture

### Android Shell

- Language: Kotlin
- UI: Jetpack Compose + Material 3
- State: ViewModel + Coroutines + Flow
- Secret storage: Android Keystore
- Preferences: DataStore
- App lifecycle and permissions: native Android

The Android shell should handle:

- room list and room UI
- invite scanning and sharing
- notification policy
- app lifecycle
- storage policy
- bridge to the embedded P2P engine

### Embedded P2P Engine

- Runtime: Bare
- Language: JavaScript or TypeScript compiled for Bare
- Communication with Android shell: IPC bridge

The P2P engine should own:

- Hyperswarm instance
- HyperDHT configuration
- room topic joins and leaves
- replication streams
- room membership state
- message encryption and decryption

This replaces the earlier Node.js runtime idea. If you choose Hyperswarm, Bare is the better native fit than embedding Node.

## Holepunch Stack Recommendation

### Networking

- `hyperswarm` for topic-based peer discovery and connection management
- `hyperdht` as the DHT layer underneath
- `protomux` for multiplexing room protocols over one peer stream when needed

Use one Hyperswarm instance per app and join multiple room topics from it.

### Data And Replication

- `corestore` as the application-level core manager
- one Corestore instance per app
- one Autobase per room
- Hypercores per room writer
- optional Hyperbee view per room for indexed room state

This is a good fit for room chat because rooms are naturally replicated, append-only collaboration spaces.

### Mobile Lifecycle

Use the swarm suspend and resume model when the app backgrounds and resumes.

The engine should:

- suspend discovery and peer sockets when the app is backgrounded
- resume, refresh discovery, and reconnect when the app returns

## Why Hyperswarm Is Suitable

Hyperswarm is a good candidate for peer connectivity in this product because it already matches the room model:

- rooms can map to 32-byte topics
- peers can join multiple topics
- peer connections can be reused across topics
- streams are already Noise encrypted at the transport layer
- direct peer joins by known public key are supported

It is especially suitable if Epher is positioned as a Holepunch-native private room app rather than a generic mobile messenger.

## Where Hyperswarm Still Needs Care

### Bootstrap Reality

HyperDHT has default bootstrap infrastructure. A fully isolated DHT still needs at least one persistent bootstrap node.

So the correct claim is:

- no central message server

Not:

- no infrastructure of any kind

### NAT Reality

Hole punching works on many networks, not all networks. Epher should expect:

- some slow joins
- some failed direct paths
- some mobile network edge cases

This is a product and support concern, not just a protocol concern.

### Room Size Reality

Hyperswarm can connect many peers, but secure room messaging on mobile still has cost. V1 should stay intentionally small-room.

## Recommended Group Messaging Model

### V1 Model: Pairwise Fanout Inside A Room

For v1, keep the room UX but use pairwise secure sessions internally.

Each participant maintains:

- device identity key
- room membership signature state
- pairwise session state with active room peers

Each logical room message becomes:

- one room event
- N encrypted recipient payloads

This preserves a conservative security story for small private rooms.

### Why Not A Single Shared Room Key For Everything

A shared symmetric room key is tempting, but it weakens the security story around:

- compromise recovery
- member removal
- key rotation
- sender accountability

If you want strong group cryptography later, move to MLS or a sender-key design after the room product works.

## Room State Model

Each room should have:

- a room topic for Hyperswarm discovery
- a room Autobase for ordered multiwriter events
- a roster view
- a message view
- room metadata and versioning

Room events should include:

- member join
- member leave
- member remove
- room rekey
- message append
- room close

All control-plane events should be signed by device identity, and privileged events should additionally require room owner authority in v1.

## Storage Model

### Persist Locally

- device identity keys or wrapped key material
- trusted rooms and room metadata
- local encrypted room cache
- invite and verification metadata

### Keep Only In Memory

- active pairwise session keys
- live peer sockets
- unsent drafts
- current presence state

### Default Retention Policy

- delete local room content on leave
- delete stale room cache after a short TTL
- never store room content on a central server

Do not use "RAM-only" as the core promise. Use "ephemeral local retention by default."

## Security Model

### Claims We Can Defend

- room discovery is peer-to-peer first
- transport streams are Noise encrypted by Hyperswarm
- room messages are end-to-end encrypted between participants
- no central message storage
- local data can be minimized and auto-deleted by default

### Claims We Should Avoid

- zero metadata
- no infrastructure
- no local persistence at all
- strong large-group forward secrecy unless it is truly implemented
- guaranteed connectivity on hostile networks

## Why The Previous Stack Was Conflicting

The older documents mixed all of these together:

- Hyperswarm
- WebRTC
- STUN/TURN
- WebView UI
- Node.js runtime
- Compose UI
- RAM-only storage
- Keystore-backed persistent secrets

That is why the previous docs kept contradicting themselves.

If you choose Hyperswarm, choose the Holepunch-native branch clearly and remove the WebRTC and embedded Node split-brain.

## Recommended System Diagram

```text
Android App (Kotlin + Compose)
    |
    +-- Room UI
    +-- Invite flow
    +-- Keystore and DataStore
    +-- Lifecycle and notification handling
    |
IPC Bridge
    |
Bare Runtime Worker
    |
    +-- One Hyperswarm instance
    +-- HyperDHT
    +-- One Corestore instance
    +-- One Autobase per room
    +-- Pairwise session manager
    +-- Room encryption and event processing
```

## Recommended V1 Limits

- Android only
- text chat only
- room size target: 2 to 8 active peers
- single owner/admin model
- no attachments
- no background push delivery

## Research Track

Keep these out of the mainline build until the room core works:

- MLS or sender-key upgrade path
- larger-room scaling
- attachment replication
- Yggdrasil transport
- custom bootstrap topologies
- metadata-shaping and decoy traffic

## Recommended Next Build Steps

1. Define the room invite format and room event schema.
2. Prototype one Hyperswarm room with three peers and a single swarm instance.
3. Implement signed roster events and room owner controls.
4. Add pairwise encrypted fanout for room messages.
5. Add local encrypted cache plus delete-on-leave behavior.
6. Test background suspend and resume on Android before adding more features.
