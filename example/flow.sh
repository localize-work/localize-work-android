#!/bin/sh
# Runs the demo app's whole flow on the connected device or emulator and
# reads the result off the screen: bundled texts on a first launch without a
# network, the first download, languages, an update while the app is open, an
# update at the next launch, a cold start without a network, and (optionally)
# the default CDN over HTTPS.
#
#   ./flow.sh <cdn-base-url> <control-url> <output-folder> [real-project-slug]
#
# Build the two APKs first, from the repository's root:
#
#   ./gradlew -p example assembleDebug assembleDebugAndroidTest
#
# <cdn-base-url>  where the test project's published files are served, as the
#                 SDK's cdnBaseUrl takes it, e.g. http://192.168.1.10:3411/cdn
# <control-url>   a server that publishes the test project on request:
#                   GET <control-url>/state/<project>         -> {"version":"v3"}
#                   GET <control-url>/publish-next/<project>  -> {"version":"v4"}
#                 Release vN must hold, in the namespace "translation":
#                   en  title "Demo (release vN)", greeting "Hello, {{name}}!",
#                       items_one "{{count}} item", items_other "{{count}} items",
#                       only_source "Only in English on the server",
#                       and from v2 on added_later "Added in release vN"
#                   et  title "Demo (väljalase vN)", greeting "Tere, {{name}}!",
#                       items_one "{{count}} asi", items_other "{{count}} asja"
#                   de  title "Demo (Version vN)", greeting "Hallo, {{name}}!",
#                       items_one and items_other "{{count}} Artikel"
# <output-folder> gets a picture of the screen at the end of every phase.
# [real-project-slug]  a published project on the default CDN with the
#                 namespace "translation" and Estonian; read only. Without it
#                 the HTTPS phase is left out.
#
# PROJECT (default demo-android) names the test project, ADB the adb to use,
# and ANDROID_SERIAL the device, as adb itself reads it. The device's own
# language should be English: the app's default resources are.
#
# The flow turns the device's network off twice and back on, also when it
# stops early. It publishes two releases of the test project.

set -u

if [ $# -lt 3 ]; then
    sed -n '2,12p' "$0" >&2
    exit 2
fi
CDN=$1
CONTROL=$2
REAL=${4:-}
PROJECT=${PROJECT:-demo-android}
ADB=${ADB:-adb}
PKG=work.localize.demo

mkdir -p "$3" || exit 2
OUT=$(cd "$3" && pwd) || exit 2
cd "$(dirname "$0")" || exit 2
APP=build/outputs/apk/debug/example-debug.apk
TESTS=build/outputs/apk/androidTest/debug/example-debug-androidTest.apk

# Git Bash on Windows rewrites arguments that look like paths before adb gets
# them; the device must get them as written.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

quiet() {
    "$ADB" shell "$@" >/dev/null 2>&1
}

# Turns the device's network off or on. Which command does it differs between
# Android versions, so all of them are tried; a phase that needs the network
# off checks for itself that the server cannot be reached.
network() {
    if [ "$1" = off ]; then
        quiet svc wifi disable
        quiet svc data disable
        quiet cmd connectivity airplane-mode enable
        quiet settings put global airplane_mode_on 1
        quiet am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true
    else
        quiet settings put global airplane_mode_on 0
        quiet am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false
        quiet cmd connectivity airplane-mode disable
        quiet svc wifi enable
        quiet svc data enable
    fi
}

stop() {
    echo "FLOW FAILED: $*" >&2
    exit 1
}

version_in() {
    sed -n 's/.*"version":"\(v[0-9][0-9]*\)".*/\1/p'
}

live_version() {
    curl -fsS --max-time 30 "$CONTROL/state/$PROJECT" | version_in
}

# The pictures the phases took are in the app's own storage.
pictures() {
    for name in $("$ADB" shell run-as "$PKG" ls files/flow 2>/dev/null | tr -d '\r'); do
        case $name in
            *.png) ;;
            *) continue ;;
        esac
        "$ADB" exec-out run-as "$PKG" cat "files/flow/$name" > "$OUT/$name" 2>/dev/null
        [ -s "$OUT/$name" ] || rm -f "$OUT/$name"
    done
}

# One phase: one test of FlowTest in a new process of the app.
phase() {
    method=$1
    shift
    echo
    echo "== $method $*"
    output=$("$ADB" shell am instrument -w \
        -e class "$PKG.FlowTest#$method" -e cdn "$CDN" -e project "$PROJECT" "$@" \
        "$PKG.test/androidx.test.runner.AndroidJUnitRunner" 2>&1)
    echo "$output"
    pictures
    case $output in
        *"OK (1 test)"*) ;;
        *) stop "$method" ;;
    esac
}

trap 'network on' EXIT

"$ADB" wait-for-device || stop "no device"
SDK=$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')
START=$(live_version)
[ -n "$START" ] || stop "$CONTROL names no release of $PROJECT; publish one first"
echo "Android API $SDK, project $PROJECT at $START"

"$ADB" install -r -t "$APP" || stop "could not install $APP"
"$ADB" install -r -t "$TESTS" || stop "could not install $TESTS"
quiet pm clear "$PKG"
quiet input keyevent 224 # wake up
quiet input keyevent 82  # past the lock screen

# 1. A first launch without a network: the app's own texts.
network off
phase offlineFirstLaunch

# 2. The first download, seen arriving, and the start after it.
network on
phase firstDownload -e version "$START"
phase coldStart -e version "$START" -e name cold-start

# 3. Estonian and German.
phase languages -e version "$START"

# 4. An update while the app is open (the phase publishes it), and one that
#    waits for the next launch.
phase updateWhileOpen -e version "$START" -e control "$CONTROL"
SHOWN=$(live_version)
[ -n "$SHOWN" ] && [ "$SHOWN" != "$START" ] || stop "nothing was published during updateWhileOpen"
NEXT=$(curl -fsS --max-time 180 "$CONTROL/publish-next/$PROJECT" | version_in)
[ -n "$NEXT" ] && [ "$NEXT" != "$SHOWN" ] || stop "$CONTROL published nothing"
phase updateAtNextLaunch -e version "$SHOWN" -e next "$NEXT"
phase coldStart -e version "$NEXT" -e policy next_launch -e name cold-start-after-next-launch

# 5. A cold start without a network: the stored release.
network off
phase coldStart -e version "$NEXT" -e offline true -e name offline-cold-start
network on

# 6. The default CDN over HTTPS.
if [ -n "$REAL" ]; then
    phase realCdn -e slug "$REAL"
fi

echo
echo "FLOW PASSED on Android API $SDK; pictures in $OUT"
