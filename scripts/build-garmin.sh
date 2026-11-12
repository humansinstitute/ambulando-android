#!/bin/zsh
# Official Garmin SDK only. Install Vivoactive 6 using Garmin SDK Manager first.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${GARMIN_SDK:?Set GARMIN_SDK to the Connect IQ SDK directory}"
: "${GARMIN_DEVELOPER_KEY:?Set GARMIN_DEVELOPER_KEY to a private Garmin-only DER key outside source}"
[[ -f "$GARMIN_DEVELOPER_KEY" ]] || { print -u2 'Garmin developer key missing'; exit 1; }
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
mkdir -p garmin/build
"$GARMIN_SDK/bin/monkeyc" -f garmin/monkey.jungle -d vivoactive6 -r -w -y "$GARMIN_DEVELOPER_KEY" -o garmin/build/AmbulandoRemote.prg
"$GARMIN_SDK/bin/monkeyc" -f garmin/monkey.jungle -e -r -w -y "$GARMIN_DEVELOPER_KEY" -o garmin/build/AmbulandoRemote.iq
shasum -a 256 garmin/build/AmbulandoRemote.prg garmin/build/AmbulandoRemote.iq
