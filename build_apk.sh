#!/usr/bin/env bash
# Build the signed release APK (Kotlin/Compose) in a gradle:8.10.2-jdk17
# container, so the toolchain is the same everywhere and nothing needs to be
# installed on the host but Docker.
#
# Signing credentials come from signing.env, which is not in this repository:
#   BF_KEYSTORE, BF_KS_PASS, BF_KEY_ALIAS, BF_KEY_PASS
set -euo pipefail
P="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK_CACHE:-$HOME/.android-sdk-cache}"
mkdir -p "$SDK"
HOST_UID=$(id -u); HOST_GID=$(id -g)

docker run --rm \
  -v "$P":/work -v "$SDK":/sdk -w /work \
  --env-file "$P/signing.env" \
  -e ANDROID_SDK_ROOT=/sdk -e ANDROID_HOME=/sdk \
  -e JAVA_HOME=/opt/java/openjdk \
  -e GRADLE_USER_HOME=/work/.gradle \
  -e HOST_UID="$HOST_UID" -e HOST_GID="$HOST_GID" \
  gradle:8.10.2-jdk17 bash -c '
    set -e
    export PATH=$PATH:/sdk/cmdline-tools/latest/bin:/sdk/platform-tools
    if [ ! -x /sdk/cmdline-tools/latest/bin/sdkmanager ]; then
      mkdir -p /sdk/cmdline-tools
      curl -sSLo /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
      unzip -q /tmp/cmdtools.zip -d /sdk/cmdline-tools
      mv /sdk/cmdline-tools/cmdline-tools /sdk/cmdline-tools/latest
    fi
    yes | sdkmanager --licenses >/dev/null 2>&1 || true
    sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null
    gradle :app:assembleRelease --no-daemon --console=plain
    chown -R ${HOST_UID}:${HOST_GID} /work/.gradle /work/app/build 2>/dev/null || true
    echo ">> BUILD OK"
  '
APK="$P/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] && echo "APK: $APK ($(du -h "$APK" | cut -f1))" || { echo "APK MISSING"; exit 1; }
