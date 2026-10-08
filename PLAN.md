# TubeBeam — "Share to TV" Android app

> Build plan for Claude Code. Put this file in an empty project folder, then tell Claude Code:
> *"Read PLAN.md and implement it phase by phase. Stop after each phase so I can test."*

---

## 1. What we're building

An Android phone app that appears in the system **Share** sheet. You share a YouTube video from the YouTube app (or Chrome, or anything else), and the app sends it to **SmartTube on your Android TV** over the local network. You never have to open the app.

- **One TV configured:** the share sends immediately and shows a toast ("Sent to Living Room ✓").
- **Two or more TVs:** a small floating picker appears over the current app. Tap a TV and it sends.
- **Settings screen:** add a TV by IP address, connect it, approve it on the TV once ("trusted device"), then rename, test, set a default, or remove it.

## 2. How it works (the key idea)

SmartTube is not a Chromecast receiver, so "casting" doesn't work in the usual sense. The approach that works reliably is the one `adb` uses:

1. The phone app speaks the **ADB protocol directly** over Wi-Fi to the TV's `adbd` (the TV's debugging daemon). It uses an embedded Kotlin ADB client library, with no `adb` binary and no root.
2. It runs one shell command on the TV:
   ```
   input keyevent KEYCODE_WAKEUP
   am start -a android.intent.action.VIEW \
            -d "https://www.youtube.com/watch?v=VIDEO_ID&t=42" \
            -p com.liskovsoft.smarttubetv.beta
   ```
3. SmartTube receives the VIEW intent and plays the video. As a side effect, this also launches SmartTube if it's closed and wakes the TV. That fixes SmartTube's limitation that it can't auto-launch from a cast.

### "Trusted device" = ADB authorization

ADB uses an RSA key pair to identify the phone. The first time the phone connects, the TV shows a prompt: **"Allow network debugging? RSA key fingerprint …"**. The user ticks **"Always allow from this computer"** and presses OK. After that, the TV trusts this phone's key permanently. The app generates one key pair on first launch and reuses it for every TV.

There are two connection modes, and the app must support both:

| Mode | TVs | Port | Trust step |
|---|---|---|---|
| **Legacy ADB over TCP** | Most Android TV boxes, Chromecast with Google TV, Shield, Sony/Philips/TCL Android TVs | `5555` (fixed) | On-screen "Allow debugging?" prompt on the TV |
| **Wireless debugging (TLS)** | Android 11+ TVs that expose "Wireless debugging" in Developer options | Random port (changes after reboot) | One-time pairing with a 6-digit code and pairing port, then mDNS discovery of the connect port |

**Legacy mode is the main path.** It's simpler and works on most TVs. TLS pairing is phase 4.

## 3. One-time setup on each TV (to show in the app's help screen)

1. Settings → Device Preferences → About → click **Build** 7 times. This enables Developer options.
2. Settings → Device Preferences → Developer options → enable **USB debugging** and/or **Network debugging** (the wording varies by vendor).
3. Find the TV's IP: Settings → Network → your Wi-Fi network.
4. **Strongly recommended:** give the TV a fixed IP using a DHCP reservation in your router.
5. Make sure SmartTube is installed.

## 4. Tech stack

| Concern | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 |
| Min / target SDK | minSdk 26, targetSdk = latest stable (35/36). Verify at build time. |
| ADB client | **Kadb** (`com.flyfishxu:kadb`, Kotlin, supports connect, shell, and Android 11+ pairing). Fallback: **libadb-android** (`com.github.MuntashirAkon:libadb-android` via JitPack, which needs Conscrypt for TLS). Pick one in phase 1 and wrap it behind an interface. |
| Persistence | Room (TV list), DataStore (settings) |
| Async | Kotlin coroutines; `Dispatchers.IO` for all network calls |
| DI | None, or a tiny manual `AppContainer`. Hilt is overkill here. |
| Discovery | `NsdManager` for `_adb-tls-connect._tcp` and `_adb-tls-pairing._tcp` (TLS mode) |
| Build | Gradle Kotlin DSL + version catalog (`libs.versions.toml`) |

> ⚠️ Library versions: have Claude Code check the latest Kadb release on Maven Central/GitHub before pinning. If Kadb's pairing API doesn't work on-device, switch the `AdbTransport` implementation to libadb-android. Nothing else should need to change.

## 5. Architecture

```
app/src/main/java/dev/tubebeam/
├── TubeBeamApp.kt                 # Application; builds AppContainer, ensures RSA key exists
├── AppContainer.kt                # manual DI: db, repo, adb, sender
│
├── data/
│   ├── TvDevice.kt                # @Entity
│   ├── TvDao.kt
│   ├── AppDatabase.kt
│   ├── TvRepository.kt            # Flow<List<TvDevice>>, add/update/delete, default handling
│   └── SettingsStore.kt           # DataStore: default TV id, target package override, wake TV on/off
│
├── adb/
│   ├── AdbKeyStore.kt             # generate/load RSA-2048 key pair, stored in app-private files
│   ├── AdbTransport.kt            # interface: connect(host,port), shell(cmd), pair(host,port,code), close
│   ├── KadbTransport.kt           # implementation using Kadb
│   ├── TlsPortResolver.kt         # NsdManager lookup of current wireless-debugging port
│   └── AdbErrors.kt               # sealed class: Unreachable, Unauthorized, Timeout, Refused, Unknown
│
├── youtube/
│   ├── YouTubeLinkParser.kt       # extract videoId, start time, playlist from shared text
│   └── TargetApp.kt               # known package names + detection via `pm list packages`
│
├── send/
│   └── VideoSender.kt             # orchestrates: resolve port → connect → wake → am start → close
│
├── ui/
│   ├── MainActivity.kt            # settings / TV list
│   ├── screens/TvListScreen.kt
│   ├── screens/AddTvScreen.kt     # IP, name, mode; "Connect" button drives trust flow
│   ├── screens/TvDetailScreen.kt  # rename, test, wake, set default, re-pair, delete
│   ├── screens/HelpScreen.kt      # TV setup steps from section 3
│   └── theme/
│
└── share/
    ├── ShareReceiverActivity.kt   # translucent, dialog-themed; receives ACTION_SEND
    └── TvPickerSheet.kt           # Compose picker shown when >1 TV
```

### Data model

```kotlin
@Entity
data class TvDevice(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,                 // "Living Room"
    val host: String,                 // "192.168.1.50"
    val port: Int = 5555,             // legacy; for TLS this is last known connect port
    val mode: ConnectionMode,         // LEGACY_TCP or TLS
    val targetPackage: String?,       // detected SmartTube package; null = auto-detect at send time
    val isDefault: Boolean = false,
    val lastSeenOk: Long? = null,     // epoch ms of last successful send
    val sortOrder: Int = 0,
)
enum class ConnectionMode { LEGACY_TCP, TLS }
```

## 6. Core logic, in detail

### 6.1 Parsing the shared text — `YouTubeLinkParser`

The YouTube app shares text like `https://youtu.be/dQw4w9WgXcQ?si=abc123`. Chrome shares the page URL, sometimes with a title before it. The parser must:

- Find the first URL in `EXTRA_TEXT` with a regex.
- Accept these formats:
  - `youtu.be/{id}`
  - `youtube.com/watch?v={id}`
  - `m.youtube.com/watch?v=`
  - `music.youtube.com/watch?v=`
  - `youtube.com/shorts/{id}`
  - `youtube.com/live/{id}`
  - `youtube.com/embed/{id}`
- Validate the ID with `^[A-Za-z0-9_-]{11}$`.
- Keep `t` / `start` (handle `t=90`, `t=1m30s`, `t=90s`). Normalize to seconds.
- Keep `list=` if present (playlists).
- Drop tracking params (`si`, `pp`, `feature`).
- Output a canonical URL: `https://www.youtube.com/watch?v=ID[&t=N][&list=L]`.
- Return `null` for non-YouTube content. The share activity then shows "Not a YouTube link" and closes.

Write unit tests for every format above. This is pure Kotlin and easy to test.

### 6.2 Target app detection — `TargetApp`

Ordered preference list:
1. `com.liskovsoft.smarttubetv.beta`
2. `com.liskovsoft.smarttubetv`
3. `com.google.android.youtube.tv` (fallback to the official app)

At pairing time, run `pm list packages | grep -E 'smarttube|youtube.tv'` and store the best match in `TvDevice.targetPackage`. Let the user override it in TV details.

### 6.3 Sending — `VideoSender.send(tv, url)`

```
1. port = if TLS: TlsPortResolver.resolve(tv.host) ?: tv.port  else tv.port
2. transport.connect(tv.host, port, key, timeout = 4s)
3. if settings.wakeTv: shell("input keyevent KEYCODE_WAKEUP")
4. shell("am start -a android.intent.action.VIEW -d '<url>' -p <pkg>")
     - single-quote the URL; it contains '&'
     - if output contains "Error" / "unable to resolve": retry without -p (let the TV choose)
5. close; update tv.lastSeenOk
6. Return Result.Success or a typed AdbError
```

Constraints:
- Target time: under 1.5 s on a healthy LAN. Open a fresh connection per send; don't keep sockets alive.
- Timeouts: connect 4 s, shell 5 s.
- Map failures to friendly messages:
  - Unreachable → "Can't reach Living Room. Is it on and on the same Wi-Fi?"
  - Unauthorized → "Living Room hasn't trusted this phone. Open the app to reconnect."
  - Refused on 5555 → "Network debugging seems off on the TV."

### 6.4 The trust/connect flow — `AddTvScreen`

1. The user enters a name and IP. Validate the IPv4 format and offer the option to edit the port.
2. The user taps **Connect**.
3. Run a TCP reachability check (socket connect to host:5555, 2 s).
   - **Open:** start the ADB connect with a long timeout (60 s). Show a full-screen instruction: "Look at your TV. Tick *Always allow from this computer* and press OK." Include a spinner and a Cancel button.
   - **Closed:** offer **"Use Wireless debugging (Android 11+)"**, which goes to the TLS pairing flow (6.5).
4. On success, run `pm list packages` to detect SmartTube, save the TV, and offer **"Send a test video"**.
5. On timeout or rejection, explain and offer to retry.

Show the phone's ADB key fingerprint on screen so the user can match it to the TV prompt. ADB displays the MD5 of the public key.

### 6.5 TLS pairing (phase 4)

1. Instruct the user: on the TV, open Developer options → Wireless debugging → **Pair device with pairing code**. The TV shows an IP:port and a 6-digit code.
2. The user enters the pairing port and code. The app calls `transport.pair(host, pairPort, code)`.
3. Discover the connect port via `NsdManager` (`_adb-tls-connect._tcp`) filtered by host. Fall back to asking the user to type it.
4. Connect, then follow the same detection and save steps as 6.4.
5. At send time, if the stored port fails, re-resolve via mDNS once before giving up. The port changes after a TV reboot.

Hold a `WifiManager.MulticastLock` during mDNS discovery.

### 6.6 Share entry point — `ShareReceiverActivity`

Manifest:
```xml
<activity
    android:name=".share.ShareReceiverActivity"
    android:exported="true"
    android:theme="@style/Theme.TubeBeam.Translucent"
    android:excludeFromRecents="true"
    android:taskAffinity=""
    android:launchMode="singleTask"
    android:label="Send to TV">
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/plain" />
    </intent-filter>
</activity>
```

Theme: translucent window, no title, `windowIsFloating=false`, dim background. Compose draws a bottom sheet or card.

Behaviour:
- Parse the link. If it's invalid, show a toast and `finish()`.
- Count the TVs:
  - **0 TVs:** show a toast "Add a TV first" and open `MainActivity`.
  - **1 TV,** or a default is set and "always use default" is on: send immediately. Show a compact "Sending to Living Room…" card, then ✓ or an error. Auto-finish after about 1 s on success. On error, stay open with a **Retry** button.
  - **2+ TVs:** show `TvPickerSheet`, a floating card listing TVs with a status dot (last-seen ok/unknown). It includes a "Remember my choice" checkbox (sets the default), and tapping outside cancels.
- Run the send in `lifecycleScope`. The activity stays alive for the ~1 s the send takes, so no service or WorkManager is needed.
- "Floating window" is achieved with a translucent activity. **Do not** use `SYSTEM_ALERT_WINDOW`; it isn't needed and the permission UX is bad.

Optional extra: add a **Direct Share** target per TV (`ShortcutManagerCompat` with share-target shortcuts). Each TV then appears as its own icon at the top of the share sheet, and the picker can be skipped entirely.

### 6.7 RSA key — `AdbKeyStore`

- Generate RSA-2048 on first launch with the library's helper (Kadb and libadb both provide one) or `KeyPairGenerator`.
- Store the PKCS#8 private key and public key in `filesDir/adb/`, which is app-private.
- Optionally encrypt the file with an AES key from Android Keystore (`androidx.security`-style). This is nice to have; phase 5.
- Set ADB's key name (shown on the TV) to `TubeBeam@<phone model>`.
- Provide a "Reset key" action in settings. Warn that every TV will need to re-trust the phone.

## 7. Manifest permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />  <!-- mDNS, TLS mode -->
```

> ⚠️ Newer Android versions are adding a **local network access** permission for apps that talk to LAN devices. Have Claude Code check the current requirements for the chosen `targetSdk`. If one is required, request it at runtime on the Add-TV screen with an explanation.

## 8. Build phases (give these to Claude Code one at a time)

**Phase 0 — Environment (Linux)**
- JDK 17 (`sudo apt install openjdk-17-jdk`).
- Android command-line tools: `sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"`.
- Set `ANDROID_HOME`, accept licenses (`yes | sdkmanager --licenses`).
- Phone: enable USB debugging, connect, `adb devices`.
- Create the project skeleton (Compose, Kotlin DSL, version catalog). `./gradlew assembleDebug` must pass.

**Phase 1 — ADB proof of concept**
- `AdbKeyStore`, `AdbTransport` + `KadbTransport`.
- A debug screen with IP field, Connect, and "Run shell" text box.
- ✅ Done when you can approve the phone on the TV and see the output of `getprop ro.product.model`.

**Phase 2 — Sending**
- `YouTubeLinkParser` + unit tests, `TargetApp`, `VideoSender`.
- A debug button that sends a hard-coded video.
- ✅ Done when the video plays in SmartTube, including when SmartTube was closed and the TV was asleep.

**Phase 3 — Real app**
- Room, repository, `MainActivity` with list, add, detail, and help screens; full trust flow (6.4).
- `ShareReceiverActivity` with single-TV and multi-TV picker behaviour.
- ✅ Done when sharing from the YouTube app works end-to-end with 1 TV and with 2 TVs.

**Phase 4 — Wireless debugging (TLS)**
- Pairing flow, `TlsPortResolver`, port re-resolution on failure.
- ✅ Done when a TLS-only TV still works after a reboot.

**Phase 5 — Polish**
- Direct Share shortcuts per TV.
- Encrypted key storage.
- Error copy, haptics, app icon.
- Optional extra **Quick Settings tile** "Send clipboard link to TV".
- Optional: share a timestamp-less vs. timestamped link toggle.
- `./gradlew lint test` clean; release build with R8 (add keep rules for the ADB library if needed).

## 9. Testing checklist

- Unit tests: link parser (all URL forms, timestamps, garbage input), error mapping.
- On device:
  - Share from the YouTube app, Chrome (youtube.com), YouTube Music, and Shorts.
  - TV asleep → wakes and plays.
  - SmartTube closed → launches and plays.
  - TV off or unplugged → clear error within ~4 s, not a hang.
  - Trust revoked on TV (Developer options → Revoke USB debugging authorizations) → "Unauthorized" message and a working reconnect.
  - Phone on mobile data / different Wi-Fi → "can't reach" message.
  - 2 TVs, picker, "remember choice" → next share goes straight to the default.
  - Rotation and dark mode in the picker.

## 10. Known limitations and caveats

- **Security:** enabling network ADB on the TV means anyone on your LAN with a *trusted* key can control it. Untrusted devices still get the prompt, so the risk is modest on a home network. Don't enable it on shared or public networks. libadb-android explicitly says it hasn't been security-audited; treat Kadb the same way.
- Some TVs **turn off network debugging after a reboot** or firmware update. The error message should hint at this.
- Some vendors (certain Sony/TCL firmwares) don't show the "Allow" prompt on the TV for network connections until you've connected via USB once. Document this in the help screen.
- The phone and TV must be on the same L2 network. Guest Wi-Fi and AP/client isolation will block it.
- This uses SmartTube's ability to open `youtube.com` VIEW intents. If a future SmartTube version changes its intent filters, the `-p` retry-without-package fallback still lets the TV pick a handler.

## 11. Suggested `CLAUDE.md` for the repo

```markdown
# TubeBeam
Android app (Kotlin, Compose) that receives YouTube share intents and sends them to
Android TVs running SmartTube via an embedded ADB client. Full spec: PLAN.md.

## Commands
- Build: ./gradlew assembleDebug
- Install: ./gradlew installDebug
- Tests: ./gradlew test
- Lint: ./gradlew lint

## Rules
- All ADB/network calls on Dispatchers.IO with explicit timeouts.
- Keep the ADB library behind the AdbTransport interface.
- Never log the full RSA private key or pairing codes.
- Implement PLAN.md phases in order; stop for manual testing after each phase.
```
