#!/bin/sh
# Offline unit tests for the ru2zh command mapper (no car, no models — just a JDK).
# Compiles the PRODUCTION source stand/asr-android/src/com/stand/bridge/Ru2Zh.java (the file that ships
# in classes7.dex) together with Ru2ZhTest.java, then checks:
#   tests.tsv   — "фраза<TAB>ожидаемый_ZH[<TAB>UNSAFE]"; NULL = must not be a command (chat)
#   chatter.txt — small talk / questions; none may become a command
#   LangDetectTest — language tag of multilingual ASR output
#   tests_uz.tsv / chatter_uz.txt — the same checks for the Uzbek mapper (Uz2Ru → Ru2Zh)
set -e
cd "$(dirname "$0")"
SRC=../../../stand/asr-android/src/com/stand/bridge/Ru2Zh.java
# LangDetect: the multi build's per-utterance language tag (ru/kk/ky/uz/en), pure Java too.
LD=../../../stand/asr-android/src/com/stand/asr/LangDetect.java
# Uz2Ru: Uzbek → Russian keyword phrase → the same Ru2Zh (multi build). tests_uz.tsv mirrors tests.tsv line for line.
UZ=../../../stand/asr-android/src/com/stand/bridge/Uz2Ru.java
# AppNames: which app «открой ...» names, pure Java too.
APPS=../../../stand/asr-android/src/com/stand/bridge/AppNames.java
# Resolve a JDK with javac (any JDK 11+ works). Override with JAVA_HOME.
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JH="$JAVA_HOME"
else
  JH=""
  for c in "$(/usr/libexec/java_home 2>/dev/null || true)" \
           /opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/openjdk /usr/local/opt/openjdk@17 \
           /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-17; do
    if [ -n "$c" ] && [ -x "$c/bin/javac" ]; then JH="$c"; break; fi
  done
fi
if [ -n "$JH" ]; then JAVAC="$JH/bin/javac"; JAVA="$JH/bin/java"; else JAVAC=javac; JAVA=java; fi

rm -rf build && mkdir -p build
"$JAVAC" -encoding UTF-8 -d build "$SRC" "$LD" "$UZ" "$APPS" Ru2ZhTest.java LangDetectTest.java Uz2ZhTest.java AppNamesTest.java
"$JAVA" -cp build com.stand.asr.LangDetectTest
"$JAVA" -cp build com.stand.bridge.AppNamesTest
"$JAVA" -cp build com.stand.bridge.Ru2ZhTest tests.tsv chatter.txt
"$JAVA" -cp build com.stand.bridge.Uz2ZhTest tests_uz.tsv chatter_uz.txt
