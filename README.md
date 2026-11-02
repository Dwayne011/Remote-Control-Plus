# Remote Control Plus

An Android remote for the TVs in your house, in one app. Switch between them from the
title bar; the same control layout works on every one.

| Device | Protocol | Pairing | What works |
|---|---|---|---|
| Samsung TV (Tizen, 2016+) | WebSocket on port 8002 | Allow prompt on the TV | Everything, plus keyboard; Wake-on-LAN for power on |
| LG TV (webOS) | SSAP WebSocket on port 3001 | Yes prompt on the TV | Everything, plus keyboard; Wake-on-LAN for power on |
| Roku | ECP over HTTP on port 8060 | None | Everything, plus keyboard |
| Apple TV (4K / HD) | Companion Link | 4-digit code | D-pad, Menu/Back, Home, play/pause, volume (HDMI-CEC), sleep/wake, keyboard. No mute. |
| Chromecast with Google TV / Google TV | Android TV Remote v2 | 6-character code | Everything, plus keyboard |
| Classic Chromecast | Cast v2 | None | Volume, mute, play/pause of what is casting, stop casting |

Design follows [Material Design 3](https://m3.material.io/): dynamic colour from the wallpaper on
Android 12+, with a fixed seed palette as fallback, light and dark.

## Requirements

- Android 12 (API 31) or newer.
- Phone and TVs on the same Wi-Fi network.
- To build: JDK 17 or 21 and the Android SDK (platform 36). Android Studio sets these up.

## Build

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Unit tests, including the protocol test vectors for Apple TV and Android TV:

```bash
./gradlew test
```

The GitHub Actions workflow in `.github/workflows/android.yml` runs the tests and uploads a
debug APK on every push.

## Layout

The remote follows the Google Home TV remote: a large touch pad (Swipe mode flicks to move and
taps to select; D-pad mode has buttons), Back and Home, On and Off, previous / play / pause / next,
mute with volume down and up, and a Keyboard row.
or plus and minus on those that do not (Samsung, Roku), and a Keyboard row. Devices only show
the controls they support. On Apple TV a swipe is sent as a swipe on the Siri Remote trackpad.
The connection is picked up again automatically when the app returns to the foreground.

## Quick Settings tile

Add the **Remote** tile from the device switcher ("Add to Quick Settings") or from the
notification shade's Edit tiles screen. It shows the selected TV; tapping it opens a full-screen remote over whatever you are doing (close, device chip with a
switcher, power, the pad, Back/Home,
power, volume, play/pause) with a button to the full app.

## Using it

1. Open the app and tap **Find TVs**. It scans with mDNS (Apple TV, Google TV, Chromecast) and
   SSDP (Samsung, LG, Roku) at the same time.
2. Tap a TV to add it. Samsung and LG show an allow prompt on the TV; Apple TV and Google TV
   show a code to type into the app; Roku and Chromecast need nothing.
3. Switch TVs from the dropdown in the title bar. Long-lived pairings are stored encrypted
   with a key in the Android Keystore.
4. The **Keyboard** button sends text to whatever field the TV has focused.

## Project layout

| Module | Contents |
|---|---|
| `app/` | The Android app: Compose UI, device controllers, discovery, storage |
| `protocol-appletv/` | Apple TV Companion Link, vendored from [msthind04/AppleTV-Remote](https://github.com/msthind04/AppleTV-Remote) (Apache-2.0) |
| `protocol-androidtv/` | Android TV Remote v2, vendored from [amrakshay/android-tv-remote](https://github.com/amrakshay/android-tv-remote) (MIT) |
| `protocol-cast/` | A small Cast v2 client written for this app |

Samsung, LG and Roku are implemented directly in `app/src/main/kotlin/com/remotecontrolplus/devices/`
with the Python libraries samsung-tv-ws-api, PyWebOSTV and the Roku ECP docs as references.

Each vendored module keeps its upstream licence and a `NOTICE.md` listing local changes.

## Diagnostics

The bug icon in the title bar opens a log of everything the app did: discovery results from
mDNS, SSDP and the subnet probe, every connection and pairing step, every command sent and any
error with its stack trace. **Share** sends it as text. Tokens and keys are redacted.

## Known limitations

- Apple TV and Google TV protocol code is tested against upstream byte-level vectors, but the
  live connection has only been verified by the upstream authors on their own devices.
- Apple TV volume only works when the Apple TV routes volume over HDMI-CEC.
- Classic Chromecast dongles have no on-screen UI to navigate, so only media controls are shown.
- Devices on another subnet or with discovery blocked will not be found. There is no manual IP
  entry yet.
