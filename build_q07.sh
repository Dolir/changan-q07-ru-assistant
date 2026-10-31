#!/usr/bin/env bash
# build_q07.sh — адаптация changan-a06-ru-assistant к Qiyuan Q07 (C798, Android 11 / SDK 30).
#
# Использование:
#   ./build_q07.sh <q07_original.apk> <speechassistant-ru-vX.Y.Z.apk> <out.apk>
#   Из релиза мода берутся только модели; код собирается из stand/asr-android.
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
        print(f"  WARN {label}: file missing {fpath}")
        return
    s = open(fpath, encoding='utf-8').read()
    m = re.search(sig_pat, s, re.S)
    if not m:
        print(f"  WARN {label}: sig not found in {fpath}")
        return
    # вставка после .registers внутри метода
    anchor_end = m.end()
    s = s[:anchor_end] + body + s[anchor_end:]
    open(fpath, 'w', encoding='utf-8').write(s)
    print(f"  [hook] {label}")

def repl_reg(fpath, sig_pat, body, reg_to, label):
    """Как repl, но дополнительно поднимает .registers первой группы до reg_to."""
    if not os.path.exists(fpath):
        print(f"  WARN {label}: file missing {fpath}")
        return
    s = open(fpath, encoding='utf-8').read()
    m = re.search(sig_pat, s, re.S)
    if not m:
        print(f"  WARN {label}: sig not found in {fpath}")
        return
    # заменить .registers в матче
    new_sig = re.sub(r'\.registers \d+', '.registers ' + str(reg_to), m.group(0), count=1)
    anchor_end = m.end()
    s = s[:m.start()] + new_sig + body + s[anchor_end:]
    open(fpath, 'w', encoding='utf-8').write(s)
    print(f"  [hook] {label} (registers->{reg_to})")

# (a) VoiceApp.onCreate
repl(
    path('classes18','com','incall','apps','speechassistant','application','VoiceApp.smali'),
    r'(\.method public onCreate\(\)V\n\s*\.registers \d+\n)',
    ("    invoke-static {p0}, Lcom/stand/bridge/RuBridge;->init(Landroid/content/Context;)V\n"
     "    new-instance v0, Lcom/stand/bridge/StandNluReceiver;\n"
     "    invoke-direct {v0}, Lcom/stand/bridge/StandNluReceiver;-><init>()V\n"
     "    new-instance v1, Landroid/content/IntentFilter;\n"
     "    const-string v2, \"com.stand.NLU\"\n"
     "    invoke-direct {v1, v2}, Landroid/content/IntentFilter;-><init>(Ljava/lang/String;)V\n"
     "    const/4 v2, 0x2\n"
     "    invoke-virtual {p0, v0, v1, v2}, Landroid/content/Context;->registerReceiver(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;I)Landroid/content/Intent;\n"),
    'VoiceApp.onCreate -> RuBridge.init + receiver')

# (b2) SrSession.stopRecognize() -> FeedTask.start() (async feed3)
repl_reg(
    path('classes26','com','incall','apps','speechassistant','sr','SrSession.smali'),
    r'(\.method public stopRecognize\(\)I\n\s*\.registers \d+\n)',
    ("    const-string v3, \"FEED3\"\n"
     "    const-string v4, \"call-async\"\n"
     "    invoke-static {v3, v4}, Landroid/util/Log;->e(Ljava/lang/String;Ljava/lang/String;)I\n"
     "    invoke-static {}, Lcom/stand/asr/FeedTask;->start()V\n"),
    8,
    'SrSession.stopRecognize -> FeedTask.start (async feed3)')

# Create FeedTask.smali in classes26
ft_path = path('classes26', 'com', 'stand', 'asr', 'FeedTask.smali')
os.makedirs(os.path.dirname(ft_path), exist_ok=True)
open(ft_path, 'w', encoding='utf-8').write(
    '.class public Lcom/stand/asr/FeedTask;\n'
    '.super Ljava/lang/Object;\n'
    '.implements Ljava/lang/Runnable;\n\n'
    '.field private static isFeeding:Z\n\n'
    '.method public constructor <init>()V\n'
    '    .registers 1\n'
    '    invoke-direct {p0}, Ljava/lang/Object;-><init>()V\n'
    '    return-void\n'
    '.end method\n\n'
    '.method public static reset()V\n'
    '    .registers 1\n'
    '    const/4 v0, 0x0\n'
    '    sput-boolean v0, Lcom/stand/asr/FeedTask;->isFeeding:Z\n'
    '    return-void\n'
    '.end method\n\n'
    '.method public static declared-synchronized start()V\n'
    '    .registers 4\n'
    '    const-class v3, Lcom/stand/asr/FeedTask;\n'
    '    monitor-enter v3\n'
    '    :try_start\n'
    '    sget-boolean v0, Lcom/stand/asr/FeedTask;->isFeeding:Z\n'
    '    if-eqz v0, :cond_skip\n'
    '    monitor-exit v3\n'
    '    return-void\n'
    '    :cond_skip\n'
    '    const/4 v0, 0x1\n'
    '    sput-boolean v0, Lcom/stand/asr/FeedTask;->isFeeding:Z\n'
    '    new-instance v0, Ljava/lang/Thread;\n'
    '    new-instance v1, Lcom/stand/asr/FeedTask;\n'
    '    invoke-direct {v1}, Lcom/stand/asr/FeedTask;-><init>()V\n'
    '    const-string v2, "stand-feed3"\n'
    '    invoke-direct {v0, v1, v2}, Ljava/lang/Thread;-><init>(Ljava/lang/Runnable;Ljava/lang/String;)V\n'
    '    invoke-virtual {v0}, Ljava/lang/Thread;->start()V\n'
    '    :try_end\n'
    '    .catchall {:try_start .. :try_end} :catchall\n'
    '    monitor-exit v3\n'
    '    return-void\n'
    '    :catchall\n'
    '    move-exception v0\n'
    '    monitor-exit v3\n'
    '    throw v0\n'
    '.end method\n\n'
    '.method public run()V\n'
    '    .registers 1\n'
    '    invoke-static {}, Lcom/stand/asr/FeedTask;->doFeed()V\n'
    '    return-void\n'
    '.end method\n\n'
    '.method private static declared-synchronized doFeed()V\n'
    '    .registers 7\n'
    '    invoke-static {}, Lcom/stand/bridge/RuBridge;->isTtsSpeaking()Z\n'
    '    move-result v0\n'
    '    if-eqz v0, :cond_feed_tts_ok\n'
    '    const-string v0, "FeedTask"\n'
    '    const-string v1, "doFeed skipped: assistant is speaking TTS"\n'
    '    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I\n'
    '    return-void\n'
    '    :cond_feed_tts_ok\n'
    '    invoke-static {}, Lcom/incall/apps/speechassistant/common/VoiceStateCache;->isWakeUp()Z\n'
    '    move-result v0\n'
    '    if-nez v0, :cond_feed_ok\n'
    '    invoke-static {}, Lcom/incall/apps/speechassistant/common/VoiceStateCache;->isOutSide()Z\n'
    '    move-result v0\n'
    '    if-nez v0, :cond_feed_ok\n'
    '    const-string v0, "FeedTask"\n'
    '    const-string v1, "doFeed skipped: assistant asleep (not awake, not outside)"\n'
    '    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I\n'
    '    return-void\n'
    '    :cond_feed_ok\n'
    '    const/4 v0, 0x0\n'
    '    const/4 v1, 0x0\n'
    '    const/4 v2, 0x0\n'
    '    const-wide/16 v3, 0x3\n'
    '    const/4 v5, 0x0\n'
    '    const/4 v6, 0x0\n'
    '    invoke-static/range {v0 .. v6}, Lcom/stand/bridge/RuBridge;->feed(Ljava/lang/Object;IIJ[BI)V\n'
    '    return-void\n'
    '.end method\n'
)
print("  [new] classes26/com/stand/asr/FeedTask.smali")


# (b3) SrSession.startRecognize(IZ) -> FeedTask.reset + GigaAsr.reset
repl(
    path('classes26','com','incall','apps','speechassistant','sr','SrSession.smali'),
    r'(\.method public startRecognize\(IZ\)I\n\s*\.registers \d+\n)',
    ("    invoke-static {}, Lcom/stand/asr/FeedTask;->reset()V\n"
     "    invoke-static {}, Lcom/stand/asr/GigaAsr;->reset()V\n"),
    'SrSession.startRecognize -> FeedTask.reset + GigaAsr.reset')

# (b4) SrSession.onSrMsg(ILjava/lang/String;)V -> VAD hooks (speech_start=reset, speech_end/timeout=FeedTask.start)
repl(
    path('classes26','com','incall','apps','speechassistant','sr','SrSession.smali'),
    r'(\.method public onSrMsg\(ILjava/lang/String;\)V\n\s*\.registers \d+\n)',
    ("    move/from16 v0, p1\n"
     "    const/16 v1, 0x451\n"
     "    if-ne v0, v1, :stand_no_vad\n"
     "    move-object/from16 v0, p2\n"
     "    if-eqz v0, :stand_no_vad\n"
     "    const-string v1, \"speech_start\"\n"
     "    invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z\n"
     "    move-result v1\n"
     "    if-eqz v1, :stand_check_end\n"
     "    invoke-static {}, Lcom/stand/bridge/RuBridge;->isTtsSpeaking()Z\n"
     "    move-result v0\n"
     "    if-eqz v0, :stand_no_vad\n"
     "    invoke-static {}, Lcom/stand/asr/FeedTask;->reset()V\n"
     "    invoke-static {}, Lcom/stand/asr/GigaAsr;->reset()V\n"
     "    const-string v0, \"STAND_VAD\"\n"
     "    const-string v1, \"speech_start -> reset\"\n"
     "    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I\n"
     "    goto :stand_no_vad\n"
     "    :stand_check_end\n"
     "    const-string v1, \"speech_end\"\n"
     "    invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z\n"
     "    move-result v1\n"
     "    if-nez v1, :stand_check_awake\n"
     "    const-string v1, \"speech_timeout\"\n"
     "    invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z\n"
     "    move-result v1\n"
     "    if-nez v1, :stand_check_awake\n"
     "    goto :stand_no_vad\n"
     "    :stand_check_awake\n"
     "    invoke-static {}, Lcom/stand/bridge/RuBridge;->isTtsSpeaking()Z\n"
     "    move-result v0\n"
     "    if-eqz v0, :stand_no_vad\n"
     "    invoke-static {}, Lcom/incall/apps/speechassistant/common/VoiceStateCache;->isWakeUp()Z\n"
     "    move-result v0\n"
     "    if-nez v0, :stand_check_outside\n"
     "    goto :stand_feed_run\n"
     "    :stand_check_outside\n"
     "    invoke-static {}, Lcom/incall/apps/speechassistant/common/VoiceStateCache;->isOutSide()Z\n"
     "    move-result v0\n"
     "    if-eqz v0, :stand_feed_run\n"
     "    const-string v0, \"STAND_VAD\"\n"
     "    const-string v1, \"speech_end/timeout ignored: assistant is asleep\"\n"
     "    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I\n"
     "    goto :stand_no_vad\n"
     "    :stand_feed_run\n"
     "    const-string v0, \"STAND_VAD\"\n"
     "    const-string v1, \"speech_end/timeout -> FeedTask.start()\"\n"
     "    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I\n"
     "    invoke-static {}, Lcom/stand/asr/FeedTask;->start()V\n"
     "    :stand_no_vad\n"),
    'SrSession.onSrMsg -> VAD speech_start/speech_end/timeout hooks')

# (b) SeEngineProxy.process([BI)I -> downmix 8ch→mono + GigaAsr.accept (only when inside car and not TTS speaking)
repl_reg(
    path('classes14','com','incall','apps','voiceservice','proxy','SeEngineProxy.smali'),
    r'(\.method public process\(\[BI\)I\n\s*\.registers \d+\n)',
    ("    invoke-static {}, Lcom/stand/bridge/RuBridge;->isTtsSpeaking()Z\n"
     "    move-result v0\n"
     "    if-eqz v0, :cond_cabin_skip\n"
     "    invoke-static {}, Lcom/incall/apps/speechassistant/common/VoiceStateCache;->isOutSide()Z\n"
     "    move-result v0\n"
     "    if-nez v0, :cond_cabin_skip\n"
     "    new-array v4, p2, [B\n"
     "    const/4 v5, 0x0\n"
     "    const/4 v6, 0x0\n"
     "    :dmx_loop\n"
     "    if-ge v5, p2, :dmx_done\n"
     "    aget-byte v7, p1, v5\n"
     "    add-int/lit8 v8, v5, 0x1\n"
     "    aget-byte v8, p1, v8\n"
     "    aput-byte v7, v4, v6\n"
     "    add-int/lit8 v0, v6, 0x1\n"
     "    aput-byte v8, v4, v0\n"
     "    add-int/lit8 v6, v6, 0x2\n"
     "    add-int/lit8 v5, v5, 0x10\n"
     "    goto :dmx_loop\n"
     "    :dmx_done\n"
     "    invoke-static {v4, v6}, Lcom/stand/asr/GigaAsr;->accept([BI)V\n"
     "    :cond_cabin_skip\n"),
    12,
    'SeEngineProxy.process -> downmix 8ch->mono + GigaAsr.accept')

# (h) SrSession.checkNlpResult: bypass FreeWakeManager for stand commands
repl(
    path('classes26','com','incall','apps','speechassistant','sr','SrSession.smali'),
    r'(\.method private checkNlpResult\(ILcom/incall/apps/voiceservice/sr/bean/NlpBean;\)Z\n\s*\.registers \d+\n)',
    ("    if-eqz p2, :stand_check_orig\n"
     "    iget-object v0, p2, Lcom/incall/apps/voiceservice/sr/bean/NlpBean;->sid:Ljava/lang/String;\n"
     "    if-eqz v0, :stand_check_orig\n"
     "    const-string v1, \"stand\"\n"
     "    invoke-virtual {v0, v1}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z\n"
     "    move-result v0\n"
     "    if-eqz v0, :stand_check_orig\n"
     "    invoke-virtual {p0}, Lcom/incall/apps/speechassistant/sr/SrSession;->clearResult()V\n"
     "    const-string v0, \"RuBridge\"\n"
     "    const-string v1, \"checkNlpResult: stand cmd accepted, bypassing FreeWakeManager!\"\n"
     "    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I\n"
     "    const/4 v0, 0x1\n"
     "    return v0\n"
     "    :stand_check_orig\n"),
    'SrSession.checkNlpResult -> bypass FreeWakeManager for stand commands')

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
         "    invoke-static {v0, v1}, Lcom/stand/bridge/RuBridge;->onTtsText(Ljava/lang/String;Ljava/lang/Object;)Z\n"
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

# (f) TipsManager.getWakeUpTipsFromClick -> marker "Слушаю"
repl(
    path('classes23','com','incall','apps','speechassistant','tips','TipsManager.smali'),
    r'(\.method public getWakeUpTipsFromClick\(\)Ljava/lang/String;\n\s*\.registers \d+\n)',
    ("    const-string v0, \"\\u0421\\u043b\\u0443\\u0448\\u0430\\u044e\"\n"
     "    return-object v0\n"),
    'TipsManager.getWakeUpTipsFromClick -> marker')

# (g) PgsController.updateText(String,CharSequence,J) -> подменить текст на русский (UI)
repl(
    path('classes19','com','incall','apps','speechassistant','controller','PgsController.smali'),
    r'(\.method public updateText\(Ljava/lang/String;Ljava/lang/CharSequence;J\)V\n\s*\.registers \d+\n)',
    ("    invoke-static {p1}, Lcom/stand/bridge/RuBridge;->swap(Ljava/lang/String;)Ljava/lang/String;\n"
     "    move-result-object v1\n"
     "    invoke-virtual {v1}, Ljava/lang/String;->isEmpty()Z\n"
     "    move-result v2\n"
     "    if-nez v2, :stand_no_swap\n"
     "    move-object p1, v1\n"
     "    :stand_no_swap\n"),
    'PgsController.updateText -> RuBridge.swap (UI RU)')

# (c) NluManager.onArbitrationResult
repl(
    path('classes23','com','incall','apps','speechassistant','nlu','NluManager.smali'),
    r'(\.method private onArbitrationResult\(Ljava/lang/String;\)V\n\s*\.registers \d+\n)',
    ("    move-object/from16 v0, p1\n"
     "    invoke-static {v0}, Lcom/stand/bridge/RuBridge;->isOurs(Ljava/lang/String;)Z\n"
     "    move-result v1\n"
     "    if-eqz v1, :stand_pass_arb\n"
     "    invoke-static {}, Lcom/incall/apps/speechassistant/common/VoiceStateCache;->isOutSide()Z\n"
     "    move-result v1\n"
     "    if-eqz v1, :stand_pass_arb\n"
     "    const-string v0, \"RuBridge\"\n"
     "    const-string v1, \"onArbitrationResult: dropped non-ours (Chinese) NLU result\"\n"
     "    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I\n"
     "    return-void\n"
     "    :stand_pass_arb\n"
     "    invoke-static {v0}, Lcom/stand/bridge/RuBridge;->checkArbResult(Ljava/lang/String;)V\n"
     "    goto :stand_run_orig\n"
     "    :stand_run_orig\n"),
    'NluManager.onArbitrationResult -> checkArbResult if ours, else drop Chinese result')

# (c2) ArbitrationManager.arbitrationNluResult (instant arbitration 0ms for stand)
repl(
    path('classes28','com','incall','apps','speechassistant','arbitration','ArbitrationManager.smali'),
    r'(\.method public arbitrationNluResult\(Ljava/lang/String;Ljava/lang/String;\)V\n.*?invoke-virtual \{v14\}, Lcom/incall/apps/speechassistant/arbitration/ArbitrationResult;->getResult\(\)Ljava/util/concurrent/atomic/AtomicBoolean;\n\s*move-result-object v15\n)',
    ("    const-string v0, \"stand\"\n"
     "    invoke-virtual {v13, v0}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z\n"
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

# (j) VoiceEnableUtils.smali: prevent stopRecognize when outside
repl(
    path('classes21','com','incall','apps','speechassistant','util','VoiceEnableUtils.smali'),
    r'(\s*invoke-virtual \{v1, v4, v3\}, Lcom/incall/apps/speechassistant/controller/BusinessController;->stopRecognize\(IZ\)V)',
    '    # do not stop outside recognize',
    'VoiceEnableUtils: bypass stopRecognize when outside')

# (k) OutSeEngineProxy.process([BI)I -> stereo (step 4) downmix to GigaAsr.accept (only when outside)
op_path = path('classes14','com','incall','apps','voiceservice','proxy','OutSeEngineProxy.smali')
if os.path.exists(op_path):
    op_s = open(op_path, encoding='utf-8').read()
    if '.method public process([BI)I' not in op_s:
        out_proc = (
            "\n.method public process([BI)I\n"
            "    .registers 12\n"
            "    .param p1, \"buffer\"    # [B\n"
            "    .param p2, \"length\"    # I\n\n"
            "    invoke-static {}, Lcom/stand/bridge/RuBridge;->isTtsSpeaking()Z\n"
            "    move-result v0\n"
            "    if-eqz v0, :cond_out_dmx_skip\n"
            "    invoke-static {}, Lcom/incall/apps/speechassistant/common/VoiceStateCache;->isOutSide()Z\n"
            "    move-result v0\n"
            "    if-eqz v0, :cond_out_dmx_skip\n"
            "    new-array v4, p2, [B\n"
            "    const/4 v5, 0x0\n"
            "    const/4 v6, 0x0\n"
            "    :out_dmx_loop\n"
            "    if-ge v5, p2, :out_dmx_done\n"
            "    aget-byte v7, p1, v5\n"
            "    add-int/lit8 v8, v5, 0x1\n"
            "    aget-byte v8, p1, v8\n"
            "    aput-byte v7, v4, v6\n"
            "    add-int/lit8 v0, v6, 0x1\n"
            "    aput-byte v8, v4, v0\n"
            "    add-int/lit8 v6, v6, 0x2\n"
            "    add-int/lit8 v5, v5, 0x4\n"
            "    goto :out_dmx_loop\n"
            "    :out_dmx_done\n"
            "    invoke-static {v4, v6}, Lcom/stand/asr/GigaAsr;->accept([BI)V\n"
            "    :cond_out_dmx_skip\n"
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
        print("  [hook] OutSeEngineProxy.process -> stereo downmix (step 4) + GigaAsr.accept (only outside)")

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

# (p) ITvwConvertor.getWake: when Distribution is "outside", ensure direct >= 16
repl(
    path('classes15','com','incall','apps','voiceservice','vw','convertor','ITvwConvertor.smali'),
    r'(\.line 28\n\s*const-string v2, "nKeyword")',
    ("    const-string v3, \"outside\"\n"
     "    iget-object v2, v0, Lcom/incall/apps/voiceservice/vw/bean/VwBean;->distribution:Ljava/lang/String;\n"
     "    invoke-virtual {v3, v2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z\n"
     "    move-result v2\n"
     "    if-eqz v2, :cond_not_outside\n"
     "    iget v2, v0, Lcom/incall/apps/voiceservice/vw/bean/VwBean;->direct:I\n"
     "    const/16 v3, 0x10\n"
     "    if-ge v2, v3, :cond_not_outside\n"
     "    iput v3, v0, Lcom/incall/apps/voiceservice/vw/bean/VwBean;->direct:I\n"
     "    :cond_not_outside\n"
     "    .line 28\n"
     "    const-string v2, \"nKeyword\""),
    'ITvwConvertor.getWake -> outside wake direction fix (direct >= 16)')

print("  hooks done")
PY

# --------------------------------------------------------------- 3. собрать dex обратно
echo "[4/7] smali -> dex (изменённые)"
for w in classes33 classes29 classes28 classes26 classes24 classes23 classes21 classes20 classes19 classes18 classes15 classes14 classes11; do
  if [ -d "$BDIR/smali/$w" ]; then
    java -jar "$HERE/tools/smali.jar" a --api 30 "$BDIR/smali/$w" -o "$BDIR/dex/$w.dex"
    echo "  пересобран $w.dex"
  fi
done

# --------------------------------------------------------------- 4. RuBridge dex (stand/asr-android -> classes35.dex)
echo "[5/7] собираю classes35.dex из stand/asr-android (Java -> dex)"
cmd.exe /c "stand\\asr-android\\build_dex_win.bat"
cp "$HERE/stand/asr-android/build/dex7/classes.dex" "$BDIR/classes35.dex"
echo "  classes35.dex готов"

# jni
mkdir -p "$BDIR/lib/arm64-v8a"
cp "$HERE/stand/asr-android/piper/jni/arm64-v8a/"*.so "$BDIR/lib/arm64-v8a/" 2>/dev/null || true

# assets: gigaam + tera + stand/wake_chime
mkdir -p "$BDIR/assets/gigaam" "$BDIR/assets/stand"
"$SEVENZ" e -y -o"$BDIR/assets/gigaam" "$RU" "assets/gigaam/model.int8.onnx" "assets/gigaam/tokens.txt" >/dev/null 2>&1
"$SEVENZ" e -y -o"$BDIR/assets/stand" "$RU" "assets/stand/wake_chime.wav" >/dev/null 2>&1
"$SEVENZ" x -y -o"$BDIR/teraev" "$RU" "assets/tera/*" >/dev/null 2>&1
if [ -d "$BDIR/teraev/assets/tera" ]; then
  cp -r "$BDIR/teraev/assets/tera" "$BDIR/assets/"
fi

# --------------------------------------------------------------- 5. собрать APK
echo "[6/7] собираю APK"
cp "$Q07" "$BDIR/new.apk"
# удалить старые изменённые dex (чтобы заменить на новые)
for w in classes33 classes29 classes28 classes26 classes24 classes23 classes21 classes20 classes19 classes18 classes15 classes14 classes11; do
  "$SEVENZ" d "$BDIR/new.apk" "$w.dex" >/dev/null 2>&1 || true
done
# 1) собираем плоский каталог (dex в корне, lib/assets с путями)
FLAT="$BDIR/flat"; rm -rf "$FLAT"; mkdir -p "$FLAT"
cp "$BDIR/dex/classes33.dex" "$BDIR/dex/classes29.dex" "$BDIR/dex/classes28.dex" "$BDIR/dex/classes26.dex" "$BDIR/dex/classes24.dex" "$BDIR/dex/classes23.dex" \
   "$BDIR/dex/classes21.dex" "$BDIR/dex/classes20.dex" "$BDIR/dex/classes19.dex" "$BDIR/dex/classes18.dex" "$BDIR/dex/classes15.dex" "$BDIR/dex/classes14.dex" "$BDIR/dex/classes11.dex" \
   "$BDIR/classes35.dex" "$FLAT/"
cp -r "$BDIR/lib" "$FLAT/lib"
cp -r "$BDIR/assets" "$FLAT/assets"
# 2) dex в корень
( cd "$FLAT" && "$SEVENZ" a -tzip "$BDIR/new.apk" classes33.dex classes29.dex classes28.dex classes26.dex classes24.dex classes23.dex \
    classes21.dex classes20.dex classes19.dex classes18.dex classes15.dex classes14.dex classes11.dex classes35.dex >/dev/null 2>&1 ) || true
# 3) lib БЕЗ сжатия (STORED) — критично для extractNativeLibs
( cd "$FLAT" && "$SEVENZ" a -tzip -mx=0 "$BDIR/new.apk" lib >/dev/null 2>&1 ) || true
# 4) assets с сжатием (модели onnx уже сжаты slавлно)
( cd "$FLAT" && "$SEVENZ" a -tzip "$BDIR/new.apk" assets >/dev/null 2>&1 ) || true

echo "[7/7] zipalign + подпись"
"$BT/zipalign.exe" -p -f 4 "$BDIR/new.apk" "$BDIR/aligned.apk"
"$BT/apksigner.bat" sign --key "$KEY_DIR/platform.pk8" --cert "$KEY_DIR/platform.x509.pem" --out "$OUT" "$BDIR/aligned.apk"

echo "[build_q07] готово: $OUT"
ls -la "$OUT"
