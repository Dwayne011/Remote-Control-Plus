# protocol-androidtv

This module is vendored from [amrakshay/android-tv-remote](https://github.com/amrakshay/android-tv-remote)
(`protocol/` module), licensed under the MIT License (reproduced in LICENSE).

Local changes relative to upstream:
- The ADB transport (`AdbRemote.kt`, `adb/`), the desktop CLI (`Cli.kt`) and their tests are
  not included; this app only uses the TV's own remote service on ports 6466/6467.
- `build.gradle.kts` rewritten to use this repository's version catalog and a JDK 17 toolchain.
- Package names and the remaining source files are unchanged.
