#!/usr/bin/env bash
# Install an APK on a TV whose firmware blocks `pm install` (TCL: "install apk has be disabled from pm
# by system default"). Stages the APK through the PackageInstaller session API from a shell-run
# app_process, then finishes with `pm install-commit`, which those firmwares leave alone.
#
# Usage: TV_HOST=192.168.1.50 tools/session-install.sh path/to/app.apk
#
# Two things this cannot do:
# - APKs that declare android.intent.category.HOME. TCL refuses those at parse time unless they are
#   signed with TCL's key: "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES: disable install launcher
#   app not signature by tcl".
# - Downgrades, and re-installs of an already installed package (use `pm uninstall` first).
set -euo pipefail
APK="${1:?usage: session-install.sh <apk>}"
APK="$(cd "$(dirname "$APK")" && pwd)/$(basename "$APK")"
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0 path/to/app.apk}"
PY=venv/bin/python
[ -x "$PY" ] || PY=venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt"; exit 1; }

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)}"
export ANDROID_HOME="${ANDROID_HOME:-$([ -d ~/Library/Android/sdk ] && echo ~/Library/Android/sdk || echo /opt/homebrew/share/android-commandlinetools)}"
D8="$(ls -d "$ANDROID_HOME"/build-tools/*/d8 2>/dev/null | sort -V | tail -1)"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
[ -x "$D8" ] && [ -f "$ANDROID_JAR" ] || {
  echo "need build-tools and platform 34: sdkmanager 'platforms;android-34' 'build-tools;34.0.0'"; exit 1; }

# Build the staging helper once: javac → d8 → jar with classes.dex inside.
if [ ! -f build/sessioninstall.jar ]; then
  mkdir -p build/dex
  "$JAVA_HOME/bin/javac" -classpath "$ANDROID_JAR" --release 8 -nowarn -d build sessioninstall/Installer.java
  "$D8" --release --lib "$ANDROID_JAR" --output build/dex build/Installer.class
  (cd build/dex && zip -q -j ../sessioninstall.jar classes.dex)
fi

"$PY" -I tv.py --push build/sessioninstall.jar /sdcard/sessioninstall.jar
# The APK goes up in base64 chunks: large files hang in the tv.py --push path (see README)
"$PY" -I b64push.py "$APK" /sdcard/session-install.apk

ID="$("$PY" -I tv.py 'CLASSPATH=/sdcard/sessioninstall.jar app_process /system/bin Installer /sdcard/session-install.apk' |
      grep -oE 'SESSION_ID=[0-9]+' | cut -d= -f2)"
[ -n "$ID" ] || { echo "staging failed"; exit 1; }
echo "staged as session $ID"

"$PY" -I tv.py "pm install-commit $ID; rm -f /sdcard/session-install.apk"
