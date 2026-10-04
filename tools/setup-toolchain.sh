#!/usr/bin/env bash
# Installs everything needed to compile Quest Soundboard locally:
#   JDK 17, the Android SDK (platform 34 + build-tools 34), Gradle, the
#   Gradle wrapper, and the throwaway sideload keystore.
#
# Everything lands under ./.toolchain (gitignored) except the SDK, which goes
# to $ANDROID_SDK_ROOT (default ~/Android/Sdk) so other projects can share it.
#
#   ./tools/setup-toolchain.sh          # install
#   source ./tools/setup-toolchain.sh --env-only   # just export the vars
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN="$ROOT/.toolchain"
GRADLE_VERSION="${GRADLE_VERSION:-8.7}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
CMDLINE_TOOLS_VERSION="11076708"   # cmdline-tools 12.0

step() { printf '\n\033[1;35m==>\033[0m %s\n' "$1"; }
have() { command -v "$1" >/dev/null 2>&1; }

# ---------------------------------------------------------------------- JDK 17

setup_java() {
  if have java && java -version 2>&1 | grep -qE '"(17|21)'; then
    step "JDK already present: $(java -version 2>&1 | head -1)"
    return
  fi

  step "Installing JDK 17"
  if have apt-get; then
    sudo apt-get update -qq
    sudo apt-get install -y openjdk-17-jdk-headless
  elif have dnf; then
    sudo dnf install -y java-17-openjdk-devel
  elif have brew; then
    brew install openjdk@17
    sudo ln -sfn "$(brew --prefix)/opt/openjdk@17/libexec/openjdk.jdk" \
      /Library/Java/JavaVirtualMachines/openjdk-17.jdk
  elif have pacman; then
    sudo pacman -S --noconfirm jdk17-openjdk
  else
    echo "No supported package manager found."
    echo "Install JDK 17 manually: https://adoptium.net/temurin/releases/?version=17"
    exit 1
  fi
}

# ----------------------------------------------------------------- Android SDK

setup_android_sdk() {
  if [ -d "$ANDROID_SDK_ROOT/platforms/android-34" ]; then
    step "Android SDK already has platform 34 at $ANDROID_SDK_ROOT"
  else
    step "Installing Android SDK into $ANDROID_SDK_ROOT"
    mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"

    if [ ! -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
      case "$(uname -s)" in
        Darwin) OS_TAG=mac ;;
        *)      OS_TAG=linux ;;
      esac
      TMP="$(mktemp -d)"
      curl -fL --progress-bar \
        "https://dl.google.com/android/repository/commandlinetools-${OS_TAG}-${CMDLINE_TOOLS_VERSION}_latest.zip" \
        -o "$TMP/cmdline-tools.zip"
      unzip -q "$TMP/cmdline-tools.zip" -d "$TMP"
      rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/latest"
      mv "$TMP/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
      rm -rf "$TMP"
    fi

    export ANDROID_SDK_ROOT ANDROID_HOME="$ANDROID_SDK_ROOT"
    SDKMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
    yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
    "$SDKMANAGER" "platform-tools" "platforms;android-34" "build-tools;34.0.0"
  fi

  # The Android Gradle Plugin reads this if ANDROID_HOME is not exported.
  if [ ! -f "$ROOT/local.properties" ]; then
    echo "sdk.dir=$ANDROID_SDK_ROOT" > "$ROOT/local.properties"
    step "Wrote local.properties"
  fi
}

# ---------------------------------------------------------------------- Gradle

setup_gradle() {
  if [ -x "$ROOT/gradlew" ] && [ -f "$ROOT/gradle/wrapper/gradle-wrapper.jar" ]; then
    step "Gradle wrapper already present"
    return
  fi

  if have gradle; then
    step "Generating wrapper with system Gradle $(gradle --version | awk '/^Gradle/{print $2}')"
    (cd "$ROOT" && gradle wrapper --gradle-version "$GRADLE_VERSION")
    return
  fi

  step "Downloading Gradle $GRADLE_VERSION"
  mkdir -p "$TOOLCHAIN"
  if [ ! -d "$TOOLCHAIN/gradle-$GRADLE_VERSION" ]; then
    curl -fL --progress-bar \
      "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
      -o "$TOOLCHAIN/gradle.zip"
    unzip -q "$TOOLCHAIN/gradle.zip" -d "$TOOLCHAIN"
    rm -f "$TOOLCHAIN/gradle.zip"
  fi
  step "Generating the Gradle wrapper"
  (cd "$ROOT" && "$TOOLCHAIN/gradle-$GRADLE_VERSION/bin/gradle" wrapper --gradle-version "$GRADLE_VERSION")
}

# -------------------------------------------------------------------- keystore

setup_keystore() {
  if [ -f "$ROOT/app/sideload.keystore" ]; then
    step "Sideload keystore already present"
    return
  fi
  step "Generating the sideload keystore"
  keytool -genkeypair -v \
    -keystore "$ROOT/app/sideload.keystore" \
    -alias soundboard \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass soundboard -keypass soundboard \
    -dname "CN=Quest Soundboard, OU=Dev, O=Quest Soundboard, C=US" >/dev/null
}

# ------------------------------------------------------------------------ main

if [ "${1:-}" = "--env-only" ]; then
  export ANDROID_SDK_ROOT ANDROID_HOME="$ANDROID_SDK_ROOT"
  export PATH="$ANDROID_SDK_ROOT/platform-tools:$PATH"
  return 0 2>/dev/null || exit 0
fi

setup_java
setup_android_sdk
setup_gradle
setup_keystore

step "Pre-flight check"
python3 "$ROOT/tools/precompile.py"

step "Building"
cd "$ROOT"
ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT" ANDROID_HOME="$ANDROID_SDK_ROOT" \
  ./gradlew --no-daemon assembleRelease

APK=$(find app/build/outputs/apk/release -name '*.apk' 2>/dev/null | head -1 || true)
if [ -n "$APK" ]; then
  printf '\n\033[1;32m==> Built %s\033[0m\n' "$APK"
  echo "Install it with:  adb install -r $APK"
else
  echo "Build finished but no APK was found." >&2
  exit 1
fi
