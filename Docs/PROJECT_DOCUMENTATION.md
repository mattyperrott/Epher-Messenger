# Epher Project Documentation and Feature Mapping

## Project Overview
Epher is a secure, privacy-focused, server-less, peer-to-peer (P2P) ephemeral messenger application designed for Android. It uses a double-ratchet cryptographic protocol for end-to-end encryption with forward secrecy. The application emphasizes zero metadata retention, no reliance on clearnet or servers, and ephemeral message handling with strong traffic analysis countermeasures.

---

## Feature and Specification Mapping

| Feature / Specification | Implementation Status | Details / Notes |
|-------------------------|-----------------------|-----------------|
| **100% Peer To Peer** | Aligned | Uses Hyperswarm DHT for decentralized peer discovery and hole punching. No relays or bootstrap servers except optional Yggdrasil bootstrap. |
| **Transport Encryption Wrapper** | Aligned | Hyperswarm sockets wrapped with double-ratcheted XChaCha20-Poly1305 + Ed25519 encryption using libsodium. |
| **Forward Secrecy** | Aligned | Implements X25519 one-time pre-keys and double-ratchet protocol advancing on every message. |
| **Ephemeral Messaging** | Aligned | Messages live only in the DOM, wiped on disconnect. No persistent storage or logs of messages. |
| **Transport Choice** | Aligned | Supports direct Hyperswarm transport and optional Yggdrasil IPv6 overlay VPN transport. |
| **Packet Shaping and Traffic Obfuscation** | Aligned | Implements 256-byte packet padding, ±250 ms timestamp jitter, and decoy keep-alives to frustrate traffic analysis. |
| **Crypto in Sandbox** | Aligned | Cryptographic operations run inside a Web Worker, isolating keys from UI JavaScript. |
| **Self-Contained Runtime** | Aligned | APK bundles Node.js 18 runtime and Yggdrasil binary locally; no external downloads required. |
| **Offline Capability** | Aligned | libsodium JS and WASM loaded locally from assets, no network fetches. |
| **Zero Metadata and RAM-only Storage** | Partially Aligned | Ephemeral message handling and no persistent logs are implemented. Explicit zero metadata claims are implied but not fully verifiable from code. |
| **UI Layout and Material Design 3 Compliance** | Partially Aligned | Uses Jetpack Compose with Material3 components. Core UI elements like navigation, privacy sheet, and status indicators are present. Some detailed UI elements described in the proposal are partially evident. |

---

## Component Architecture

- **Android App**
  - Jetpack Compose UI with Material3 design.
  - MainActivity hosts a hardened WebView loading local frontend assets.
  - Navigation includes chat and privacy screens.
  - Integrates YggVpnService for VPN transport.
  - NodeJSBridge facilitates communication between WebView JS and Node.js backend.

- **Node.js Backend**
  - Uses Hyperswarm and Hyperbeam for P2P networking.
  - Implements NetworkManager for peer connections, verification, message handling, and transport switching.
  - Uses libsodium for cryptography and message encryption.

- **Frontend (WebView JS)**
  - Implements core app logic, session management, crypto initialization, and UI state.
  - Uses cryptoBridge.js for cryptographic operations.
  - Provides notifications, rate limiting, transport toggling, and logging services.

- **VPN Service**
  - YggVpnService runs Yggdrasil VPN client as Android VpnService.
  - Establishes IPv6 VPN interface and manages VPN lifecycle.

---

## Security Highlights

- End-to-end encryption with double-ratchet and authenticated encryption.
- Transport stream encryption before leaving device.
- Worker sandboxing to isolate cryptographic keys.
- Traffic shaping and jitter to prevent metadata leakage.
- Ephemeral message lifecycle with no persistent storage.
- Peer verification and room password protection.

---

## Message Lifecycle Summary

1. Session creation with room ID and password.
2. Secure key derivation using Argon2id and PBKDF2 fallback.
3. Peer verification and authenticated message exchange.
4. Message encryption and ratchet advancement.
5. Ephemeral message handling and replay defense.
6. Transport switching between direct and VPN modes.

---

## UI Overview

- Backdrop Scaffold with navigation and branding.
- Top app bar with app name and security icon.
- Chat interface with room banner, message container, and input area.
- Persistent bottom status strip with connection and crypto status.
- Privacy and settings screens with transport and security toggles.
- Notifications and status badges for real-time feedback.

---

## Conclusion

The Epher project aligns closely with the proposed application rundown, implementing a secure, server-less, P2P ephemeral messenger with strong privacy, cryptography, and transport features. Some UI details and explicit zero metadata claims are partially verifiable but consistent with the overall design.

This document can be extended with more detailed technical specs, API references, and developer guides as needed.

---
