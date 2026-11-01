# SmartTube queue support

TubeBeam's **Queue on TV** share target sends the usual VIEW intent with one extra:

```
am start -a android.intent.action.VIEW -d 'https://www.youtube.com/watch?v=VIDEO_ID' --ez add_to_queue true -p org.smarttube.stable
```

Stock SmartTube ignores the extra and plays the video immediately. `add-to-queue-intent.patch` teaches
SmartTube to append the video to its **Playback queue** instead when the player is open (playing or
paused); when nothing is playing the video starts as usual. Title and channel are fetched in the
background so the queue dialog (video menu → *Playback queue*) shows them.

The patch is small (two files in `common/`) and has been submitted upstream; until it is merged you
need a SmartTube built from the patched source.

## Build

```
smarttube/build.sh            # clones SmartTube, applies the patch, builds in Docker (JDK 17, SDK 34)
```

Output: `smarttube/SmartTube/smarttubetv/build/outputs/apk/ststable/debug/SmartTube_stable_<version>_<abi>.apk`
(`armeabi-v7a` fits nearly every TV box; `universal` works everywhere).

## Install on the TV

The APK is signed with a debug key, so it cannot update the official SmartTube in place. Back up
SmartTube's settings first (SmartTube → Settings → Backup & restore), then:

```
adb connect <tv-ip>
adb uninstall org.smarttube.stable
adb install SmartTube_stable_<version>_armeabi-v7a.apk
```

Restore the backup from the same menu. TubeBeam keeps working as before; "Send to TV" plays now,
"Queue on TV" appends.
