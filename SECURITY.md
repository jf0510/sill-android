# Security

This client is experimental and has not undergone an independent security audit.
Only the current development branch receives fixes; there is no separate support
commitment for older preview builds.

## Reporting a vulnerability

Use [GitHub private vulnerability reporting](https://github.com/jf0510/sill-android/security/advisories/new)
for issues involving authentication, pairing, certificate verification, or
unintended access to a Mac. Do not include pairing codes, private keys, desktop
contents, or personal network details in a public issue.

## Connection model

- Sessions use TLS 1.3 with Sill ALPN identifiers and a pinned SHA-256 digest of
  the Mac's public-key encoding. There is no plaintext fallback.
- Initial code pairing accepts an unpinned certificate only for that exchange;
  the client verifies the Mac's code-derived proof before retaining its identity.
- Device keys are generated and used through Android Keystore. Pairing metadata
  is kept in private application storage and Android backup is disabled.
- Protocol messages and the compressed-video backlog have explicit bounds.
  These are defensive limits, not a complete audit of malicious-host behavior.

A paired client can view streamed content and send input to the Mac. Pair only
with Macs you are authorized to control. Remove the client's device entry in the
Mac's Sill settings when access is no longer needed. Forgetting a Mac on Android
removes the local pairing record; it does not revoke the device on the Mac.

Debug builds include a synthetic UI preview and a USB/ADB diagnostic route.
These entry points are disabled in release builds. The diagnostic route retains
TLS pinning and is not a user-facing USB pairing implementation.
