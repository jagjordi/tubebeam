# TubeBeam
Android app (Kotlin, Compose) that receives YouTube share intents and sends them to
Android TVs running SmartTube via an embedded ADB client (Kadb). Full spec: PLAN.md.

## Build environment
Everything (JDK 21, Android SDK 36, Gradle via wrapper) runs in a Docker container
defined in `docker/Dockerfile` + `compose.yaml`. Nothing Android-related is installed on the host.
`./gw` runs the Gradle wrapper inside the container; `./gw --sh` opens a shell (or runs a command) in it.
The container uses host networking and the host's adb server, so `./gw installDebug` reaches a phone plugged into the host.

## Commands
- Build image (first time / after Dockerfile change): `docker compose build`
- Build: `./gw assembleDebug`
- Install on phone: `./gw installDebug` (host `adb devices` must list the phone)
- Tests: `./gw test`
- Lint: `./gw lint`

## Rules
- All ADB/network calls on Dispatchers.IO with explicit timeouts.
- Keep the ADB library behind the AdbTransport interface.
- Never log the full RSA private key or pairing codes.
- Implement PLAN.md phases in order; stop for manual testing after each phase.
