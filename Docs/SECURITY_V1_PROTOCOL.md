# Epher Security V1

This document describes the security model currently implemented in the Android app and the transport/security work that is still planned.

## Implemented Now

- `Device identity`: local `Ed25519` signing keypair plus `X25519` key agreement keypair, generated on-device.
- `Key storage`: encrypted local persistence protected by an Android Keystore AES-GCM master key.
- `Room secret derivation`: `Argon2id` from `room_id + password`. The app fails closed if Argon2id is unavailable.
- `Invite authentication`: signed invite payloads containing room metadata, owner fingerprint, and public keys.
- `Peer authentication`: HMAC challenge-response derived from the room authentication key.
- `Message encryption`: `XChaCha20-Poly1305` sealed envelopes with associated data bound to room ID, sender fingerprint, message ID, and sequence number.
- `Replay protection`: signed message IDs plus a bounded replay window per room session.
- `Traffic shaping`: direct `Hyperswarm` mode does not add active traffic shaping beyond transport behavior. The optional mix-relay development path uses fixed-size padded relay frames, jittered polling, mailbox pulls, and cover packets.
- `Attachment encryption`: encrypted attachment envelopes using a dedicated room attachment key, with manifest/chunk validation, SHA-256 digest verification after decryption, and stale partial-transfer cleanup.
- `Metadata policy`: no server-side message retention; only encrypted local session state is persisted.
- `Transport`: `Noise` over `Hyperswarm/HyperDHT` through the embedded Bare runtime when no relay URL is configured.
- `Direct peer hints`: signed invites include the owner's transport public key so joiners can attempt a direct peer dial before relying only on topic discovery.
- `Owner removal`: room owners can mark a verified peer as removed, rotate the room secret to a new epoch, and send the new room secret only to remaining verified peers over pairwise encrypted control messages.

## Optional Transport Paths

- `Primary mode`: direct-first P2P through Bare/Hyperswarm.
- `Bootstrap`: explicit HyperDHT bootstrap nodes configured at build time.
- `Room relay`: optional relay-assisted ciphertext transport when a room relay URL is configured.
- `Mix relay`: optional development transport with padded frames, mailbox polling, and cover packets. This is not a production-anonymity-safe multi-hop mixnet.
- `Fallback`: production NAT-failure relay strategy and relay authentication policy still need a deployment-specific design.

## Group Messaging Model

The current app foundation uses a per-room chain ratchet for local envelope protection and replay handling.

For production group messaging, the intended direction is:

- `V1 networked rooms`: pairwise secure fan-out over peer sessions
- `Membership changes`: participants can leave locally. Owners can remove a peer and rotate future room traffic to a new epoch; this is not full MLS membership and does not retroactively revoke messages or files the removed peer already received.
- `Future`: evaluate `MLS` or sender-key style group ratchets once the transport runtime is in place

## Non-Goals For This Build

- Full `Signal Double Ratchet` integration
- Production-anonymity-safe multi-hop mixnet guarantees
- MLS group state, MLS welcome flows, or MLS remove commits
- Real `Yggdrasil` overlay routing
- Retroactive revocation of data already received by a removed participant

Those pieces require additional protocol design and should not be presented as complete until they are implemented and audited.
