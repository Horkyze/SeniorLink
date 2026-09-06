#!/bin/sh
# Source from the repository root after scripts/bootstrap-macos.sh.
export JAVA_HOME="${JAVA_HOME:-$PWD/.build-tools/jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$PWD/.build-tools/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$PWD/.build-tools/android-user"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export GRADLE_USER_HOME="$PWD/.build-tools/gradle-home"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$HOME/.cargo/bin:$PATH"
