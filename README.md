# TubeBeam

[![CI](https://github.com/jagjordi/tubebeam/actions/workflows/ci.yml/badge.svg)](https://github.com/jagjordi/tubebeam/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/jagjordi/tubebeam?include_prereleases)](https://github.com/jagjordi/tubebeam/releases/latest)

Share a YouTube link from your phone and it plays in **[SmartTube](https://github.com/yuliskov/SmartTube)** on your Android TV. No casting, no remote-control pairing, and the TV wakes up and launches SmartTube by itself.

TubeBeam is an Android phone app that appears in the system **Share** sheet as "Send to TV". Share a video from the YouTube app, Chrome, YouTube Music, Shorts, or anywhere else, and it opens on the TV. With one TV it just sends; with several, a small floating picker lets you choose.

## How it works

SmartTube is not a Chromecast receiver, so TubeBeam uses the same channel developer tools use: it speaks the **ADB protocol directly** to the TV over Wi-Fi (via the embedded [Kadb](https://github.com/flyfishxu/Kadb) client, no `adb` binary, no root) and runs one shell command on the TV:

```
input keyevent KEYCODE_WAKEUP
am start -a android.intent.action.VIEW -d 'https://www.youtube.com/watch?v=VIDEO_ID&t=42' -p org.smarttube.stable
```

SmartTube receives the VIEW intent and plays the video. As a side effect this also wakes the TV and launches SmartTube if it was closed, which plain casting cannot do.

The phone identifies itself with an RSA key. The first time you connect, the TV shows its usual **"Allow USB debugging?"** prompt with the key fingerprint; tick *Always allow from this computer* once and the TV trusts the phone from then on. The app shows the same fingerprint so you can compare.

## Install

Download the APK from the [latest release](https://github.com/jagjordi/tubebeam/releases/latest) on your phone and open it. Android 8.0 or newer.

## TV setup (once per TV)

1. Settings → Device Preferences → About → select **Build** seven times to enable Developer options.
2. Settings → Device Preferences → Developer options → enable **USB debugging** and, if present, **Network debugging** (wording varies by vendor).
3. Note the TV's IP address (Settings → Network). A DHCP reservation in your router is recommended, but not required: TubeBeam records the TV's serial and, if the address stops answering, finds the TV again via mDNS or a quick subnet scan and updates it.
4. Have SmartTube installed.

Then in TubeBeam: **Add TV**, enter a name and the IP, tap **Connect**, and accept the prompt on the TV.

Tested with a Xiaomi Mi Box and a Philips Android TV. Any Android TV, TV box, Chromecast with Google TV or Shield that exposes ADB on port 5555 should work.

## Status

- [x] ADB over TCP (port 5555) with on-TV trust prompt
- [x] Link parsing for youtu.be, watch, Shorts, live, embed, music.youtube.com, timestamps and playlists
- [x] Share-sheet target, single-TV instant send, multi-TV picker with "remember my choice"
- [x] TV list, add/rename/remove, default TV, test and wake actions
- [x] Reachability dots in the picker; TVs whose IP changed are found again by serial (mDNS first, subnet sweep as fallback), automatically on send or via "Find on network"
- [ ] Android 11+ **Wireless debugging** (TLS pairing with code, mDNS port discovery)
- [ ] Direct Share shortcuts per TV, app icon

Detected player packages, in order of preference: `com.liskovsoft.smarttubetv.beta`, `org.smarttube.stable`, `com.teamsmart.videomanager.tv`, `com.liskovsoft.smarttubetv`, and the official `com.google.android.youtube.tv` as a fallback.

## Building

Everything (JDK, Android SDK, Gradle) runs in a Docker container; nothing needs to be installed on the host besides Docker. CI builds every push; pushing a `v*` tag publishes a signed release APK (see `.github/workflows/release.yml`, signing via repository secrets, local setup in `.env.release.example`).

```
docker compose build          # once
./gw assembleDebug            # APK in app/build/outputs/apk/debug/
./gw test                     # unit tests
./gw installDebug             # install on a phone attached to the host
```

`./gw` runs the Gradle wrapper inside the container. Kotlin, Jetpack Compose, Material 3, Room, DataStore, Kadb. Min SDK 26.

## Limitations and security

- Enabling network ADB on a TV means anyone on your LAN holding a *trusted* key can control it; untrusted devices still get the prompt. Don't enable it on shared or public networks.
- Some TVs turn network debugging off after a reboot or firmware update.
- Some Sony/TCL firmwares only show the trust prompt after one USB debugging session.
- Phone and TV must be on the same network; guest Wi-Fi and client isolation block it.
- Kadb, like all third-party ADB implementations, has not been security-audited.

## Credits

[SmartTube](https://github.com/yuliskov/SmartTube) by yuliskov and contributors, and [Kadb](https://github.com/flyfishxu/Kadb) by flyfishxu.

## License

[MIT](LICENSE)
