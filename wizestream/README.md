# WizeStream: "Send to TV" in the long-press menu and the Cast button

[WizeStream](https://github.com/wizdom13/WizeStream) is a NewPipe fork. `send-to-tubebeam.patch`
adds two entries to the menu that opens when you long-press a video, and the same two at the top
of the "Cast to TV" picker behind the Cast button on the video page:

- **Send to TV** hands the link to TubeBeam, which plays it on the TV.
- **Queue on TV** does the same with the add-to-queue flag (needs SmartTube with queue support, see
  [`../smarttube/`](../smarttube/README.md)).

The entries only appear for YouTube streams and only when TubeBeam is installed. With one TV (or a
remembered default) nothing else is asked; with several TVs, TubeBeam's picker pops up. Sending
from the Cast button pauses local playback, like casting does.

## Build

```
wizestream/build.sh       # clones WizeStream, applies the patch, builds a debug APK in Docker
```

The debug build installs as a separate app (`org.wisso.newpipematerial.debug`) next to the official
WizeStream, so you can try it without touching your subscriptions. The patch has been submitted
upstream.
