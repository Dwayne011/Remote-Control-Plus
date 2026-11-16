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

## Look

The app uses the frosted style shared with At-a-Glance-Plus: a near-black base, soft blurred pools
of the wallpaper's Material You colours, and translucent glass tiles with a thin light edge. It is
always dark; accents follow the wallpaper. Sheets, dialogs and the Quick Settings pop-up use opaque
dark surfaces so nothing shows through them.

## Layout

The remote follows the Google Home TV remote: a large touch pad (Swipe mode flicks to move and
taps to select; D-pad mode has buttons), Back and Home, On and Off, previous / play / pause / next,
mute with volume down and up, and a Keyboard row.
or plus and minus on those that do not (Samsung, Roku), and a Keyboard row. Devices only show
the controls they support. A swipe is sent as one arrow press on every device, including Apple TV, so each swipe moves once.
On the swipe pad the dot follows your finger like a joystick: push it past a short distance to move once,
keep it there to keep moving, and it springs back when you let go.
Holding the centre button, or holding still on the swipe pad, sends a one-second held OK (Apple TV,
Google TV, Samsung and Roku; other devices get a normal press).
The connection is picked up again automatically when the app returns to the foreground.

## Lock-screen remote

When you use the remote and the TV is on, a notification with the remote's own buttons appears on
the lock screen and in the shade, and the buttons work without unlocking (the lock screen must be
set to show notification content).

- **Expanded:** a rail with switch-device, Volume up, Mute and Volume down; a rail with Power, Back,
  Home and Play-pause; and a large D-pad with OK on the right. The device name is in the header.
- **Collapsed:** Power, Play/pause, Volume down, Volume up.

Every button is a stateless trigger: nothing on the notification shows volume or mute state, and it
is only re-posted when the device changes. Presses go through one serial queue, so rapid volume
taps are sent in order. Power sends Off when the TV is known to be on and On otherwise; play/pause
alternates, since the TV does not report playback. The notification stays while the TV is on and
removes itself when it turns off (Apple TV, Google TV and Roku report this; on Samsung and LG a
lost connection counts as off; a plain Chromecast cannot tell). While it is showing, the phone's
volume buttons control the TV. Colours come from the system's wallpaper palette in light and dark.

## Quick Settings tile

Add the **Remote** tile from the notification shade's Edit tiles screen.
It shows the selected TV; tapping it closes the shade and opens a full-width pop-up remote (close, device chip with a
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

## Voice

Hold the microphone button to talk. On Google TV the phone microphone is streamed to the
assistant (16-bit mono 8 kHz PCM over the remote protocol's voice channel, as androidtvremote2
does); the first press asks for microphone access. On Apple TV the button holds Siri like the
Siri Remote. **Experimental:** the phone microphone is also streamed to Siri over Companion
Link, the way the iPhone's Control Center remote does it (`_siriStart`, `_siA` events of 16 kHz Opus
SILK wideband frames, matching the captured TOC byte 0x48, `_siriStop`; message layout from captures in pyatv issues #2325 and #2875). Publicly
this has opened Siri but not had speech recognised from a non-Apple client; the log records each step.

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
