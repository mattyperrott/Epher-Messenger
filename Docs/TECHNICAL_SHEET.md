# Epher App Technical Sheet

## Overview
Epher is a secure, privacy-focused, server-less, peer-to-peer (P2P) ephemeral messaging application designed for Android. It emphasizes end-to-end encryption, zero metadata retention, and strong traffic analysis resistance. The app supports flexible transport modes including direct P2P connections and an IPv6 overlay VPN.

---

## Features

### Core Features
- **End-to-End Encrypted Messaging:** Utilizes the Double Ratchet algorithm for forward secrecy and message confidentiality.
- **Peer-to-Peer Networking:** Decentralized peer discovery and connection using Hyperswarm Distributed Hash Table (DHT).
- **HMAC Peer Verification:** Challenge-response handshake with HMAC using a room pre-shared key to authenticate peers.
- **Transport Modes:** Supports direct Hyperswarm connections and Yggdrasil IPv6 overlay VPN for flexible networking.
- **File Sharing:** Secure encrypted file transfer with integrity verification.
- **Session Management:** Robust session lifecycle with automatic renewal, permission management, and activity tracking.
- **Replay Attack Prevention:** Detects and blocks replayed messages to maintain communication integrity.
- **Traffic Obfuscation:** Implements fixed-size packet padding, length prefixing, and timestamp jitter to mitigate traffic analysis.
- **Keep-Alive Mechanism:** Periodic decoy messages maintain active connections.
- **Rate Limiting & CSRF Protection:** Security measures to prevent abuse and cross-site request forgery.
- **VPN Detection:** Utility to detect active VPNs for network environment awareness.

### User Interface
- **Material Design 3:** Modern UI built with Material Web Components and Roboto fonts.
- **Responsive Layout:** Adaptive design for various Android device sizes.
- **Room Management:** Create, join, and manage chat rooms with unique IDs and optional password protection.
- **Message Input:** Text input with support for file attachments.
- **Status Indicators:** Real-time display of connection status, cryptographic verification, and session timers.
- **Security Panel:** User controls for transport mode selection, packet shaping, forward secrecy, and screenshot prevention.
- **Notifications:** Snackbar alerts for system messages, errors, and status updates.
- **Modals and Drawers:** Interfaces for room password entry, error messages, connected peers list, and settings.

---

## Architecture and Technologies

### Frontend
- **Platform:** WebView embedded in Android app.
- **Technologies:** HTML, CSS (Material Design 3), JavaScript ES Modules.
- **UI Framework:** Material Web Components.
- **Cryptography:** Web Worker sandbox for cryptographic operations using libsodium.
- **Session & State Management:** Modular JavaScript services for session, messages, UI state, and settings.
- **Communication:** AndroidBridge JavaScript interface for native interaction.

### Backend
- **Runtime:** Embedded Node.js 18 runtime bundled within the APK.
- **Networking:** Hyperswarm and Hyperbeam libraries for P2P networking and hole punching.
- **Cryptography:** libsodium for authenticated encryption (XChaCha20-Poly1305) and key exchange (Ed25519).
- **Transport Switching:** Dynamic switching between direct and VPN transports.

### Android Native
- **UI:** Jetpack Compose with Material3 components for native screens and navigation.
- **VPN Service:** YggVpnService running Yggdrasil IPv6 mesh VPN client as Android VpnService.
- **Security:** Hardened WebView with strict content loading policies and JavaScript interface sandboxing.

---

## Cryptography Measures
- **Double Ratchet Algorithm:** For message encryption and forward secrecy.
- **Pre-Key Bundles:** X25519 one-time pre-keys for initial key exchange.
- **Authenticated Encryption:** XChaCha20-Poly1305 with Ed25519 signatures.
- **Key Rotation:** Group key rotation every 100 messages.
- **Forward Secrecy:** Enabled by default.
- **Replay Protection:** Detection and prevention of replayed messages.
- **Password Derivation:** Argon2id with PBKDF2 fallback for room password key derivation.
- **Sandboxing:** Cryptographic operations isolated in Web Worker to protect keys.

---

## Security Measures
- **Zero Metadata Retention:** No persistent logs or message storage; messages live only in RAM.
- **Traffic Analysis Resistance:** Packet padding, timestamp jitter, and decoy keep-alives.
- **Screenshot Prevention:** Optional UI-level screenshot blocking.
- **CSRF Protection:** Token generation and validation.
- **Rate Limiting:** Limits on message and file sending to prevent abuse.
- **VPN Conflict Detection:** Prevents starting VPN if another VPN is active.
- **Hardened WebView:** Restricts URL loading to local assets only.

---

## Protocols and Standards
- **Networking:** Hyperswarm DHT for decentralized peer discovery.
- **VPN:** Yggdrasil IPv6 mesh VPN protocol.
- **Cryptography:** libsodium (NaCl) cryptographic library.
- **UI:** Material Design 3 guidelines and components.
- **Android:** Jetpack Compose, Android VpnService API.

---

## Performance and Reliability
- **Automatic Reconnection:** Handles network interruptions and transport fallback.
- **Session Management:** Pause and resume on app visibility changes.
- **Efficient Message Queuing:** Ensures ordered and reliable message delivery.
- **Offline Capability:** All cryptographic and runtime dependencies bundled locally.
- **Resource Management:** Native binaries and Node.js runtime embedded for self-contained operation.

---

## Testing
- Unit tests covering cryptography, networking, message handling, and session management are included in the project.

---

This technical sheet summarizes the comprehensive design, implementation, and security posture of the Epher secure messaging app, suitable for documentation and developer reference.
