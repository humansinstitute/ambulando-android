#!/bin/zsh
# Builds the signed release APK. The keystore lives outside the repository and its password is read from the macOS
# Keychain, so no signing material is ever written into the working tree or the build logs.
#
#   AMBULANDO_KEYSTORE   path to the PKCS12 keystore (default: ~/.config/ambulando-android/release.jks)
#   AMBULANDO_KEYCHAIN   Keychain service holding the store password (default: ambulando-android-release-keystore)
set -euo pipefail
cd "$(dirname "$0")/.."

store="${AMBULANDO_KEYSTORE:-$HOME/.config/ambulando-android/release.jks}"
service="${AMBULANDO_KEYCHAIN:-ambulando-android-release-keystore}"
[[ -f "$store" ]] || { print -u2 "Release keystore not found at $store"; exit 1; }
[[ "$(stat -f %Lp "$store")" == 600 ]] || { print -u2 "Refusing: $store must have mode 600"; exit 1; }

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export AMBULANDO_RELEASE_STORE="$store"
export AMBULANDO_RELEASE_STORE_PASSWORD="$(security find-generic-password -s "$service" -a release -w)"
trap 'unset AMBULANDO_RELEASE_STORE_PASSWORD' EXIT

./gradlew --no-configuration-cache clean :app:assembleRelease "$@"

apk=app/build/outputs/apk/release/app-release.apk
build_tools="${ANDROID_HOME:-$HOME/Library/Android/sdk}/build-tools"
apksigner="$(ls -d "$build_tools"/*/apksigner | sort -V | tail -1)"
"$apksigner" verify --verbose --print-certs "$apk" | grep -E "Verified using|Signer #1 certificate SHA-256"
shasum -a 256 "$apk"
