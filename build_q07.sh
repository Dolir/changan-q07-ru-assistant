#!/usr/bin/env bash
# build_q07.sh — адаптация changan-a06-ru-assistant к Qiyuan Q07 (C798, Android 11 / SDK 30).
#
# Использование:
#   ./build_q07.sh <q07_original.apk> <speechassistant-ru-vX.Y.Z.apk> <out.apk>
#   Из релиза мода берётся только звук вызова; код собирается из stand/asr-android, модели ставит OpenQiyuan.
#
# Требует: java (ровно JDK 17: d8 из build-tools 34 падает на классах от javac 25), python3, 7z, zipalign/apksigner (build-tools), baksmali/smali (tools/).
set -euo pipefail

Q07="${1:?q07_original.apk}"
RU="${2:?speechassistant-ru-vX.Y.Z.apk}"
OUT="${3:?out.apk}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BDIR="$HERE/build"

# python: предпочитаем прямой путь Python313
PY="$HOME/AppData/Local/Programs/Python/Python313/python.exe"
[ -x "$PY" ] || PY="$(command -v python3 || command -v python)"

BT="$HOME/AppData/Local/Android/Sdk/build-tools/34.0.0"
SEVENZ="C:/Program Files/7-Zip/7z.exe"
KEY_DIR="$HERE/tools/platform-key"

rm -rf "$BDIR"; mkdir -p "$BDIR"
echo "[build_q07] build dir: $BDIR"

# --------------------------------------------------------------- 1. распаковка
echo "[1/7] извлекаю dex из Q07 APK"
mkdir -p "$BDIR/dex"
"$SEVENZ" e -y -o"$BDIR/dex" "$Q07" "classes*.dex" >/dev/null 2>&1

echo "[2/7] baksmali -> smali (api 30)"
mkdir -p "$BDIR/smali"
for d in "$BDIR"/dex/classes*.dex; do
  n="$(basename "$d" .dex)"
  java -jar "$HERE/tools/baksmali.jar" d --api 30 "$d" -o "$BDIR/smali/$n" 2>/dev/null || true
done
ls "$BDIR/smali" | wc -l | xargs echo "  распаковано dex:"

# --------------------------------------------------------------- 2. smali hooks (python)
echo "[3/7] smali-хуки (python regex)"
PYTHONIOENCODING=utf-8 "$PY" - "$BDIR/smali" <<'PY'
import os, re, sys
base = sys.argv[1]

def path(*parts):
    return os.path.join(base, *parts)

def repl(fpath, sig_pat, body, label):
    """Заменяет тело метода по regex-якорю. sig_pat — regex от `.method` до
    `.registers N\n`. body вставляется сразу после `.registers` строки."""
    if not os.path.exists(fpath):
        sys.exit(f"hook {label}: file missing {fpath}")
    s = open(fpath, encoding='utf-8').read()
    m = re.search(sig_pat, s, re.S)
    if not m:
        sys.exit(f"hook {label}: sig not found in {fpath}")
    # вставка после .registers внутри метода
    anchor_end = m.end()
    s = s[:anchor_end] + body + s[anchor_end:]
    open(fpath, 'w', encoding='utf-8').write(s)
    print(f"  [hook] {label}")

# (a) VoiceApp: загрузчик dex до загрузки наших классов, остальное через него (см. DexOverlay)
repl(
    path('classes18','com','incall','apps','speechassistant','application','VoiceApp.smali'),
    r'(\.method public onCreate\(\)V\n\s*\.registers \d+\n)',
    "    invoke-static {p0}, Lcom/stand/loader/DexOverlay;->boot(Landroid/app/Application;)V\n",
    'VoiceApp.onCreate -> DexOverlay.boot')
va = path('classes18','com','incall','apps','speechassistant','application','VoiceApp.smali')
open(va, 'a', encoding='utf-8').write(
    "\n.method protected attachBaseContext(Landroid/content/Context;)V\n"
    "    .registers 2\n"
    "    invoke-super {p0, p1}, Landroid/app/Application;->attachBaseContext(Landroid/content/Context;)V\n"
    "    invoke-static {p1}, Lcom/stand/loader/DexOverlay;->install(Landroid/content/Context;)V\n"
    "    return-void\n"
    ".end method\n")
print("  [hook] VoiceApp.attachBaseContext -> DexOverlay.install")

# Хуки только вызывают методы com.stand.bridge.Hooks, решения принимает Java: их можно менять обновлением dex.

# (b2) SrSession.stopRecognize
repl(
    path('classes26','com','incall','apps','speechassistant','sr','SrSession.smali'),
    r'(\.method public stopRecognize\(\)I\n\s*\.registers \d+\n)',
    "    invoke-static {}, Lcom/stand/bridge/Hooks;->onStopRecognize()V\n",
    'SrSession.stopRecognize -> Hooks.onStopRecognize')

# (b3) SrSession.startRecognize(IZ)
repl(
    path('classes26','com','incall','apps','speechassistant','sr','SrSession.smali'),
    r'(\.method public startRecognize\(IZ\)I\n\s*\.registers \d+\n)',
    "    invoke-static {}, Lcom/stand/bridge/Hooks;->onStartRecognize()V\n",
    'SrSession.startRecognize -> Hooks.onStartRecognize')

# (b4) SrSession.onSrMsg(ILjava/lang/String;)V
repl(
    path('classes26','com','incall','apps','speechassistant','sr','SrSession.smali'),
    r'(\.method public onSrMsg\(ILjava/lang/String;\)V\n\s*\.registers \d+\n)',
    ("    move/from16 v0, p1\n"
     "    move-object/from16 v1, p2\n"
     "    invoke-static {v0, v1}, Lcom/stand/bridge/Hooks;->onSrMsg(ILjava/lang/String;)V\n"),
    'SrSession.onSrMsg -> Hooks.onSrMsg')

# (b) SeEngineProxy.process([BI)I
repl(
    path('classes14','com','incall','apps','voiceservice','proxy','SeEngineProxy.smali'),
    r'(\.method public process\(\[BI\)I\n\s*\.registers \d+\n)',
    "    invoke-static {p1, p2}, Lcom/stand/bridge/Hooks;->onCabinAudio([BI)V\n",
    'SeEngineProxy.process -> Hooks.onCabinAudio')

# (h) SrSession.checkNlpResult: наши команды идут мимо FreeWakeManager
repl(
    path('classes26','com','incall','apps','speechassistant','sr','SrSession.smali'),
    r'(\.method private checkNlpResult\(ILcom/incall/apps/voiceservice/sr/bean/NlpBean;\)Z\n\s*\.registers \d+\n)',
    ("    invoke-static {p0, p2}, Lcom/stand/bridge/Hooks;->acceptStandNlp(Ljava/lang/Object;Ljava/lang/Object;)Z\n"
     "    move-result v0\n"
     "    if-eqz v0, :stand_check_orig\n"
     "    const/4 v0, 0x1\n"
     "    return v0\n"
     "    :stand_check_orig\n"),
    'SrSession.checkNlpResult -> Hooks.acceptStandNlp')

# (d) TtsPlayer.start / TtsPlayer2.start
for cls in ('TtsPlayer','TtsPlayer2'):
    fp = path('classes20','com','incall','apps','speechassistant','tts', cls+'.smali')
    if not os.path.exists(fp):
        continue
    repl(
        fp,
        r'(\.method public start\(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZLcom/incall/apps/speechassistant/tts/IPlayerListener;\)I\n\s*\.registers \d+\n)',
        ("    move-object/from16 v0, p2\n"
         "    move-object/from16 v1, p5\n"
         "    move-object/from16 v2, p0\n"
         "    invoke-static {v0, v1, v2}, Lcom/stand/bridge/RuBridge;->onTtsText(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)Z\n"
         "    move-result v0\n"
         "    if-eqz v0, :stand_tts_orig\n"
         "    const/4 v0, 0x0\n"
         "    return v0\n"
         "    :stand_tts_orig\n"),
        cls+'.start -> onTtsText')

# (d2) TtsPlayer.requestTtsFocus: отказ в фокусе не отменяет ответ (голос ассистента в политике машины самый низкий)
tp = path('classes20','com','incall','apps','speechassistant','tts','TtsPlayer.smali')
ts = open(tp, encoding='utf-8').read()
ts, n = re.subn(
    r'(->mFocusCounts:Ljava/util/concurrent/atomic/AtomicInteger;\n\n'
    r'    invoke-virtual \{v3\}, Ljava/util/concurrent/atomic/AtomicInteger;->decrementAndGet\(\)I\n\n'
    r'    \.line \d+\n)'
    r'    const/4 v3, 0x0\n',
    r'\1    invoke-static {}, Lcom/stand/bridge/RuBridge;->onTtsFocusDenied()V\n\n    const/4 v3, 0x1\n',
    ts)
assert n == 1, f"requestTtsFocus failure branch: {n} matches"
open(tp, 'w', encoding='utf-8').write(ts)
print("  [hook] TtsPlayer.requestTtsFocus -> denied focus no longer cancels the reply")

# (e) CommonConfig.getDialogUrl -> loopback
repl(
    path('classes21','com','incall','apps','speechassistant','util','CommonConfig.smali'),
    r'(\.method public static final getDialogUrl\(\)Ljava/lang/String;\n\s*\.registers \d+\n)',
    ("    const-string v1, \"http://127.0.0.1\"\n"
     "    return-object v1\n"),
    'CommonConfig.getDialogUrl -> loopback')

# (f) TipsManager.getWakeUpTipsFromClick
repl(
    path('classes23','com','incall','apps','speechassistant','tips','TipsManager.smali'),
    r'(\.method public getWakeUpTipsFromClick\(\)Ljava/lang/String;\n\s*\.registers \d+\n)',
    ("    invoke-static {}, Lcom/stand/bridge/Hooks;->clickWakeTip()Ljava/lang/String;\n"
     "    move-result-object v0\n"
     "    return-object v0\n"),
    'TipsManager.getWakeUpTipsFromClick -> Hooks.clickWakeTip')

# (g) PgsController.updateText(String,CharSequence,J) -> подменить текст на русский (UI)
repl(
    path('classes19','com','incall','apps','speechassistant','controller','PgsController.smali'),
    r'(\.method public updateText\(Ljava/lang/String;Ljava/lang/CharSequence;J\)V\n\s*\.registers \d+\n)',
    ("    invoke-static {p1}, Lcom/stand/bridge/Hooks;->updateText(Ljava/lang/String;)Ljava/lang/String;\n"
     "    move-result-object p1\n"),
    'PgsController.updateText -> Hooks.updateText (UI RU)')

# (c) NluManager.onArbitrationResult
repl(
    path('classes23','com','incall','apps','speechassistant','nlu','NluManager.smali'),
    r'(\.method private onArbitrationResult\(Ljava/lang/String;\)V\n\s*\.registers \d+\n)',
    ("    move-object/from16 v0, p1\n"
     "    invoke-static {v0}, Lcom/stand/bridge/Hooks;->onArbitrationResult(Ljava/lang/String;)Z\n"
     "    move-result v0\n"
     "    if-nez v0, :stand_run_orig\n"
     "    return-void\n"
     "    :stand_run_orig\n"),
    'NluManager.onArbitrationResult -> Hooks.onArbitrationResult')

# (c2) ArbitrationManager.arbitrationNluResult (instant arbitration 0ms for stand)
repl(
    path('classes28','com','incall','apps','speechassistant','arbitration','ArbitrationManager.smali'),
    r'(\.method public arbitrationNluResult\(Ljava/lang/String;Ljava/lang/String;\)V\n.*?invoke-virtual \{v14\}, Lcom/incall/apps/speechassistant/arbitration/ArbitrationResult;->getResult\(\)Ljava/util/concurrent/atomic/AtomicBoolean;\n\s*move-result-object v15\n)',
    ("    invoke-static {v13}, Lcom/stand/bridge/Hooks;->isStandRequest(Ljava/lang/String;)Z\n"
     "    move-result v1\n"
     "    if-eqz v1, :cond_stand_no_fast_arbi\n"
     "    invoke-virtual {v11}, Lcom/incall/apps/speechassistant/nlu/NluResponse;->getZoneId()I\n"
     "    move-result v6\n"
     "    const-string v2, \"stand instant\"\n"
     "    move-object/from16 v0, p0\n"
     "    move-object v1, v15\n"
     "    move-object v3, v13\n"
     "    move-object/from16 v4, p1\n"
     "    move-object/from16 v5, p2\n"
     "    invoke-direct/range {v0 .. v6}, Lcom/incall/apps/speechassistant/arbitration/ArbitrationManager;->arbitrationFinalResult(Ljava/util/concurrent/atomic/AtomicBoolean;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V\n"
     "    :cond_stand_no_fast_arbi\n"
     "    const-string v0, \"ArbitrationManager\"\n"),
    'ArbitrationManager.arbitrationNluResult -> instant arbitration (0ms)')

# (i) NotifierUtil.notifyTextChanged -> filterShowText (drop Chinese text / PGS)
repl(
    path('classes21','com','incall','apps','speechassistant','util','NotifierUtil.smali'),
    r'(\.method public static notifyTextChanged\(Lcom/incall/apps/speechassistant/sr/ShowTextInfo;\)V\n\s*\.registers \d+\n)',
    ("    invoke-static {p0}, Lcom/stand/bridge/RuBridge;->filterShowText(Ljava/lang/Object;)Z\n"
     "    move-result v0\n"
     "    if-nez v0, :cond_stand_notify_pass\n"
     "    return-void\n"
     "    :cond_stand_notify_pass\n"),
    'NotifierUtil.notifyTextChanged -> RuBridge.filterShowText')

# (k) OutSeEngineProxy.process([BI)I: своего метода в классе нет, добавляем
op_path = path('classes14','com','incall','apps','voiceservice','proxy','OutSeEngineProxy.smali')
if not os.path.exists(op_path):
    sys.exit("hook OutSeEngineProxy: file missing")
op_s = open(op_path, encoding='utf-8').read()
if '.method public process([BI)I' not in op_s:
    out_proc = (
        "\n.method public process([BI)I\n"
        "    .registers 12\n"
        "    .param p1, \"buffer\"    # [B\n"
        "    .param p2, \"length\"    # I\n\n"
        "    invoke-static {p1, p2}, Lcom/stand/bridge/Hooks;->onOutsideAudio([BI)V\n"
        "    iget-object v0, p0, Lcom/incall/apps/voiceservice/proxy/SeEngineProxy;->mCaSe:Lcom/incall/voicecore/se/ICaSe;\n"
        "    if-eqz v0, :cond_out_ret_zero\n"
        "    invoke-interface {v0, p1, p2}, Lcom/incall/voicecore/se/ICaSe;->process([BI)I\n"
        "    move-result v0\n"
        "    return v0\n"
        "    :cond_out_ret_zero\n"
        "    const/4 v0, 0x0\n"
        "    return v0\n"
        ".end method\n"
    )
    op_s += out_proc
    open(op_path, 'w', encoding='utf-8').write(op_s)
    print("  [hook] OutSeEngineProxy.process -> Hooks.onOutsideAudio")

# (l) GuideWordsStore.getSleepText / getSrText -> RuBridge.ruGuideSleep / ruGuideSr (Russian hints)
repl(
    path('classes29','com','incall','apps','speechassistant','guide','GuideWordsStore.smali'),
    r'(\.method public getSleepText\(Ljava/lang/String;\)Lcom/incall/apps/speechassistant/guide/TextColorBean;\n\s*\.registers \d+\n)',
    ("    invoke-static {}, Lcom/stand/bridge/RuBridge;->ruGuideSleep()Ljava/lang/Object;\n"
     "    move-result-object v0\n"
     "    check-cast v0, Lcom/incall/apps/speechassistant/guide/TextColorBean;\n"
     "    return-object v0\n"),
    'GuideWordsStore.getSleepText -> RuBridge.ruGuideSleep')

repl(
    path('classes29','com','incall','apps','speechassistant','guide','GuideWordsStore.smali'),
    r'(\.method public getSrText\(Ljava/lang/String;I\)Lcom/incall/apps/speechassistant/guide/TextColorBean;\n\s*\.registers \d+\n)',
    ("    invoke-static {}, Lcom/stand/bridge/RuBridge;->ruGuideSr()Ljava/lang/Object;\n"
     "    move-result-object v0\n"
     "    check-cast v0, Lcom/incall/apps/speechassistant/guide/TextColorBean;\n"
     "    return-object v0\n"),
    'GuideWordsStore.getSrText -> RuBridge.ruGuideSr')

# (n) PgsController.updatePgs -> no-op (never paint partial dictation)
repl(
    path('classes19','com','incall','apps','speechassistant','controller','PgsController.smali'),
    r'(\.method public updatePgs\(Ljava/util/ArrayList;II\)V\n\s*\.registers \d+\n)',
    ("    return-void\n"),
    'PgsController.updatePgs -> no-op')

print("  hooks done")
PY

# --------------------------------------------------------------- 3. собрать dex обратно
echo "[4/7] smali -> dex (изменённые)"
for w in classes33 classes29 classes28 classes26 classes24 classes23 classes21 classes20 classes19 classes18 classes14 classes11; do
  if [ -d "$BDIR/smali/$w" ]; then
    java -jar "$HERE/tools/smali.jar" a --api 30 "$BDIR/smali/$w" -o "$BDIR/dex/$w.dex"
    echo "  пересобран $w.dex"
  fi
done

# --------------------------------------------------------------- 4. RuBridge dex (stand/asr-android -> classes35.dex)
echo "[5/7] собираю classes35.dex из stand/asr-android (Java -> dex)"
cmd.exe /c "stand\\asr-android\\build_dex_win.bat"
cp "$HERE/stand/asr-android/build/dex7/classes.dex" "$BDIR/classes35.dex"
cp "$HERE/stand/asr-android/build/dex_loader/classes.dex" "$BDIR/classes36.dex"
echo "  classes35.dex готов"

# jni
mkdir -p "$BDIR/lib/arm64-v8a"
cp "$HERE/stand/asr-android/piper/jni/arm64-v8a/"*.so "$BDIR/lib/arm64-v8a/" 2>/dev/null || true

# assets: модели в APK не кладём (publish_q07.py models), только звук вызова.
# BUNDLE_MODELS=<папка с gigaam и tera> — сборка без OpenQiyuan, модели внутри APK (распаковывает ModelStore).
mkdir -p "$BDIR/assets/stand"
"$SEVENZ" e -y -o"$BDIR/assets/stand" "$RU" "assets/stand/wake_chime.wav" >/dev/null 2>&1
if [ -n "${BUNDLE_MODELS:-}" ]; then
  cp -r "$BUNDLE_MODELS/gigaam" "$BUNDLE_MODELS/tera" "$BDIR/assets/"
fi

# --------------------------------------------------------------- 5. собрать APK
echo "[6/7] собираю APK"
cp "$Q07" "$BDIR/new.apk"
# удалить старые изменённые dex (чтобы заменить на новые)
for w in classes33 classes29 classes28 classes26 classes24 classes23 classes21 classes20 classes19 classes18 classes14 classes11; do
  "$SEVENZ" d "$BDIR/new.apk" "$w.dex" >/dev/null 2>&1 || true
done
# 1) собираем плоский каталог (dex в корне, lib/assets с путями)
FLAT="$BDIR/flat"; rm -rf "$FLAT"; mkdir -p "$FLAT"
cp "$BDIR/dex/classes33.dex" "$BDIR/dex/classes29.dex" "$BDIR/dex/classes28.dex" "$BDIR/dex/classes26.dex" "$BDIR/dex/classes24.dex" "$BDIR/dex/classes23.dex" \
   "$BDIR/dex/classes21.dex" "$BDIR/dex/classes20.dex" "$BDIR/dex/classes19.dex" "$BDIR/dex/classes18.dex" "$BDIR/dex/classes14.dex" "$BDIR/dex/classes11.dex" \
   "$BDIR/classes35.dex" "$BDIR/classes36.dex" "$FLAT/"
cp -r "$BDIR/lib" "$FLAT/lib"
cp -r "$BDIR/assets" "$FLAT/assets"
# 2) dex в корень
( cd "$FLAT" && "$SEVENZ" a -tzip "$BDIR/new.apk" classes33.dex classes29.dex classes28.dex classes26.dex classes24.dex classes23.dex \
    classes21.dex classes20.dex classes19.dex classes18.dex classes14.dex classes11.dex classes35.dex classes36.dex >/dev/null 2>&1 ) || true
# 3) lib БЕЗ сжатия (STORED) — критично для extractNativeLibs
( cd "$FLAT" && "$SEVENZ" a -tzip -mx=0 "$BDIR/new.apk" lib >/dev/null 2>&1 ) || true
# 4) assets
( cd "$FLAT" && "$SEVENZ" a -tzip "$BDIR/new.apk" assets >/dev/null 2>&1 ) || true

echo "[7/7] zipalign + подпись"
"$BT/zipalign.exe" -p -f 4 "$BDIR/new.apk" "$BDIR/aligned.apk"
"$BT/apksigner.bat" sign --key "$KEY_DIR/platform.pk8" --cert "$KEY_DIR/platform.x509.pem" --out "$OUT" "$BDIR/aligned.apk"

echo "[build_q07] готово: $OUT"
ls -la "$OUT"
