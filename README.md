# Sill for Android

An **unofficial, experimental Android client** for [Sill](https://github.com/Saffsanity/sill).
View a Mac window or desktop on an Android phone or tablet and control it with touch.

This project is maintained independently by [John Fu](https://github.com/jf0510).
It is not affiliated with, endorsed by, or supported by Sill's author.
The Mac host is the existing Sill application; no host modifications are required.

## Status

The client supports pairing, desktop and window streaming, touch input, a portrait
trackpad, basic software keyboard input, and automatic session recovery. It is
under active development and does not yet provide full iOS feature parity.

Development builds have been exercised on a Pixel 6 and Xiaomi Pad 7 with hardware
HEVC decoding. Frame pacing still varies with the host, network, and device.
These observations are not a latency guarantee or a claim of iPhone-equivalent
performance. See [testing](docs/testing.md) for scope and limitations.

## Screenshots

Connection screens captured on a Pixel 6 and Xiaomi Pad 7.
Host names are synthetic demonstration data.

<table>
  <tr><th>Pixel 6</th><th>Xiaomi Pad 7</th></tr>
  <tr>
    <td><img src="docs/images/phone.png" alt="Pixel 6 connection screen with two Mac cards" width="240"></td>
    <td><img src="docs/images/tablet.png" alt="Xiaomi Pad 7 landscape connection screen with square Mac cards" width="560"></td>
  </tr>
</table>

## Requirements

- Android 11 (API 30) or later, with a compatible HEVC decoder.
- A Mac running [Sill](https://getsill.app/). Development testing used Sill for Mac 0.6.0.
- Both devices reachable on the same local network. Discovery requires multicast
  traffic to be permitted on that network.

The Mac needs the permissions requested by Sill, including Screen Recording and
Accessibility. Refer to the upstream documentation for Mac setup.

## Build and install

Use JDK 17, Android SDK Platform 36, and Build Tools 36.0.0. Set `ANDROID_HOME` to
your SDK directory or create an untracked `local.properties` containing `sdk.dir`.
The Gradle wrapper downloads the pinned Gradle distribution on first use.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The application ID is `io.github.jf0510.sill`; its launcher label is **Sill**.
The connection screen identifies it as an unofficial Android client. A locally
built debug APK uses the developer's Android debug signing key. Install updates
using the same signing key to retain application data and pairing.

Signed preview APKs are available from [GitHub Releases](https://github.com/jf0510/sill-android/releases).
Download the `.apk` asset from the desired version and allow installation from
your browser or file manager when Android prompts. There is no Play Store listing.
Release APKs use a stable signing key so later releases can update an existing
installation. A debug build uses a different key; replacing it with a release
APK requires uninstalling the debug build and pairing again. There is no in-app
auto-updater; download subsequent versions from Releases.
`./gradlew :app:assembleRelease` produces an unsigned release APK; signing keys
and passwords must remain outside the repository.

Release signing certificate SHA-256:

```text
b07156eb35bb8f5f5e200a001af573f9f0799fb7ea2e5d763b252caac9f1f491
```

## Connect

1. Start Sill on the Mac and the Android client on the same network.
2. Select the Mac in the connection screen. Enter the 12-digit pairing code shown
   on the Mac. If necessary, open **Pair iPhone or iPad…** in the Mac's Sill menu.
3. After pairing, the client joins the active source or selects Desktop when no
   source is active. Use a window thumbnail or **Desktop** to change the source.

If discovery is unavailable, **Add a Mac** accepts an address and port. Use the
Mac's actual TLS listener. Port 7455 is the upstream remote listener and requires
Remote Access on the Mac; it is not a universal local-discovery port.
Automatic address refresh and a complete remote-connection workflow are not yet
implemented. After a Mac's address changes, discovery and pairing may need to be
repeated.

## Controls

| Surface or control | Behavior |
| --- | --- |
| Picture | Tap to click; hold to right-click; swipe to scroll |
| Picture zoom | Pinch to zoom; two-finger pan while zoomed |
| Portrait trackpad | Slide to move; tap to click; two-finger tap to right-click; two-finger scroll |
| Trackpad drag | Tap, then begin a second touch within 300 ms and move |
| Window thumbnail | Select a window; long-press for window commands |
| Apps | Search open windows and launch installed Mac applications |
| Keyboard | Open the Android software keyboard; use the shortcut row for additional keys |
| Settings | Adjust supported host settings and local scrolling direction |

Settings that affect capture quality, resolution, or frame rate are **shared Mac
stream settings** and may affect other connected clients. If an HEVC picture
exceeds the decoder's size limit, the client can request Standard resolution.
It does not automatically restore the previous Retina setting.

## Known limitations

- Frame delivery can be uneven, including on devices with hardware decoding.
- Trackpad hold-to-drag, modifier handling, and software keyboard composition do
  not yet fully match the iOS client. Hardware keyboard and stylus mappings are
  incomplete and are not advertised as supported.
- Mac menu navigation and settings acknowledgement are preliminary. Cursor
  shapes, clipboard synchronization, audio, QR pairing, three-finger gestures,
  and the first-run tour are not implemented.
- Apple Direct Wireless (AWDL), USB discovery, and USB automatic pairing are not
  implemented. Sharing an SSID does not reproduce the iOS Direct transport path.
- Saved-host address reconciliation, accessibility, large font sizes, and the
  broader range of Android decoders need additional testing.

See [architecture and provenance](docs/architecture.md),
[contributing](CONTRIBUTING.md), and [security](SECURITY.md).

## Privacy

The client discovers devices on the local network and connects directly to the
Mac selected by the user. It has no analytics, advertising SDK, account service,
or relay server. Pairing records and preferences remain in application storage;
the device private key remains in Android Keystore. Android application backup
is disabled. Diagnostic logs may contain host names, addresses, and device
information; redact these before sharing a bug report.

## License and attribution

[Apache License 2.0](LICENSE). Sill was created by **Noah Saffer**.
This Android implementation adapts the upstream protocol and interface behavior
from commit `41e8cb8801f8e9d387e27a0d498dee193d17e9ec`.
The Android launcher icon is independent artwork; the upstream icon is not included.

See [NOTICE](NOTICE) for upstream attribution and modifications, and
[third-party notices](app/src/main/assets/THIRD_PARTY_NOTICES.txt) for dependencies.
These notices are also bundled in the APK and available under Settings →
**About and open-source licenses**. No endorsement or trademark rights are claimed.
