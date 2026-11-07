# Contributing

Bug reports and focused contributions are welcome. Please open an issue before
starting a substantial feature or changing the wire protocol. This repository
maintains the Android client; report Android-specific issues here rather than
in the upstream Sill repository.

## Development

Use JDK 17 and Android SDK Platform 36. Python 3 is used by the formatting helper.

```sh
python3 tools/format.py
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

The formatter downloads ktfmt 0.59 from Maven Central to `.tools/` and verifies
its pinned SHA-256 checksum before execution. CI runs the same formatter in
check-only mode. Do not commit SDK paths, build outputs, signing keys, pairing
records, or unredacted device logs and screenshots.

## Pull requests

- Describe the problem, resulting behavior, and relevant validation.
- Keep changes scoped. Separate behavior changes from unrelated refactoring.
- Add regression tests for protocol, recovery, or state-machine changes.
- Identify any upstream source adapted into the change and retain its notices.
- Distinguish device-tested behavior from emulator results and source inspection.
- Use concise, descriptive commit subjects, for example:
  `fix: retain queued video frames during decoder backpressure`.

Performance reports should state the device and Android version, Mac host
version, transport, source resolution, quality preset, frame-rate limit, workload,
and measurement method. Decode throughput, presentation timing, and end-to-end
latency are different measurements and should not be used interchangeably.

Contributions are provided under the Apache License 2.0, consistent with this
repository. See [SECURITY.md](SECURITY.md) for vulnerability reporting.

## Releases

Release publication is triggered by a `vMAJOR.MINOR.PATCH` tag. Update
`versionName` and increment `versionCode` in `app/build.gradle.kts`, add matching
notes in `docs/releases/MAJOR.MINOR.PATCH.md`, and complete the checks above before
pushing the tag. The workflow verifies that the APK version matches the tag,
signs the APK, and publishes it with a SHA-256 checksum. Versions beginning with
`0.` are marked as prereleases. Ordinary commits do not publish a release.
If infrastructure fails before publication, the Release APK workflow can be
started manually from `main` with the existing tag; it checks out that tag
and never replaces an existing release.

Repository maintainers configure `RELEASE_KEYSTORE_BASE64` and
`RELEASE_STORE_PASSWORD` as GitHub Actions secrets. The PKCS12 keystore uses alias
`sill-release` and the same store/key password. Preserve the signing key in a
secure backup: replacing it prevents updates to existing installations. Never
commit the keystore or passwords, and never expose signing secrets to PR jobs.
