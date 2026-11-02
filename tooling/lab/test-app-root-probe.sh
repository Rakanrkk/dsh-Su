#!/bin/bash
# SukiSU 5586 App UID root probe runner.
# Usage: bash tooling/lab/test-app-root-probe.sh <apk-path> <device-serial>
set -euo pipefail
APK=${1:?usage: test-app-root-probe.sh <apk-path>}
A=${ADB:-adb}
S=${2:?usage: test-app-root-probe.sh <apk-path> <device-serial>}
PKG=io.github.rakanrkk.dsh_su
ACT=io.github.ccch1mneyyy.dsh_app.diagnostics.RootShellProbeActivity

[ "$($A -s "$S" shell getprop ro.boot.qemu | tr -d '\r')" = 1 ] || { echo WRONG_DEVICE; exit 2; }
[ "$($A -s "$S" shell getprop ro.product.model | tr -d '\r')" = 'Android SDK built for x86_64' ] || { echo WRONG_MODEL; exit 2; }
sha256sum "$APK"
$A -s "$S" install -r "$APK"
$A -s "$S" shell pm path "$PKG"
$A -s "$S" shell am force-stop "$PKG" || true
$A -s "$S" shell am start -n "$PKG/$ACT"
sleep 3
$A -s "$S" shell input tap 360 375
sleep 8
$A -s "$S" shell logcat -d -s DshRootAppProbe | tail -14
