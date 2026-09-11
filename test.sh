#!/bin/bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")" && pwd)"
JDK="${BUTTONS_JDK:-${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$JDK/bin/javac" --release 8 -d "$OUT" "$PROJECT/src/com/efran/buttons/Rules.java" "$PROJECT/src/com/efran/buttons/McuPacket.java" "$PROJECT/src/com/efran/buttons/VoiceGesture.java" "$PROJECT/tests/com/efran/buttons/RulesTest.java"
"$JDK/bin/java" -cp "$OUT" com.efran.buttons.RulesTest
