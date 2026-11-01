# Build environment for SmartTube (Gradle 7.5 / AGP 7.4.2): JDK 17 + Android SDK 34, build-tools 30.0.3.
FROM eclipse-temurin:17-jdk-jammy

ARG CMDLINE_TOOLS_BUILD=11076708
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
 && sdkmanager "platform-tools" "platforms;android-34" "build-tools;30.0.3" > /dev/null

RUN groupadd -g "$GID" dev && useradd -m -u "$UID" -g "$GID" -s /bin/bash dev \
 && chown -R dev:dev "$ANDROID_HOME"
USER dev
ENV GRADLE_USER_HOME=/home/dev/.gradle
RUN mkdir -p /home/dev/.gradle
WORKDIR /workspace
