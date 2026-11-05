# protocol-appletv

This module is vendored from [msthind04/AppleTV-Remote](https://github.com/msthind04/AppleTV-Remote)
(`protocol/` module), licensed under the Apache License 2.0. The original LICENSE and
THIRD_PARTY_NOTICES.md are reproduced in this directory. The protocol knowledge and the test
vectors derive from [pyatv](https://github.com/postlund/pyatv) (MIT).

Local changes relative to upstream:
- `build.gradle.kts` rewritten to use this repository's version catalog and a JDK 17 toolchain.
- Package names and source files are otherwise unchanged, so upstream fixes can be re-applied.
- `Log.kt`: added a `sink` hook so the app can route wire logging into its diagnostics screen.
- `mrp/MrpVoice.kt` (new), `Protobuf.Builder.double`, and voice methods on `Ap2Session`:
  experimental Siri voice input over the AirPlay-tunnelled MRP channel, using the message
  definitions from pyatv's protobuf files. Untested against real hardware upstream.
