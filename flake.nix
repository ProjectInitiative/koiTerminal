{
  # Nix dev environment for koiTerminal (AndroidStudioProject build path)
  #
  # Mirrors the upstream Android Studio setup in BUILD.md / .github/scripts/build-common.sh:
  #   - Android Platform 37, Build Tools 36.0.0, NDK, platform-tools
  #   - Rust + aarch64/x86_64-linux-android targets + cargo-ndk 4.1.2
  #   - "fake" SDK with the modified android-37-custom.jar (hidden AVF APIs)
  #     and an aidl wrapper that forces --min_sdk_version=37
  #     (equivalent to AndroidStudioProject/tools/setup.sh + build_fake_sdk.sh,
  #      but done as a pure Nix derivation so the repo tree stays clean)
  #
  # Usage:
  #   nix develop              # enter shell, then use the helper commands
  #   build-rust               # build Rust components into app/src/main/jniLibs
  #   build-android            # ./gradlew :app:assembleDebug (extra args passed through)
  #   install-app [apk]        # adb install the built APK
  #   logcat-app               # stream logcat filtered to the app
  #   run-app                  # build-rust + build-android + install + launch + logcat
  #
  # The shell also (re)writes AndroidStudioProject/local.properties to point at
  # the modified SDK, so plain ./gradlew invocations work too.

  description = "Dev shell for building the koiTerminal Android app";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    android-nixpkgs = {
      url = "github:tadfisher/android-nixpkgs";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    fenix = {
      url = "github:nix-community/fenix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    {
      self,
      nixpkgs,
      android-nixpkgs,
      fenix,
    }:
    let
      supportedSystems = [ "x86_64-linux" ];
      forAllSystems = f: nixpkgs.lib.genAttrs supportedSystems (system: f system);

      nixpkgsFor =
        system:
        import nixpkgs {
          inherit system;
          config.allowUnfree = true;
        };

      # Package id used by the AndroidStudioProject build (see
      # AndroidStudioProject/app/build.gradle.kts). The OS/Soong build uses
      # com.android.virtualization.koiterminal instead.
      appId = "com.android.virtualization.koiterminal.githubaction";
      # Activity classes are relative to the manifest namespace
      # (com.android.virtualization.koiterminal), NOT the applicationId.
      # .LauncherActivity is the MAIN/LAUNCHER entry (manifest line ~49).
      mainActivity = "${appId}/com.android.virtualization.koiterminal.LauncherActivity";
      buildToolsVer = "36.0.0";
      sdkApiMajor = "37";
      # Directory name android-nixpkgs installs the API 37 platform under.
      # (android-nixpkgs names it android-37.0; the fake SDK needs android-37.)
      # AGP (compileSdk release(37)) resolves to platforms;android-37.0 exactly.
      platformDir = "android-37.0";
      ndkVer = "30.0.16248370";
    in

    {
      formatter = forAllSystems (system: nixpkgs.legacyPackages.${system}.nixfmt-tree);

      devShells = forAllSystems (
        system:
        let
          pkgs = nixpkgsFor system;

          androidSdk = android-nixpkgs.sdk.${system} (
            sdkPkgs: with sdkPkgs; [
              cmdline-tools-latest
              build-tools-36-0-0
              platform-tools
              platforms-android-37-0
              ndk-30-0-16248370
            ]
          );
          realSdk = "${androidSdk}/share/android-sdk";

          # Modified android.jar with hidden AVF/system APIs
          # (the same jar tools/setup.sh downloads from outlawsanzhang's release).
          fakeAndroidJar = pkgs.fetchurl {
            url = "https://github.com/outlawsanzhang/android-hidden-api/releases/download/37.0/android-37-custom.jar";
            sha256 = "1cdbspf1v10gh5h6x9l2smv4j8m9yd7sma4igjg5n3crj35kpv8w";
          };

          # Pure equivalent of AndroidStudioProject/tools/build_fake_sdk.sh:
          # copy the SDK layout, swap in the fake android.jar, wrap aidl.
          modifiedSdk =
            pkgs.runCommand "koi-modified-android-sdk"
              {
                nativeBuildInputs = [ pkgs.makeWrapper ];
                passthru = { inherit androidSdk; };
              }
              ''
                sdk_in="${realSdk}"

                mkdir -p $out/build-tools/${buildToolsVer} $out/platforms/android-${sdkApiMajor}

                # Everything except platforms/ and build-tools/ is symlinked as-is
                # (platform-tools, cmdline-tools, ndk, ...)
                for i in $(ls -1 "$sdk_in"); do
                  case "$i" in
                    platforms|build-tools) continue ;;
                  esac
                  ln -s "$sdk_in/$i" "$out/$i"
                done

                # build-tools: symlink everything except aidl, which gets a wrapper
                # that forces --min_sdk_version=37 (upstream build_fake_sdk.sh).
                for i in $(ls -1 "$sdk_in/build-tools/${buildToolsVer}"); do
                  case "$i" in
                    aidl) continue ;;
                  esac
                  ln -s "$sdk_in/build-tools/${buildToolsVer}/$i" "$out/build-tools/${buildToolsVer}/$i"
                done
                makeWrapper "$sdk_in/build-tools/${buildToolsVer}/aidl" \
                  "$out/build-tools/${buildToolsVer}/aidl" \
                  --add-flags "--min_sdk_version=${sdkApiMajor}"

                # Platform: android-nixpkgs ships android-37.0; expose it as android-37
                # with the fake android.jar swapped in.
                for i in $(ls -1 "$sdk_in/platforms/${platformDir}"); do
                  case "$i" in
                    android.jar) continue ;;
                  esac
                  ln -s "$sdk_in/platforms/${platformDir}/$i" "$out/platforms/android-${sdkApiMajor}/$i"
                done
                ln -s ${fakeAndroidJar} "$out/platforms/android-${sdkApiMajor}/android.jar"
              '';

          # Rust toolchain with the two Android targets from BUILD.md
          rustToolchain = fenix.packages.${system}.combine [
            fenix.packages.${system}.stable.cargo
            fenix.packages.${system}.stable.rustc
            fenix.packages.${system}.stable.rust-std
            fenix.packages.${system}.targets.aarch64-linux-android.stable.rust-std
            fenix.packages.${system}.targets.x86_64-linux-android.stable.rust-std
          ];

          # Gradle downloads a dynamically linked protoc-gen-grpc-java from Maven,
          # which cannot run on NixOS as-is; patchelf its interpreter/rpath.
          # Also pre-seeds the gradle cache if the artifact is missing, since a
          # plain gradle run would download it unpatched and then fail.
          patchGradleNative = pkgs.writeShellScriptBin "patch-gradle-native" ''
            CACHE="$HOME/.gradle/caches/modules-2/files-2.1/io.grpc/protoc-gen-grpc-java"
            for ver_dir in "$CACHE"/*/; do
              [ -d "$ver_dir" ] || continue
              ver=$(basename "$ver_dir")
              for sha_dir in "$ver_dir"*/; do
                [ -d "$sha_dir" ] || continue
                exe="$sha_dir/protoc-gen-grpc-java-''${ver}-linux-x86_64.exe"
                if [ ! -f "$exe" ]; then
                  mkdir -p "$sha_dir"
                  ${pkgs.curl}/bin/curl -fsSL \
                    "https://repo.maven.apache.org/maven2/io/grpc/protoc-gen-grpc-java/''${ver}/protoc-gen-grpc-java-''${ver}-linux-x86_64.exe" \
                    -o "$exe" || { rm -f "$exe"; continue; }
                fi
                interp=$(${pkgs.patchelf}/bin/patchelf --print-interpreter "$exe" 2>/dev/null) || true
                case "$interp" in
                  /nix/*) continue ;;
                esac
                if ! ${pkgs.patchelf}/bin/patchelf \
                       --set-interpreter "${pkgs.stdenv.cc.libc}/lib/ld-linux-x86-64.so.2" \
                       --set-rpath "${
                         pkgs.lib.makeLibraryPath [
                           pkgs.stdenv.cc.libc
                           pkgs.stdenv.cc.cc.lib
                         ]
                       }" \
                       "$exe"; then
                  rm -f "$exe"
                  continue
                fi
                echo "patched protoc-gen-grpc-java ''${ver} for NixOS"
              done
            done
          '';

          # --- Helper scripts ---------------------------------------------------

          buildRust = pkgs.writeShellScriptBin "build-rust" ''
            set -e
            cd AndroidStudioProject
            bash ./tools/build_rust.sh
          '';

          buildAndroid = pkgs.writeShellScriptBin "build-android" ''
            set -e
            patch-gradle-native
            cd AndroidStudioProject
            task=":app:assembleDebug"
            if [[ " $* " == *Release* ]]; then task=":app:assembleRelease"; fi
            # Retry once so artifacts gradle downloads for the first time get
            # patched (e.g. a new protoc-gen-grpc-java version).
            if ! bash ./gradlew -Pandroid.aapt2FromMavenOverride="${realSdk}/build-tools/${buildToolsVer}/aapt2" \
                 "$task" "$@"; then
              patch-gradle-native
              exec bash ./gradlew -Pandroid.aapt2FromMavenOverride="${realSdk}/build-tools/${buildToolsVer}/aapt2" \
                "$task" "$@"
            fi
          '';

          installApp = pkgs.writeShellScriptBin "install-app" ''
            set -e
            APK="''${1:-AndroidStudioProject/app/build/outputs/apk/debug/app-debug.apk}"
            if [ ! -f "$APK" ]; then
              echo "Error: APK not found at $APK. Run 'build-android' first." >&2
              exit 1
            fi
            echo "Installing $APK ..."
            exec ${pkgs.android-tools}/bin/adb install -r "$APK"
          '';

          logcatApp = pkgs.writeShellScriptBin "logcat-app" ''
            echo "Streaming logcat for ${appId} (Ctrl+C to stop)"
            PID=$(${pkgs.android-tools}/bin/adb shell pidof -s "${appId}" 2>/dev/null)
            if [ -n "$PID" ]; then
              exec ${pkgs.android-tools}/bin/adb logcat --pid=$PID "$@"
            else
              echo "Could not get PID for ${appId}; falling back to grep."
              exec ${pkgs.android-tools}/bin/adb logcat "$@" | ${pkgs.gnugrep}/bin/grep -E "${appId}|AndroidRuntime"
            fi
          '';

          runApp = pkgs.writeShellScriptBin "run-app" ''
            set -e
            build-rust
            build-android
            install-app
            echo "Launching ${mainActivity} ..."
            ${pkgs.android-tools}/bin/adb shell am start -n "${mainActivity}"
            logcat-app
          '';
        in
        {
          default = pkgs.mkShell {
            buildInputs = with pkgs; [
              androidSdk
              modifiedSdk
              rustToolchain
              cargo-ndk # 4.1.2, matches BUILD.md
              jdk25
              gradle
              android-tools
              gnugrep
              patchelf
              curl

              patchGradleNative
              buildRust
              buildAndroid
              installApp
              logcatApp
              runApp
            ];

            ANDROID_HOME = modifiedSdk;
            ANDROID_SDK_ROOT = modifiedSdk;
            # Real SDK, needed by cargo-ndk to locate the NDK
            NDK_HOME = "${realSdk}/ndk/${ndkVer}";
            JAVA_HOME = pkgs.jdk25.home;

            shellHook = ''
              # android-nixpkgs' setup hook exports the real SDK; point Gradle and
              # cargo-ndk at the modified SDK instead.
              export ANDROID_HOME=${modifiedSdk}
              export ANDROID_SDK_ROOT=${modifiedSdk}
              export PATH="${rustToolchain}/bin:${pkgs.cargo-ndk}/bin:$PATH"
              # Point Gradle at the modified SDK (fake android.jar + aidl wrapper)
              if [ -d AndroidStudioProject ]; then
                printf 'sdk.dir=%s\n' '${modifiedSdk}' > AndroidStudioProject/local.properties
              fi
              echo "koiTerminal dev shell loaded."
              echo "  ANDROID_SDK_ROOT = ${modifiedSdk} (modified SDK; real SDK: ${realSdk})"
              echo "  local.properties written to AndroidStudioProject/local.properties"
              echo ""
              echo "Commands: build-rust, build-android, install-app, logcat-app, run-app"
              echo "  build-rust        cargo-ndk build (aarch64 + x86_64)"
              echo "  build-android     gradle assembleDebug (add 'Release' for release)"
              echo "Rust:     $(rustc --version) with aarch64-linux-android + x86_64-linux-android"
            '';
          };
        }
      );
    };
}
