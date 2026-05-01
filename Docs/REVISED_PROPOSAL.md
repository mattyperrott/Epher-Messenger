# Epher Revised Proposal

## Product Summary

Epher should be a private, room-based Android messenger for small group conversations. The core product is not a public social chat app and not a mass-market replacement for Signal. It is a private room tool for people who want direct peer connectivity, no server-side message storage, and stronger control over local retention.

## Product Thesis

Build a room-first messenger where a room is the product primitive:

- users join a room through an invite or QR code
- the room connects peers directly through Hyperswarm
- messages are end-to-end encrypted between participants
- the service does not store room message history
- local room history is ephemeral by default

## Why This Direction Fits The Idea Better

The original concept already thinks in rooms, shared secrets, and multiple peers. A room-first design is more natural than forcing the product into a one-to-one messenger shape and adding groups later.

Hyperswarm also fits the room mental model well:

- rooms map naturally to topics
- peers can join many rooms on one swarm instance
- shared peer connections can be deduplicated
- peers can reconnect when the app resumes

## Target User And Use Case

Epher should target small private groups that want a temporary encrypted room:

- friends
- project teams
- field operators
- activists or journalists
- short-lived private discussion groups

It should optimize for private coordination, not for large communities, content feeds, or cloud history.

## Recommended V1 Scope

### In Scope

- Android only
- Room-based group chat
- Text messages
- Invite link or QR room join
- Small private rooms
- End-to-end encrypted room messaging
- Peer presence and room roster
- Ephemeral local retention by default
- Manual room verification and room safety details

### Out Of Scope

- Public rooms
- Large-scale communities
- File transfer
- Push notifications
- Multi-device sync
- Guaranteed offline delivery
- Perfect anonymity claims
- Strong "nothing ever touches storage" claims

## Room Model

### Room Creation

Each room should be created from an invite package, not a manually typed room secret in normal use.

The invite package should include:

- room identifier
- room discovery material
- room authentication material
- room version information

Prefer QR code and deep-link sharing over manual password entry.

### Room Size

For v1, rooms should be intentionally small. A good target is 2 to 8 active peers, with 12 as a stretch ceiling.

This keeps the product aligned with private coordination and allows a safer cryptographic model for the first release.

### Room Roles

V1 should include a room owner or admin role. That makes it easier to:

- admit new members
- remove members
- rotate room secrets when membership changes
- keep the room state coherent

## Security Position

### What Epher Should Promise

- Messages are end-to-end encrypted between room participants.
- The service does not store plaintext or encrypted room messages.
- Peer discovery and connection setup are peer-to-peer first.
- Local retention is short-lived by default.
- Room membership and room events are authenticated.

### What Epher Should Not Promise

- No RAM use
- Zero metadata at every layer
- Guaranteed relay-free connectivity on every mobile network
- Massive group sizes in v1
- Strong group forward secrecy if the implementation does not actually provide it

## Recommended Group Security Model

For v1, use room UX with pairwise cryptographic sessions underneath.

That means:

- every participant has a long-lived device identity
- every participant establishes pairwise secure sessions with other active room members
- one logical room message is encrypted separately for each recipient

This gives a room-based group experience while avoiding a rushed custom group encryption design.

### Why This Is The Best V1 Tradeoff

- keeps the "double ratchet" direction viable for small rooms
- avoids pretending that a single shared room password equals strong group cryptography
- makes membership changes easier to reason about
- keeps a later path open toward MLS or sender keys

### Tradeoff

Per-message work grows with room size, which is why the initial room cap matters.

## Data Retention Position

Be precise and honest:

- no server-side message storage
- local encrypted room cache allowed
- auto-delete on leave by default
- auto-expiry timer for inactive room data
- identity keys and trusted room metadata may persist locally

This is much more defensible than "RAM only" for a real mobile group app.

## Connectivity Position

The product should say:

- direct peer discovery is built on Hyperswarm and HyperDHT
- the system prefers direct peer connectivity
- bootstrap infrastructure is still part of the network reality
- some NAT combinations will still fail or connect slowly

This avoids the earlier contradiction between "no infrastructure ever" and the actual behavior of DHT-based systems.

## User Experience Direction

### Primary Flow

1. User installs Epher.
2. User creates a local device identity.
3. User creates a room or opens an invite.
4. The app joins the room topic and discovers peers.
5. Peers authenticate room membership and establish secure sessions.
6. Messages appear in a shared room timeline.
7. Leaving the room deletes local room content by default.

### Key UI Surfaces

- Room list
- Active room chat view
- Room roster
- Room safety sheet
- Invite / join flow
- Connection and sync status

## Product Risks To Manage

- Small-room cryptography turning into large-room expectations
- Users interpreting "P2P" as "always reliable on every network"
- Retention claims being stronger than the actual local cache behavior
- Membership changes and key rotation becoming inconsistent

## Suggested Roadmap

### Phase 1

- Two-device room creation and join
- Stable Hyperswarm connectivity
- Pairwise secure sessions inside a room
- Shared room timeline
- Local auto-delete on leave

### Phase 2

- Better reconnection and room resume behavior
- Membership changes and room rekey flows
- Room owner controls
- Short-lived encrypted local cache with expiry

### Phase 3

- Larger-room research
- MLS or sender-key evaluation
- Attachments
- Optional alternative transports such as Yggdrasil

## Success Criteria For V1

- Three to five Android devices can reliably form a private room.
- Room messaging works without central message storage.
- Users can understand who is in the room and whether the room is verified.
- The app explains its privacy guarantees honestly.
- The architecture is small enough to actually build and debug.
