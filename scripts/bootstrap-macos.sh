#!/bin/sh
# Project-local dependencies; does not install into /Applications or change PATH.
set -eu
cd "$(dirname "$0")/.."
[ "$(uname -sm)" = "Darwin arm64" ] || {
  echo "On other hosts, install JDK 21, Android SDK 35 and NDK 28.2.13676358."
  exit 1
}
mkdir -p .build-tools/downloads .build-tools/jdk .build-tools/android-sdk/cmdline-tools
download() {
  url="$1"; dest="$2"; hash="$3"; algorithm="$4"
  if ! echo "$hash  $dest" | shasum -a "$algorithm" -c - >/dev/null 2>&1; then
    curl --fail --location --retry 3 --continue-at - "$url" -o "$dest"
  fi
  echo "$hash  $dest" | shasum -a "$algorithm" -c -
}
if [ ! -x .build-tools/jdk/Contents/Home/bin/java ]; then
  download 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12.1_1.tar.gz' \
    .build-tools/downloads/jdk.tar.gz 3623232f33a9c3baadf304480b2535f9a3cba8a58d42ecbb438ba267315d9998 256
  tar -xzf .build-tools/downloads/jdk.tar.gz -C .build-tools/jdk --strip-components=1
fi
if [ ! -x .build-tools/android-sdk/cmdline-tools/latest/bin/sdkmanager ]; then
  download https://dl.google.com/android/repository/commandlinetools-mac_x86_64-16111833_latest.zip \
    .build-tools/downloads/cmdline.zip 112cf9618794a997ff273537d55bee02c22abffe 1
  unzip -q .build-tools/downloads/cmdline.zip -d .build-tools/android-sdk/cmdline-tools
  mv .build-tools/android-sdk/cmdline-tools/cmdline-tools .build-tools/android-sdk/cmdline-tools/latest
fi
. ./scripts/env.sh
# Running this bootstrap accepts the Android SDK licenses for this local SDK.
"$ANDROID_HOME/cmdline-tools/latest/bin/android" --no-metrics --sdk "$ANDROID_HOME" sdk install \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;28.2.13676358"
