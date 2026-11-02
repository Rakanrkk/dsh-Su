#!/usr/bin/env bash
# 用法：bash tooling/check-dev-env.sh；检查本机工具、依赖与 Android 构建配置。
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
source "$ROOT/tooling/env.sh"
java -version
javac -version
node --version
flutter --version
sdkmanager --list_installed
cd "$ROOT/app"
flutter pub get --enforce-lockfile
cd android
./gradlew --no-daemon help
