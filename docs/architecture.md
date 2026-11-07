# Architecture and provenance

The Android client uses Sill's existing generation-1 wire protocol without
modifying the Mac host. The implementation is based on upstream commit
[`41e8cb8`](https://github.com/Saffsanity/sill/tree/41e8cb8801f8e9d387e27a0d498dee193d17e9ec).

## Source mapping

Android source files are under `app/src/main/java/io/github/jf0510/sill/`.
This table identifies the upstream behavior used as a reference; it does not
assert complete feature parity.

| Android module | Responsibility | Upstream reference |
| --- | --- | --- |
| `Wire.kt` | Binary framing, control JSON, pairing proofs, HEVC parameter sets | `Sources/StreamProtocol` |
| `Identity.kt` | Android Keystore identity, TLS 1.3, SPKI pinning | `RemoteTLS` and pairing protocol |
| `SillClient.kt` | Discovery, connection lifecycle, stream metadata, serialized writes | `iOSClient/StreamClient.swift` and extensions |
| `SessionPolicy.kt` | Source selection, closed-window recovery, reconnect policy | `StreamClient.swift`, `GoodbyePolicy.swift` |
| `RealtimePolicy.kt` | Move coalescing and keyframe request timing | `StreamClient.sendInput`, `KeyframeRecovery.swift` |
| `VideoDecoder.kt` | MediaCodec hardware decoding and bounded input queue | `HEVCDisplayView.swift` behavior; Android-specific implementation |
| `ResolutionFallback.kt` | One-shot Standard-resolution fallback | Android decoder-size handling |
| `TouchSurface.kt` | SurfaceView, touch mapping, trackpad, zoom, IME | `InputOverlayView`, `TrackpadView` |
| `PairingCodeInput.kt` | Four-digit code grouping and cursor mapping | `AddMacCard.swift` / `PairingField.grouped` |
| `MainActivity.kt` | Compose layouts, connection screen, stream controls | `StreamScreen`, `PhonePortraitLayout`, portrait layouts |

The Swift behaviors were translated and adapted to Kotlin, Android lifecycle
callbacks, Compose, MediaCodec, and Android Keystore. Source headers and NOTICE
identify this adaptation. The launcher icon modifies the upstream artwork with an Android robot; see
[branding](branding.md) for the separate artwork attributions.
No source from the independently maintained Omar/Amine or Rahim Android clients
was imported into this initial snapshot.

The connection page follows upstream `ConnectLayout`: a centered column at most
380 dp wide, with 10 dp grid spacing and a 92 dp preview band. Tablet cards do not
stretch to fill the screen or enforce a square aspect ratio.

The session hello advertises upstream compatibility version `0.6.0` for the
Mac's device-version gate; its `build` field carries this Android client's own
version. The two release numbering schemes are independent.

## Threads and data flow

The session reads framed TLS messages on an IO coroutine. Writes use a serial
executor. Connection generations reject results belonging to replaced sessions;
source and recovery timers run on the main thread.

Video frames are passed to a dedicated display-priority HandlerThread. All
MediaCodec calls run there. Input-buffer acquisition is nonblocking; temporary
backpressure retains a compressed frame instead of breaking the HEVC reference
chain. The compressed backlog is limited to 32 frames. Queue overflow or codec
failure opens keyframe recovery. Output is released directly to a SurfaceView.

Only absolute pointer moves are coalesced, at an 8 ms interval. Other inputs
flush a pending move before sending their own event. Recovery requests wait at
least four seconds for an answer, extended using measured RTT, and back off to
avoid repeatedly requesting keyframes from the host.

## Deliberate differences and incomplete behavior

- MediaCodec and SurfaceView replace AVSampleBufferDisplayLayer; callback timing
  and decoder capabilities vary across Android vendors.
- The Android transport is LAN TLS/TCP. AWDL and Apple USB pairing are absent.
- Unsupported Retina-sized pictures can trigger a shared host resolution change.
- Gesture details, keyboard composition, settings acknowledgement, menu merging,
  cursor shapes, and discovery address refresh still need parity work.
- Source recovery uses fixed grace periods; it does not yet reproduce all
  upstream RTT-dependent timing behavior.

These differences should remain explicit in bug reports and release notes.
