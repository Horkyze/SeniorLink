# Android iroh native build

The app uses the Kotlin API from `computer.iroh:iroh:1.1.0` and builds the matching
native FFI source at commit `5e451092dba0c1a09ee83ff6e5be37b1152a5c58` (tag `v1.1.0`).
The small `IrohAndroid` JNI initializer is reproduced from that source under
its MIT OR Apache-2.0 license.

The upstream `iroh-android:1.1.0` AAR was evaluated, but is not used:

- Its native libraries have 4 KB ELF LOAD alignment, not 16 KB.
- Its Rust lockfile uses iroh 1.0.2. The [iroh 1.1.0 security release](https://www.iroh.computer/blog/iroh-1-1-0)
  fixes malicious relay packets pinning a CPU core and other transport issues.

`Cargo.lock` is the upstream lockfile with `cargo update -p iroh --precise 1.1.0`
applied. It upgrades the core, base, DNS and relay crates while retaining the same
FFI API and UniFFI generation version. This is intentional: the Kotlin/native
UniFFI checksums must agree.

`sh scripts/build-native.sh` downloads the pinned upstream source into the ignored
`.build-tools/` directory, applies this lockfile, installs project-local Rust
1.94.0 targets, and builds using Android NDK 28.2.13676358. It sets 16 KB linker
alignment on the final library and stages stripped binaries into
`app/src/main/jniLibs/`.

Default ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`. All are required for a normal
APK build. `ANDROID_ABIS=arm64-v8a` can narrow an incremental native rebuild.

Do not replace the native library or Maven API independently. After an upgrade,
run the Android instrumented tests, verify ELF alignment, and test a real
connection before shipping.
