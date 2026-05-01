# Bare Android Hyperswarm Repro

This repo contains a minimal Android-only BareKit transport probe that reproduces the current failure independently of Epher room logic.

## Purpose

Prove whether raw application payloads can cross a connected encrypted peer socket on Android using BareKit.

This probe intentionally removes:

- room handshake logic
- peer cards
- pairwise crypto
- Epher message envelopes

It tests only:

- Hyperswarm discovery
- peer connection establishment
- direct HyperDHT server/client connection establishment
- ordered payload writes (`socket.write`)
- unordered payload writes (`socket.send`)

## Files

- Probe runtime:
  - [/Users/mbp/Desktop/Epher-Codex/runtime/src/p2p-probe-runtime.js](/Users/mbp/Desktop/Epher-Codex/runtime/src/p2p-probe-runtime.js)
- Android probe session:
  - [/Users/mbp/Desktop/Epher-Codex/app/src/main/java/com/epher/app/debug/BareTransportProbeSession.kt](/Users/mbp/Desktop/Epher-Codex/app/src/main/java/com/epher/app/debug/BareTransportProbeSession.kt)
- Android probe activity:
  - [/Users/mbp/Desktop/Epher-Codex/app/src/main/java/com/epher/app/debug/BareTransportProbeActivity.kt](/Users/mbp/Desktop/Epher-Codex/app/src/main/java/com/epher/app/debug/BareTransportProbeActivity.kt)
- Build wiring:
  - [/Users/mbp/Desktop/Epher-Codex/app/build.gradle.kts](/Users/mbp/Desktop/Epher-Codex/app/build.gradle.kts)

## How To Run

Build both app variants:

```bash
./gradlew :app:assembleDebug :app:assemblePeer
```

Install on two devices or emulators:

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/peer/app-arm64-v8a-peer.apk
```

Launch the probe activity directly:

```bash
adb -s emulator-5554 shell am start -n com.epher.app.debug/com.epher.app.debug.BareTransportProbeActivity
adb -s emulator-5556 shell am start -n com.epher.app.peer/com.epher.app.debug.BareTransportProbeActivity
```

Watch logs:

```bash
adb -s emulator-5554 logcat -d | rg 'probe '
adb -s emulator-5556 logcat -d | rg 'probe '
```

## Expected Behavior

After peer connection, each side should log one or more of:

- `probe ordered rx ...`
- `probe datagram rx ...`

That would prove application payload delivery works on top of the connected encrypted transport.

## Actual Behavior

Observed repeatedly on two Android emulators.

### A. Hyperswarm topic probe

- both sides announce the same topic
- both sides log `probe peer transport connected ...`
- one side repeatedly logs:
  - `probe sent (connect) ...`
  - `probe sent (heartbeat) ...`
- neither side logs:
  - `probe ordered rx ...`
  - `probe datagram rx ...`
- eventually the connection times out

### B. Direct HyperDHT probe

The probe also runs a fixed-key direct HyperDHT server/client pair.

With `localConnection=false`:

- the client reaches `probe peer transport connected ... via direct-client-remote-only`
- the client may fail with:
  - `PEER_NOT_FOUND`, if the server is not yet announced
  - `HOLEPUNCH_ABORTED: Holepunch aborted`
- still no `probe ordered rx ...` or `probe datagram rx ...`

With `localConnection=true`:

- the client reaches:
  - `probe peer transport connected ... via direct-client`
  - `probe peer ready ...`
  - `probe socket open ... via direct-client`
- the client sends ordered probes successfully
- still no `probe ordered rx ...` or `probe datagram rx ...`
- the server side still does not show corresponding receive logs

This is the strongest current isolate because it removes topic discovery from the data path.

## Conclusion

The failure reproduces without any Epher room/session code.

That means the current Android failure is below the app protocol layer:

- not a room handshake bug
- not a peer-card bug
- not a pairwise encryption bug
- not only a Hyperswarm discovery/topic bug

The remaining problem is in raw application payload delivery over the Android BareKit + HyperDHT/Hyperswarm transport path after the encrypted socket reports open/connected.

## Next Steps

1. Run this exact probe on two real Android devices.
2. Compare behavior against the official expectation:
   - Hyperswarm emits `connection` after `open` ([hyperswarm source](https://github.com/holepunchto/hyperswarm/blob/main/index.js))
   - HyperDHT sockets are normal encrypted duplex streams ([hyperdht README](https://github.com/holepunchto/hyperdht))
   - SecretStream supports buffered `write()` after open/connect ([hyperswarm-secret-stream README](https://github.com/holepunchto/hyperswarm-secret-stream))
3. If the probe still fails on real devices, open an upstream issue against the relevant Holepunch mobile/Bare transport layer with this repro.
