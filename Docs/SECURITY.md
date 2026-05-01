# Epher Security Documentation

This document outlines the security measures implemented in the Epher Android application to protect user data, ensure secure communications, and prevent unauthorized access.

## Table of Contents
1. [Cryptographic Security](#cryptographic-security)
2. [Secure Storage](#secure-storage)
3. [Network Security](#network-security)
4. [Runtime Integrity](#runtime-integrity)
5. [WebView Security](#webview-security)
6. [Dependency Verification](#dependency-verification)
7. [Data Protection](#data-protection)
8. [Key Management](#key-management)

## Cryptographic Security

### Implemented in `cryptoWorker.js`
- **Memory Management Limits**: Implemented `MAX_SEEN_MESSAGES` and `MAX_SKIP` limits to prevent unbounded memory growth and potential DoS attacks.
- **Time-based Key Rotation**: Keys are automatically rotated every 30 minutes to limit the impact of key compromise.
- **Proper Memory Cleanup**: Cryptographic material is properly zeroed out after use to prevent memory disclosure attacks.
- **Constant-time Comparisons**: All cryptographic comparisons use constant-time algorithms to prevent timing attacks.
- **Message Authentication**: Messages include metadata binding to prevent message replay and manipulation.
- **Secure Key Management**: Skipped message keys expire after a configurable period to limit attack surface.
- **Enhanced File Encryption**: File encryption uses separate keys for content and metadata with integrity verification.

## Secure Storage

### Implemented in `KeystoreManager.kt`
- **Android Keystore**: All cryptographic keys are stored in the Android Keystore System, protected by hardware when available.
- **Room-specific Keys**: Each chat room has its own encryption key that is automatically wiped when exiting a room.
- **AES-GCM Encryption**: All sensitive data is encrypted using AES-GCM (256-bit keys) with authenticated encryption.
- **Secure Random Generation**: All random values use the SecureRandom API with proper seeding.
- **Key Separation**: Different types of keys (app keys, room keys, user keys) are strictly separated.
- **Key Wiping**: Room keys are completely removed from the Keystore when no longer needed.

## Network Security

### Implemented in `network_security_config.xml`
- **Certificate Pinning**: SSL certificates for Epher domains are pinned to prevent MitM attacks.
- **TLS Enforcement**: All connections must use TLS; cleartext traffic is disabled.
- **Trust Anchors**: Only system-provided certificates are trusted by default.
- **Domain-specific Rules**: Enhanced security rules for Epher domains with specific certificate requirements.
- **Backup Certificates**: Backup certificate pins are included for reliability in case the primary certificate is compromised.

### Implemented in `NodeJSBridge.kt`
- **Certificate Verification**: HTTPS connections verify server certificates against expected fingerprints.
- **Connection Security**: All socket connections use secure protocols with proper error handling.
- **Input Validation**: All inputs from network sources are validated and sanitized.

## Runtime Integrity

### Implemented in `IntegrityChecker.kt`
- **Application Signature Verification**: Verifies that the app hasn't been tampered with or repackaged.
- **Emulator Detection**: Identifies if the app is running in an emulator environment.
- **Root Detection**: Checks for signs that the device has been rooted.
- **Debugger Detection**: Monitors for attached debuggers to prevent runtime manipulation.
- **Critical File Monitoring**: Monitors important app files for unexpected changes.
- **Periodic Checks**: Performs security checks at regular intervals during app execution.

## WebView Security

### Implemented in `MainActivity.kt`
- **Content Security Policy (CSP)**: Restricts the types of content that can be loaded in the WebView.
- **JavaScript Restrictions**: Limits JavaScript execution to necessary contexts with proper validation.
- **File Access Restrictions**: Disables access to file:// URLs and other potentially dangerous resources.
- **Safe Browsing**: Enables Google Safe Browsing to protect against malicious content.
- **Mixed Content Blocking**: Prevents loading of insecure resources in secure contexts.
- **Screenshot Prevention**: Prevents screenshots and screen recording of sensitive content using FLAG_SECURE.
- **URL Navigation Controls**: Restricts WebView navigation to whitelisted domains only.

## Dependency Verification

### Implemented in `DependencyVerifier.kt`
- **Native Library Verification**: Verifies that native libraries haven't been tampered with using SHA-256 checksums.
- **Architecture-specific Checks**: Different checksums for each supported architecture.
- **APK Integrity**: Monitors for unexpected changes to the APK.
- **Dependency Caching**: Caches verification results for performance.

## Data Protection

### Implemented across the application
- **Automatic Cache Clearing**: WebView cache and cookies are cleared periodically and on app exit.
- **Clipboard Protection**: Secure handling of clipboard operations to prevent clipboard leakage.
- **Form Data Protection**: Prevents saving of form data and passwords.
- **Backup Exclusion**: Sensitive app data is excluded from Android's automatic backup system.

## Key Management

### Implemented in `MainActivity.kt` and `KeystoreManager.kt`
- **Room Key Lifecycle**: 
  1. Keys are created when joining a room using `KeystoreManager.createRoomKey()`
  2. Keys are securely stored in the Android Keystore
  3. Keys are completely wiped when exiting a room using `KeystoreManager.wipeRoomKey()`
  4. All keys are wiped when the application is closed
- **Application Keys**: Persistent keys used for application-wide encryption are protected by the Android Keystore.
- **Separate Key Domains**: Different encryption domains use different keys to maintain separation of concerns.

## Security Best Practices

The Epher application follows these security best practices:
- **Defense in Depth**: Multiple layers of security controls are implemented.
- **Principle of Least Privilege**: Components only have access to the resources they need.
- **Secure by Default**: Security is enabled by default, with no insecure fallbacks.
- **Fail Securely**: When errors occur, the system fails in a secure state.
- **Complete Mediation**: All access to resources is checked for authorization.
- **Input Validation**: All inputs from untrusted sources are validated.
- **Regular Security Updates**: The security measures are regularly reviewed and updated.

## Security Update Procedure

If security vulnerabilities are discovered:
1. Assess the severity and impact of the vulnerability
2. Develop and test a fix
3. Release an update through the appropriate channels
4. Notify users of the update and its importance
5. Monitor for any exploitation of the vulnerability
