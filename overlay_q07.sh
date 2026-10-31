#!/usr/bin/env bash
# overlay_q07.sh — собирает только наш код и кладёт его на ГУ, без полной сборки и установки APK.
#
# Использование:
#   ./overlay_q07.sh <ip:port>            собрать и поставить
#   ./overlay_q07.sh <ip:port> --remove   вернуть код, встроенный в APK
#
# Требует: adb root на ГУ и JDK 17 первым в PATH (см. build_q07.sh).
set -euo pipefail

DEVICE="${1:?ip:port}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG=com.incall.apps.speechassistant
DIR="/data/data/$PKG/files/overlay"
ADB=(env MSYS_NO_PATHCONV=1 adb -s "$DEVICE")

"${ADB[@]}" root >/dev/null

if [ "${2:-}" = "--remove" ]; then
  "${ADB[@]}" shell rm -rf "$DIR"
else
  ( cd "$HERE" && cmd.exe /c "stand\asr-android\build_dex_win.bat" ) | tail -1
  "${ADB[@]}" shell mkdir -p "$DIR"
  "${ADB[@]}" push "$(cd "$HERE" && pwd -W)/stand/asr-android/build/dex7/classes.dex" "$DIR/classes.dex.new"
  "${ADB[@]}" shell rm -rf "$DIR/oat" "$DIR/attempts"
  "${ADB[@]}" shell mv "$DIR/classes.dex.new" "$DIR/classes.dex"
  "${ADB[@]}" shell chown -R 1000:1000 "$DIR"
  "${ADB[@]}" shell chmod 700 "$DIR"
fi

# Не force-stop: после него ассистент сам не поднимается
PID="$("${ADB[@]}" shell pidof "$PKG" | tr -d '\r')"
[ -n "$PID" ] && "${ADB[@]}" shell kill $PID
"${ADB[@]}" shell am startservice -n "$PKG/.service.SpeechClientService" >/dev/null
echo "[overlay_q07] готово, ассистент перезапущен"
