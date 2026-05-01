# Epher

Epher is a private, room-based Android chat concept built around peer-to-peer discovery, end-to-end encrypted group messaging, and ephemeral-by-default local retention.

This repository currently contains planning and proposal material. The older documents include multiple conflicting directions. The files below are the current source of truth.

## Current Source Of Truth

- [REVISED_PROPOSAL.md](./REVISED_PROPOSAL.md)
- [REVISED_TECH_STACK.md](./REVISED_TECH_STACK.md)
- [FEATURES_BY_SCREEN.md](./FEATURES_BY_SCREEN.md)

## Future Privacy Architecture

- [EPHER_V2_MIXNET_ARCHITECTURE.md](./EPHER_V2_MIXNET_ARCHITECTURE.md)

## Current Product Direction

- Android-first private room chat
- Group-chat UX from day one
- The current app prototype experiments with direct and relay-assisted transports
- The proposed long-term privacy architecture is MLS plus mixnet-assisted delivery
- End-to-end encrypted room messaging
- Ephemeral local retention by default

## Important Scope Boundaries

- Small private rooms, not large public channels
- No central message storage
- No WebView plus Node hybrid runtime
- No "zero metadata everywhere" claim
- No guarantee that relay-free hole punching succeeds on every network

## Archive Note

The existing `PROJECT_DOCUMENTATION*`, `TECHNICAL_SHEET*`, `SECURITY*`, `rtf`, `txt`, and comparison files are preserved as historical inputs. They should be treated as reference material, not as the canonical implementation plan.
