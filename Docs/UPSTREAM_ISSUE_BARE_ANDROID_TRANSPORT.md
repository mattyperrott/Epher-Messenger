# Android BareKit + HyperDHT/Hyperswarm sockets connect/open but do not deliver application payloads

## Summary

On Android, using BareKit worklets, encrypted peer sockets can reach `connected` / `open`, but ordered (`socket.write`) and unordered (`socket.send`) application payloads are never received on the other side.

This reproduces in a minimal probe outside the Epher room protocol.

## Environment

- App repo: `/Users/mbp/Desktop/Epher-Codex`
- BareKit Android AAR integrated from `holepunchto/bare-kit`
- Worklet runtime packed with `bare-pack`
- Android emulators:
  - `emulator-5554`
  - `emulator-5556`
- Probe variants:
  - `com.epher.app.debug`
  - `com.epher.app.peer`

## Relevant upstream expectations

- Hyperswarm emits `connection` only after the underlying secret stream emits `open`:
  - [hyperswarm `index.js`](https://github.com/holepunchto/hyperswarm/blob/main/index.js)
- HyperDHT sockets are documented as encrypted duplex streams:
  - [hyperdht README](https://github.com/holepunchto/hyperdht)
- `@hyperswarm/secret-stream` says:
  - `write()` is a normal buffered duplex write
  - `send()` may silently fail before handshake completion
  - [hyperswarm-secret-stream README](https://github.com/holepunchto/hyperswarm-secret-stream)

## Minimal repro in this repo

- Runtime:
  - [/Users/mbp/Desktop/Epher-Codex/runtime/src/p2p-probe-runtime.js](/Users/mbp/Desktop/Epher-Codex/runtime/src/p2p-probe-runtime.js)
- Android session:
  - [/Users/mbp/Desktop/Epher-Codex/app/src/main/java/com/epher/app/debug/BareTransportProbeSession.kt](/Users/mbp/Desktop/Epher-Codex/app/src/main/java/com/epher/app/debug/BareTransportProbeSession.kt)
- Android activity:
  - [/Users/mbp/Desktop/Epher-Codex/app/src/main/java/com/epher/app/debug/BareTransportProbeActivity.kt](/Users/mbp/Desktop/Epher-Codex/app/src/main/java/com/epher/app/debug/BareTransportProbeActivity.kt)
- Full repro notes:
  - [/Users/mbp/Desktop/Epher-Codex/BARE_ANDROID_HYPERSWARM_REPRO.md](/Users/mbp/Desktop/Epher-Codex/BARE_ANDROID_HYPERSWARM_REPRO.md)

## Repro steps

Build:

```bash
./gradlew :app:assembleDebug :app:assemblePeer
```

Install:

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/peer/app-arm64-v8a-peer.apk
```

Launch:

```bash
adb -s emulator-5554 shell am start -n com.epher.app.debug/com.epher.app.debug.BareTransportProbeActivity
adb -s emulator-5556 shell am start -n com.epher.app.peer/com.epher.app.debug.BareTransportProbeActivity
```

## What the probe does

It runs two independent tests:

1. Hyperswarm topic join on a shared fixed topic
2. Direct HyperDHT server/client connection on fixed deterministic keys

It intentionally removes:

- room protocol
- peer cards
- E2EE/session logic
- message envelopes

It only tests:

- connection establishment
- `socket.write(Buffer)`
- `socket.send(Buffer)`
- receive callbacks (`data` / `message`)

## Observed behavior

### Hyperswarm topic path

Both peers:

- announce/join the same topic
- log `probe peer transport connected ...`
- send repeated ordered/unordered probes

But neither peer ever logs:

- `probe ordered rx ...`
- `probe datagram rx ...`

Eventually the connection times out.

### Direct HyperDHT path

With `localConnection=false`:

- client reaches `probe peer transport connected ... via direct-client-remote-only`
- may fail with `PEER_NOT_FOUND` if it races the server announce
- otherwise later fails with `HOLEPUNCH_ABORTED`
- still no received payload logs

With `localConnection=true`:

- client reaches:
  - `probe peer transport connected ... via direct-client`
  - `probe peer ready ...`
  - `probe socket open ... via direct-client`
- client sends ordered probes repeatedly
- still no receive callbacks fire on either side

## Why this seems upstream

The failure reproduces after removing Epher’s higher-level logic.

At this point it is not:

- room handshake logic
- peer-card exchange
- pairwise encryption
- application framing/parsing

The common remaining layer is Android BareKit worklet + HyperDHT/secret-stream payload delivery.

## Questions

1. Is there any Android-specific limitation or missing step for consuming payloads from HyperDHT/Hyperswarm sockets inside BareKit worklets?
2. Is `connection` / `open` on Android currently stronger than the underlying payload path can guarantee?
3. Is there a known issue around UDX/raw-stream payload delivery on Android emulators specifically?
4. Is there a recommended official mobile example that sends and receives application bytes over HyperDHT/Hyperswarm, not just starts a worklet?
