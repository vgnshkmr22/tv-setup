#!/usr/bin/env bash
# Build the launcher APK and install it on the TV as the default home screen.
#
#   CHANNEL=local      (default) build on this computer, signed with its debug key; update by running
#                      this script again
#   CHANNEL=official   download the latest release APK instead of building, checked against its
#                      update.json; it updates itself on the TV from then on (docs/specs/self-update.md)
#   MIGRATE=1          switch a TV whose launcher was signed with another key (usually: built here, moving
#                      to CHANNEL=official). Android only allows that through an uninstall, so the theme
#                      overlay and config.xml are saved first and put back; module card answers are lost.
#                      Asks before uninstalling; MIGRATE_CONFIRM=yes skips the question.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
CHANNEL="${CHANNEL:-local}"
MIGRATE="${MIGRATE:-0}"
PKG=com.gathin.tvhome
FILES=/sdcard/Android/data/$PKG/files
PY=../tools/venv/bin/python
[ -x "$PY" ] || PY=../tools/venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
tv() { "$PY" -I ../tools/tv.py "$@"; }

case "$CHANNEL" in
  local)
    export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)}"
    export ANDROID_HOME="${ANDROID_HOME:-$([ -d ~/Library/Android/sdk ] && echo ~/Library/Android/sdk || echo /opt/homebrew/share/android-commandlinetools)}"
    export PATH="$JAVA_HOME/bin:$PATH"
    [ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
    ./gradlew assembleRelease -q --console=plain
    APK=app/build/outputs/apk/release/app-release.apk ;;
  official)
    mkdir -p app/build/official
    APK=app/build/official/cleanyourtv.apk
    rm -f "$APK"
    "$PY" -I ../tools/official_apk.py "$APK" ;;
  *) echo "CHANNEL must be local or official, not '$CHANNEL'" >&2; exit 1 ;;
esac
# Which app owns Home differs by build (com.google.android.tvlauncher on Android TV,
# com.google.android.apps.tv.launcherx on newer Google TV), so ask the TV instead of guessing.
# Only a launcher we recognise is accepted: the answer can also be Android's chooser ("android"),
# the setup wizard, or another third-party launcher, none of which should be disabled by accident.
# Pass STOCK_LAUNCHER=<package> to name it yourself, or STOCK_LAUNCHER=none to disable nothing.
KNOWN_STOCK_LAUNCHERS="com.google.android.tvlauncher com.google.android.apps.tv.launcherx com.google.android.leanbacklauncher"
# Migrating: the stock launcher has to hold Home while ours is uninstalled, so find which one this TV
# has (Home itself resolves to our launcher at this point).
if [ "$MIGRATE" = 1 ] && [ -z "${STOCK_LAUNCHER:-}" ]; then
  INSTALLED=$(tv 'pm list packages' | tr -d '\r')
  for p in $KNOWN_STOCK_LAUNCHERS; do
    if printf '%s\n' "$INSTALLED" | grep -qx "package:$p"; then STOCK_LAUNCHER=$p; break; fi
  done
  [ -n "${STOCK_LAUNCHER:-}" ] || { echo "ERROR: no stock launcher this script knows is installed; pass STOCK_LAUNCHER=<package>." >&2; exit 1; }
fi
if [ -z "${STOCK_LAUNCHER:-}" ]; then
  HOME_PKG=$("$PY" -I ../tools/tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' \
    | tail -n 1 | tr -d '\r' | cut -d/ -f1) || {
    echo "ERROR: could not ask the TV which launcher owns Home. Check TV_HOST and the debugging prompt on the TV," >&2
    echo "or pass STOCK_LAUNCHER=<package> yourself." >&2; exit 1; }
  case " $KNOWN_STOCK_LAUNCHERS " in
    *" $HOME_PKG "*) STOCK_LAUNCHER=$HOME_PKG ;;
    *)
      if [ "$HOME_PKG" = com.gathin.tvhome ]; then
        STOCK_LAUNCHER=none; echo "Home already belongs to this launcher; nothing will be disabled."
      elif [ "$HOME_PKG" = android ]; then
        # Android's chooser: two or more launchers tie and no default is set, which is where a TV lands
        # after tools/restore.sh followed by a re-applied list. Take the one known stock launcher among
        # the HOME claimants, if there is exactly one.
        CLAIMANTS=$("$PY" -I ../tools/tv.py 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME' \
          | tr -d '\r' | grep -o '[A-Za-z0-9_.]*/' | tr -d / | sort -u)
        FOUND=""; N=0
        for k in $KNOWN_STOCK_LAUNCHERS; do
          if printf '%s\n' "$CLAIMANTS" | grep -qx "$k"; then FOUND=$k; N=$((N + 1)); fi
        done
        if [ "$N" -eq 1 ]; then
          STOCK_LAUNCHER=$FOUND; echo "Home is Android's chooser; the stock launcher among the candidates is $STOCK_LAUNCHER."
        else
          echo "ERROR: Home is Android's launcher chooser and $N known stock launchers claim Home: pick a default on the TV first," >&2
          echo "or run again with STOCK_LAUNCHER=<package to disable> or STOCK_LAUNCHER=none." >&2; exit 1
        fi
      else
        echo "ERROR: Home currently resolves to '${HOME_PKG:-nothing}', which is not a stock launcher this script knows." >&2
        echo "  android                                  = Android's launcher chooser (two launchers tie); pick a default on the TV first" >&2
        echo "  com.google.android.tungsten.setupwraith  = Google TV's setup wizard; see README, Troubleshooting" >&2
        echo "  anything else                            = another launcher is the default" >&2
        echo "Run again with STOCK_LAUNCHER=<package to disable> or STOCK_LAUNCHER=none." >&2; exit 1
      fi ;;
  esac
fi
echo "Stock launcher: $STOCK_LAUNCHER"
if [ "$STOCK_LAUNCHER" = none ]; then DISABLE_STOCK="echo stock launcher left alone"
else DISABLE_STOCK="pm disable-user --user 0 $STOCK_LAUNCHER"; fi

BACKUP=
if [ "$MIGRATE" = 1 ]; then
  if ! tv "pm list packages $PKG" | tr -d '\r' | grep -qx "package:$PKG"; then
    echo "MIGRATE=1: the launcher isn't installed on this TV, so there is nothing to migrate."
    MIGRATE=0
  fi
fi
if [ "$MIGRATE" = 1 ]; then
  # 1. Save the overlay and wallpaper the TV has, and turn config.xml into an overlay underneath them.
  BACKUP=../backup/launcher-$(date +%Y%m%d-%H%M%S)
  mkdir -p "$BACKUP"
  ON_TV=$(tv "ls $FILES 2>/dev/null" | tr -d '\r')
  for f in config.json wallpaper.jpg; do
    if printf '%s\n' "$ON_TV" | grep -qx "$f"; then tv --pull "$FILES/$f" "$BACKUP/tv-$f"; fi
  done
  if [ -f "$BACKUP/tv-config.json" ]; then
    "$PY" -I ../tools/config2json.py app/src/main/res/values/config.xml "$BACKUP/config.json" "$BACKUP/tv-config.json"
  else
    "$PY" -I ../tools/config2json.py app/src/main/res/values/config.xml "$BACKUP/config.json"
  fi
  [ -f "$BACKUP/tv-wallpaper.jpg" ] && cp "$BACKUP/tv-wallpaper.jpg" "$BACKUP/wallpaper.jpg"
  echo "Saved to $BACKUP."
  # 2. Ask, then hand Home to the stock launcher and 3. uninstall ours.
  echo "MIGRATE=1 will uninstall $PKG from the TV and install the new APK in its place."
  echo "  kept:  your config.xml settings and the TV's theme overlay, as $BACKUP/config.json"
  echo "  lost:  your answers to module card prompts; the TV asks again"
  echo "  Home:  $STOCK_LAUNCHER is enabled while the launcher is away"
  if [ "${MIGRATE_CONFIRM:-}" != yes ]; then
    if [ -r /dev/tty ]; then
      printf 'Type yes to go on: '; read -r answer < /dev/tty
    else answer=; fi
    [ "$answer" = yes ] || { echo "Stopped; nothing on the TV was changed. Run again with MIGRATE_CONFIRM=yes to skip the question." >&2; exit 1; }
  fi
  tv "pm enable --user 0 $STOCK_LAUNCHER" >/dev/null
  tv "cmd package set-home-activity --user 0 $STOCK_LAUNCHER" >/dev/null 2>&1 || true
  OUT=$(tv "pm uninstall $PKG" | tr -d '\r')
  case "$OUT" in *Success*) echo "Uninstalled the old launcher; $STOCK_LAUNCHER holds Home." ;;
    *) echo "ERROR: pm uninstall said: $OUT" >&2; exit 1 ;; esac
fi

"$PY" -I ../tools/b64push.py "$APK" /data/local/tmp/tvhome.apk
OUT=$(tv 'pm install -r /data/local/tmp/tvhome.apk; rm -f /data/local/tmp/tvhome.apk' | tr -d '\r')
echo "$OUT"
case "$OUT" in
  *Success*) ;;
  *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
    echo "ERROR: the launcher on the TV is signed with another key. To replace it, run again with MIGRATE=1." >&2; exit 1 ;;
  *)
    [ -z "$BACKUP" ] || echo "Your settings are saved in $BACKUP; $STOCK_LAUNCHER holds Home meanwhile." >&2
    echo "ERROR: pm install failed; see the line above and docs/TROUBLESHOOTING.md." >&2; exit 1 ;;
esac
# Grants for self-update, best effort: "Install unknown apps" (many TVs have no screen for it) and
# WRITE_SECURE_SETTINGS, which lets the launcher switch its accessibility services back on after updating.
printf '%s\n' \
  "appops set $PKG REQUEST_INSTALL_PACKAGES allow 2>/dev/null && echo install-permission-granted || echo install-permission-not-granted" \
  "pm grant $PKG android.permission.WRITE_SECURE_SETTINGS 2>/dev/null && echo secure-settings-granted || echo secure-settings-not-granted" \
  "pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted" \
  "cmd package compile -m speed -f com.gathin.tvhome" \
  "am start -W -n com.gathin.tvhome/.MainActivity | grep -E '^(Status|Error)'" \
  "$DISABLE_STOCK" \
  "cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity" \
  | "$PY" -I ../tools/tv.py --batch
# Order matters: the new launcher is started before the stock one is disabled, and the home
# preference is set after. Android drops the preference whenever the set of HOME apps changes, and
# on Google TV 14 the next candidate is the setup wizard's RecoveryActivity, which crash-loops
# system_server (see examples/xiaomi-tv-a-pro-2026). So check where Home ended up, and put the
# stock launcher back if it is not ours.
HOME_NOW=$("$PY" -I ../tools/tv.py 'cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN' | tail -n 1 | tr -d '\r')
case "$HOME_NOW" in
  com.gathin.tvhome/*) echo "Home now resolves to $HOME_NOW" ;;
  *)
    [ "$STOCK_LAUNCHER" = none ] || "$PY" -I ../tools/tv.py "pm enable --user 0 $STOCK_LAUNCHER" >/dev/null || true
    echo "ERROR: Home resolves to ${HOME_NOW:-nothing}, not to the new launcher; $STOCK_LAUNCHER was re-enabled." >&2
    echo "On Google TV this usually means com.google.android.tungsten.setupwraith also claims Home: add it to" >&2
    echo "your debloat list (see README, Troubleshooting), apply the list, then run this script again." >&2
    exit 1 ;;
esac

# Optional accessibility services. Off unless asked for, and Android switches them off again on
# every reinstall, so pass the flag each time you run this script.
#   INPUT_KEY_SERVICE=1   the remote's Input button (KEYCODE_TV) opens the input switcher from any
#                         app; Google TV needs this once the stock launcher is gone (InputKeyService)
#   HOME_KEY_SERVICE=1    remotes whose Home button sends KEYCODE_ALL_APPS instead of HOME, e.g. Xiaomi
#                         sticks: the key is routed to the launcher (HomeKeyService)
enable_service() {   # appends to enabled_accessibility_services without dropping other services
  svc=com.gathin.tvhome/.$1
  "$PY" -I ../tools/tv.py "s=\$(settings get secure enabled_accessibility_services); case \"\$s\" in *$1*) ;; null|'') settings put secure enabled_accessibility_services $svc ;; *) settings put secure enabled_accessibility_services \"\$s:$svc\" ;; esac; settings put secure accessibility_enabled 1; echo $1 enabled"
}
if [ "${INPUT_KEY_SERVICE:-0}" = 1 ]; then enable_service InputKeyService; fi
if [ "${HOME_KEY_SERVICE:-0}" = 1 ]; then enable_service HomeKeyService; fi
# 4. Put the saved overlay back and restart Home so it reads it, as tools/theme.sh does.
if [ -n "$BACKUP" ]; then
  tv "mkdir -p $FILES" >/dev/null
  tv --push "$BACKUP/config.json" "$FILES/config.json"
  [ -f "$BACKUP/wallpaper.jpg" ] && "$PY" -I ../tools/b64push.py "$BACKUP/wallpaper.jpg" "$FILES/wallpaper.jpg"
  tv "am force-stop $PKG; am start -n $PKG/.MainActivity >/dev/null; echo settings-restored" | tail -n 1
fi
echo "Installed. Press Home on the remote to see it."
