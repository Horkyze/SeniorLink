#!/bin/sh
# Rebuild the upstream Kotlin binding's library with patched iroh and 16 KB pages.
# The published 1.1.0 AAR still bundles iroh 1.0.2 and 4 KB-aligned binaries.
set -eu
cd "$(dirname "$0")/.."
ROOT="$PWD"
: "${ANDROID_HOME:?Set ANDROID_HOME or source scripts/env.sh}"
export PATH="$HOME/.cargo/bin:$PATH"
case "$(uname -s)" in
  Darwin) HOST=darwin-x86_64 ;;
  Linux) HOST=linux-x86_64 ;;
  *) echo "Build native libraries on macOS or Linux."; exit 1 ;;
esac
TOOLCHAIN="$ANDROID_HOME/ndk/28.2.13676358/toolchains/llvm/prebuilt/$HOST/bin"
[ -x "$TOOLCHAIN/llvm-ar" ] || { echo "Install Android NDK 28.2.13676358 first."; exit 1; }
SOURCE="$ROOT/.build-tools/iroh-ffi"
if [ ! -d "$SOURCE/.git" ]; then
  git clone --depth 1 --branch v1.1.0 https://github.com/n0-computer/iroh-ffi.git "$SOURCE"
fi
[ "$(git -C "$SOURCE" rev-parse HEAD)" = "5e451092dba0c1a09ee83ff6e5be37b1152a5c58" ]
cp native/Cargo.lock "$SOURCE/Cargo.lock"
export CARGO_HOME="$ROOT/.build-tools/cargo"
export RUSTUP_HOME="$ROOT/.build-tools/rustup"
export RUSTUP_TOOLCHAIN=1.94.0
export CARGO_TARGET_DIR="$ROOT/.build-tools/native-target"
export CARGO_PROFILE_RELEASE_LTO=false
export CARGO_PROFILE_RELEASE_DEBUG=0
export CARGO_PROFILE_RELEASE_OPT_LEVEL=2
export CARGO_BUILD_JOBS="${CARGO_BUILD_JOBS:-4}"
rustup set auto-self-update disable
rustup toolchain install "$RUSTUP_TOOLCHAIN" --profile minimal
for abi in ${ANDROID_ABIS:-arm64-v8a armeabi-v7a x86_64}; do
  case "$abi" in
    arm64-v8a) target=aarch64-linux-android; clang=aarch64-linux-android26-clang ;;
    armeabi-v7a) target=armv7-linux-androideabi; clang=armv7a-linux-androideabi26-clang ;;
    x86_64) target=x86_64-linux-android; clang=x86_64-linux-android26-clang ;;
    *) echo "Unknown ABI $abi"; exit 1 ;;
  esac
  rustup target add "$target"
  upper=$(printf '%s' "$target" | tr '[:lower:]-' '[:upper:]_')
  lower=$(printf '%s' "$target" | tr '-' '_')
  env "CARGO_TARGET_${upper}_LINKER=$TOOLCHAIN/$clang" \
    "CC_${lower}=$TOOLCHAIN/$clang" "AR_${lower}=$TOOLCHAIN/llvm-ar" \
    cargo rustc --manifest-path "$SOURCE/Cargo.toml" --locked --release --lib --target "$target" \
    -- -C link-arg=-Wl,-z,max-page-size=16384
  mkdir -p "app/src/main/jniLibs/$abi"
  cp "$CARGO_TARGET_DIR/$target/release/libiroh_ffi.so" "app/src/main/jniLibs/$abi/"
  "$TOOLCHAIN/llvm-strip" "app/src/main/jniLibs/$abi/libiroh_ffi.so"
done
