# Build environment for TubeBeam: JDK 21 + Android SDK. Gradle comes from the wrapper.
FROM eclipse-temurin:21-jdk-jammy

ARG CMDLINE_TOOLS_BUILD=15859902
ARG ANDROID_PLATFORM=37.2
ARG BUILD_TOOLS=36.0.0
ARG UID=1000
ARG GID=1000

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    LANG=C.UTF-8

RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip curl git ca-certificates \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p "$ANDROID_HOME/cmdline-tools" \
 && curl -fsSL -o /tmp/cmdline-tools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip" \
 && unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools \
 && mv /tmp/cmdline-tools/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest" \
 && rm -rf /tmp/cmdline-tools /tmp/cmdline-tools.zip

ENV PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"

RUN yes | sdkmanager --licenses > /dev/null \
 && sdkmanager "platform-tools" "platforms;android-${ANDROID_PLATFORM}" "build-tools;${BUILD_TOOLS}" > /dev/null

# Run as the host user so files written into the bind-mounted workspace keep the host's ownership.
RUN groupadd -g "$GID" dev && useradd -m -u "$UID" -g "$GID" -s /bin/bash dev \
 && chown -R dev:dev "$ANDROID_HOME"
USER dev
ENV GRADLE_USER_HOME=/home/dev/.gradle
# Pre-create the cache dir so a fresh named volume mounted here inherits dev ownership.
RUN mkdir -p /home/dev/.gradle
WORKDIR /workspace
