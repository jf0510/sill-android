# Architecture and provenance

The Android client uses Sill's existing generation-1 wire protocol without
modifying the Mac host. The initial implementation is based on upstream commit
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

## 0.2.0 parity update

The additional protocol and behavior reference is upstream
[`bf69067`](https://github.com/Saffsanity/sill/tree/bf69067fe60348bfcf3d617086a8589fbdf8dbb6)
(main/0.6.2 development, including released 0.6.1 behavior).

- `ParityPolicy.kt`: idle hold, lock/view capabilities, clipboard byte limits,
  bounded audio packet parsing and AudioToolbox ES descriptor unwrapping.
- `AudioOutput.kt`: bounded MediaCodec AAC-ELD worker, epoch/sequence recovery,
  priming trim, AudioTrack and audio focus. Unsupported/failed decoding is reported.
- `RemoteInfo.kt`, `PairLink.kt`: signed host addresses bound to the saved SPKI,
  replay timestamp, discovery recognition tags, QR/link identity and mutual proof.
- `HardwareInput.kt`, `TouchSurface.kt`: HID mapping and release, mouse/wheel,
  stylus drag/hover, chord gestures, cursor images and local IME composition.
- `SillClient.kt`: clipboard subscription/receipt-before-paste, display stepping,
  off-console viewing, settings acknowledgements and menu response merging.
- `MainActivity.kt`: idle hold, PiP/full-screen lifecycle, tour, phone picture size,
  QR scanner and device controls. The camera is used by ZXing only on request.

Capability negotiation is additive: no kind 47 or gesture is sent to a host that
has not advertised it; clipboard is subscribed only when advertised. The hello
advertises offConsoleView generation 1 and AAC-ELD only if the device supports it.
The compatibility version remains 0.6.0; it is not the Android release number.

## Threads and data flow

The session reads framed TLS messages on an IO coroutine. Writes use a serial
executor. Connection generations reject results belonging to replaced sessions;
source and recovery timers run on the main thread.

Video frames are passed to a dedicated display-priority HandlerThread. All
MediaCodec calls run there. Input-buffer acquisition is nonblocking; temporary
backpressure retains a compressed frame instead of breaking the HEVC reference
chain. The compressed backlog is limited to 32 frames. Queue overflow or codec
failure opens keyframe recovery. After a decoder flush, HEVC parameter sets are
queued with `BUFFER_FLAG_CODEC_CONFIG` before subsequent video frames. This
restores initialization data that Android can discard during an early flush.
Output is released directly to a SurfaceView.

Only absolute pointer moves are coalesced, at an 8 ms interval. Other inputs
flush a pending move before sending their own event. Recovery requests wait at
least four seconds for an answer, extended using measured RTT, and back off to
avoid repeatedly requesting keyframes from the host.

## Deliberate differences and incomplete behavior

- MediaCodec and SurfaceView replace AVSampleBufferDisplayLayer; callback timing
  and decoder capabilities vary across Android vendors.
- The Android transport is LAN TLS/TCP. AWDL and Apple USB pairing are absent.
- Unsupported Retina-sized pictures can trigger a shared host resolution change.
- Audio/video have separate clocks; decoder capability and PiP behavior vary by device.
- Stylus pressure/tilt and platform-specific foldable geometry are not reproduced.
- Source recovery extends its fixed minimum waits using measured RTT; Android
  discovery and activity lifecycle remain platform-specific.

These differences should remain explicit in bug reports and release notes.
