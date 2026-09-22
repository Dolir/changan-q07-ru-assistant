#!/usr/bin/env bash
# Reproducible builder for the patched SpeechAssistant (chat-redirect + privacy variant).
# Patches classes5 (tracer) and classes6 (endpoints/asr/trigger) of a FRESH apk.
#
#   build_sa.sh <src.apk> <out.apk>
# env:
#   PROFILE=emu|car   emu (default): + crash-swallow (emulator-only). car: no swallow.
#   HOST=host:port    backend the app POSTs to. default 10.0.2.2:8080 (emu->Mac).
#                     car: set reachable IP, e.g. HOST=172.20.10.3:8080.
#   RUSSIAN_ASR=0|1   1 => SettingsUtil.getLanguage()->4 (online iFlytek RU ASR). default 0/emu,1/car.
#   TRIGGER=1|0       install broadcast trigger (default 1).
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"
SRC="${1:?src.apk}"; OUT="${2:?out.apk}"
case "$SRC" in /*) ;; *) SRC="$(pwd)/$SRC";; esac
case "$OUT" in /*) ;; *) OUT="$(pwd)/$OUT";; esac
PROFILE="${PROFILE:-emu}"; TRIGGER="${TRIGGER:-1}"
if [ "$PROFILE" = "car" ]; then
  HOST="${HOST:?set HOST=ip:port reachable from the car}"; SWALLOW=0; RUSSIAN_ASR="${RUSSIAN_ASR:-1}"
else
  HOST="${HOST:-10.0.2.2:8080}"; SWALLOW=1; RUSSIAN_ASR="${RUSSIAN_ASR:-0}"
fi
echo "[build_sa] PROFILE=$PROFILE HOST=$HOST SWALLOW=$SWALLOW RUSSIAN_ASR=$RUSSIAN_ASR TRIGGER=$TRIGGER"

wd="$STAND_DIR/work/sa_build"; rm -rf "$wd"; mkdir -p "$wd"
( cd "$wd" && unzip -o -j "$SRC" classes5.dex classes6.dex >/dev/null )
java -jar "$PROJ_DIR/tools/baksmali.jar" d --api 34 "$wd/classes5.dex" -o "$wd/smali5"
java -jar "$PROJ_DIR/tools/baksmali.jar" d --api 34 "$wd/classes6.dex" -o "$wd/smali6"

APPDIR="$wd/smali6/com/incall/apps/speechassistant/application"
[ "$SWALLOW" = "1" ] && cp "$STAND_DIR/patches/smali/com/incall/apps/speechassistant/application/CrashSwallow.smali" "$APPDIR/"

SM5="$wd/smali5" SM6="$wd/smali6" HOST="$HOST" SWALLOW="$SWALLOW" RUSSIAN_ASR="$RUSSIAN_ASR" TRIGGER="$TRIGGER" BRIDGE="${BRIDGE:-0}" NATIVE_CLOUD="${NATIVE_CLOUD:-0}" NO_CN_SR="${NO_CN_SR:-0}" NO_CN_ENGINE="${NO_CN_ENGINE:-0}" TTS_HOOK="${TTS_HOOK:-0}" TTS_REWRITE="${TTS_REWRITE:-0}" TTS_NO_FOCUS_GATE="${TTS_NO_FOCUS_GATE:-1}" CAPTURE="${CAPTURE:-0}" WAKE_CHIME="${WAKE_CHIME:-1}" python3 - <<'PY'
import os, re
SM5=os.environ["SM5"]; SM6=os.environ["SM6"]; HOST=os.environ["HOST"]
SWALLOW=os.environ["SWALLOW"]=="1"; RU=os.environ["RUSSIAN_ASR"]=="1"; TRIG=os.environ["TRIGGER"]=="1"
CAPTURE=os.environ["CAPTURE"]=="1"   # diagnostic: send authentic ZH to real Dubhe + log outgoing query
BRIDGE=os.environ["BRIDGE"]=="1"; NATIVE_CLOUD=os.environ["NATIVE_CLOUD"]=="1"
NO_CN_SR=os.environ.get("NO_CN_SR")=="1"  # cut the iFlytek SR feed (no Mandarin recognition / no cloud upload)
NO_CN_ENGINE=os.environ.get("NO_CN_ENGINE")=="1"  # never start the iFlytek IAT engine (no Mandarin model load/decode) — frees CPU+RAM for GigaAM
TTS_REWRITE=os.environ.get("TTS_REWRITE")=="1"  # rewrite ALL TtsPlayer.start text CJK->RU (catches NLG responses that play via the stock engine, e.g. brightness 好的,中控屏已调亮)
WAKE_CHIME=os.environ.get("WAKE_CHIME")=="1"    # steering-key wake: tip text -> fixed marker «Слушаю» (PiperCaTts plays the chime for it; voice wake keeps the spoken greeting)
TTS_NO_FOCUS_GATE=os.environ.get("TTS_NO_FOCUS_GATE","1")=="1"  # speak even when the car denies audio focus (see the patch below)
TTS_HOOK=os.environ.get("TTS_HOOK")=="1"  # intercept TtsPlayer.start -> RuBridge.onTtsText (ZH->RU + Piper)
b6=SM6+"/com/incall/apps"

def repl(path, sig, body):
    s=open(path,encoding='utf-8').read()
    pat=re.compile(r'(\.method [^\n]*'+re.escape(sig)+r'\n).*?(\n\.end method)', re.S)
    assert pat.search(s), f"method {sig} not found in {path}"
    open(path,'w',encoding='utf-8').write(pat.sub(lambda m:m.group(1)+body+m.group(2), s, count=1))

# --- classes6: redirect ALL Changan endpoints to HOST (privacy) unless NATIVE_CLOUD ---
if not NATIVE_CLOUD:
    repl(f"{b6}/voicebase/util/CommonConfig.smali", "getProtocol()Ljava/lang/String;",
         '    .registers 1\n    const-string v0, "http://"\n    return-object v0')
    repl(f"{b6}/voicebase/util/CommonConfig.smali", "getDomain()Ljava/lang/String;",
         f'    .registers 1\n    const-string v0, "{HOST}"\n    return-object v0')
    repl(f"{b6}/voiceservice/proxy/SrEngineProxy.smali", "getHostUrl()V",
         ('    .registers 2\n'
          f'    const-string v0, "ws://{HOST}"\n'
          '    iput-object v0, p0, Lcom/incall/apps/voiceservice/proxy/SrEngineProxy;->API_URL_WSS:Ljava/lang/String;\n'
          f'    const-string v0, "http://{HOST}"\n'
          '    iput-object v0, p0, Lcom/incall/apps/voiceservice/proxy/SrEngineProxy;->API_URL_HTTP:Ljava/lang/String;\n'
          '    return-void'))
    repl(f"{b6}/voicebase/util/GateWayUtils.smali", "isEnableGateway()Z",
         "    .registers 1\n    const/4 v0, 0x0\n    return v0")
if RU:
    repl(f"{b6}/voicebase/util/SettingsUtil.smali", "getLanguage()I",
         "    .registers 1\n    const/4 v0, 0x4\n    return v0")

# --- classes5: TtsPlayer.requestTtsFocus — a denied focus must NOT cancel the reply.
#     This car ranks the assistant's voice (VOICE_COMMAND) LOWEST in its audio policy: CALL 0,
#     MUSIC 1, ANNOUNCEMENT 2, VOICE_COMMAND 3. com.incall.SoaBridgeTransit takes permanent
#     ANNOUNCEMENT focus and holds it for minutes, so every reply was dropped before reaching our
#     engine while commands still executed — "the assistant works but says nothing" (field report
#     22.09.2026, confirmed on the car). Real phone calls stay silent: they are gated separately by
#     VoiceEnableUtils "phone", not by focus. Ducking still works whenever focus IS granted.
if TTS_NO_FOCUS_GATE:
    tp = f"{SM5}/com/changan/speech/tts/TtsPlayer.smali"
    s = open(tp, encoding='utf-8').read()
    anchor = ("    iget-object p0, p0, Lcom/changan/speech/tts/TtsPlayer;->mFocusCounts:Ljava/util/concurrent/atomic/AtomicInteger;\n\n"
              "    invoke-virtual {p0}, Ljava/util/concurrent/atomic/AtomicInteger;->decrementAndGet()I\n\n"
              "    const/4 p0, 0x0\n\n"
              "    return p0\n")
    assert s.count(anchor) == 1, f"requestTtsFocus failure branch not found ({s.count(anchor)} matches)"
    patched = ("    iget-object p0, p0, Lcom/changan/speech/tts/TtsPlayer;->mFocusCounts:Ljava/util/concurrent/atomic/AtomicInteger;\n\n"
               "    invoke-virtual {p0}, Ljava/util/concurrent/atomic/AtomicInteger;->decrementAndGet()I\n\n"
               "    invoke-static {}, Lcom/stand/bridge/RuBridge;->onTtsFocusDenied()V\n\n"
               "    const/4 p0, 0x1\n\n"
               "    return p0\n")
    open(tp, 'w', encoding='utf-8').write(s.replace(anchor, patched, 1))
    print("[patch] TtsPlayer.requestTtsFocus: denied focus no longer cancels the reply")

# --- classes5: neutralize SpeechTracer uploads (no telemetry leaves the device) ---
tr=f"{SM5}/com/changan/speech/tracer/UploadManager.smali"
for sig in ("uploadSync(Lcom/changan/speech/tracer/SingleTraceEvent;)Z",
            "uploadSync(Lcom/changan/speech/tracer/SingleTracePoint;)Z"):
    repl(tr, sig, "    .registers 3\n    const/4 v0, 0x1\n    return v0")

# --- classes5: intercept TtsPlayer.start -> RuBridge.onTtsText (ZH->RU + Piper voice, system-wide) ---
if TTS_HOOK:
    for tp in (f"{SM5}/com/changan/speech/tts/TtsPlayer.smali",
               f"{SM5}/com/changan/speech/tts/TtsPlayer2.smali"):
        try: s=open(tp,encoding='utf-8').read()
        except FileNotFoundError: continue
        hook=("\n    move-object/16 v0, p2\n"                      # p2 = text
              "    move-object/16 v1, p5\n"                        # p5 = IPlayerListener
              "    invoke-static {v0, v1}, Lcom/stand/bridge/RuBridge;->onTtsText(Ljava/lang/String;Ljava/lang/Object;)Z\n"
              "    move-result v0\n"
              "    if-eqz v0, :stand_tts_orig\n"
              "    const/4 v0, 0x0\n"
              "    return v0\n"
              "    :stand_tts_orig\n")
        m=re.search(r'(\.method public start\(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZLcom/incall/apps/voiceserver/tts/IPlayerListener;\)I\n\s*\.registers \d+\n)', s)
        if not m: print("[patch] WARN: start() not found in", tp); continue
        s=s[:m.end()]+hook+s[m.end():]
        open(tp,'w',encoding='utf-8').write(s)
        print("[patch] TTS hook -> "+tp.split('/')[-1])

    # GlobalTtsClient: wakeup/sleep/reject/egg TIPS play via startPlayAuto(String) /
    # startPlayAutoRandom(String[]) -> voiceserver Mandarin synth (NOT the TtsPlayer.start path).
    # Route their text through RuBridge -> Piper Russian; if handled, return true (skip stock).
    gt=f"{SM5}/com/changan/speech/tts/GlobalTtsClient.smali"
    try: gs=open(gt,encoding='utf-8').read()
    except FileNotFoundError: gs=None
    if gs is not None:
        for sig, meth, argt, lbl in (
            (r'startPlayAuto\(Ljava/lang/String;\)Z',       "onTipText",   "Ljava/lang/String;",   "stand_tip"),
            (r'startPlayAutoRandom\(\[Ljava/lang/String;\)Z',"onTipRandom", "[Ljava/lang/String;",  "stand_tiprnd")):
            th=(f"\n    invoke-static {{p1}}, Lcom/stand/bridge/RuBridge;->{meth}({argt})Z\n"
                "    move-result v0\n"
                f"    if-eqz v0, :{lbl}_orig\n"
                "    const/4 v0, 0x1\n"
                "    return v0\n"
                f"    :{lbl}_orig\n")
            mm=re.search(r'(\.method public (?:varargs )?'+sig+r'\n\s*\.registers \d+\n)', gs)
            if not mm: print("[patch] WARN: GlobalTtsClient."+meth+" target not found"); continue
            gs=gs[:mm.end()]+th+gs[mm.end():]
            print("[patch] TIP hook -> GlobalTtsClient."+meth)
        open(gt,'w',encoding='utf-8').write(gs)

# --- classes5: rewrite ALL TtsPlayer.start text CJK->RU in place (p2=text). Unlike TTS_HOOK (which
#     diverts handled text to Piper), this only translates the text and lets the stock player proceed,
#     so NLG responses that route to the stock engine (e.g. brightness 好的,中控屏已调亮 via speechadapter)
#     are spoken/shown in Russian instead of Chinese. Independent of TTS_HOOK. ---
if TTS_REWRITE:
    for tp in (f"{SM5}/com/changan/speech/tts/TtsPlayer.smali",
               f"{SM5}/com/changan/speech/tts/TtsPlayer2.smali"):
        try: s=open(tp,encoding='utf-8').read()
        except FileNotFoundError: continue
        hook=("\n    move-object/16 v0, p2\n"
              "    invoke-static {v0}, Lcom/stand/bridge/RuBridge;->ttsRewrite(Ljava/lang/String;)Ljava/lang/String;\n"
              "    move-result-object v0\n"
              "    move-object/16 p2, v0\n")
        m=re.search(r'(\.method public start\(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZLcom/incall/apps/voiceserver/tts/IPlayerListener;\)I\n\s*\.registers \d+\n)', s)
        if not m: print("[patch] WARN: TtsPlayer.start not found in", tp); continue
        s=s[:m.end()]+hook+s[m.end():]
        open(tp,'w',encoding='utf-8').write(s)
        print("[patch] TTS rewrite -> "+tp.split('/')[-1])

# --- WAKE_CHIME: key/knob wake (BusinessController.changeInteraction / changeWakeupToLeft -> TipsPlayer.
#     playWakeUpTips type 1/2 -> TipsManager.getWakeUpTipsFromClick) picks from the SAME tip pool as voice
#     wake (在呢/我在/来了/有什么可以帮您), so the TTS text alone can't tell the two apart. Make the click
#     path return a fixed marker «Слушаю»: it is shown on screen as-is and PiperCaTts (RuBridge.
#     isClickWakeMarker) plays the wake chime for it instead of speech. Voice wake is untouched. ---
if WAKE_CHIME:
    tm=f"{SM6}/com/incall/apps/speechassistant/tips/TipsManager.smali"
    s=open(tm,encoding='utf-8').read()
    m=re.search(r'(\.method public getWakeUpTipsFromClick\(\)Ljava/lang/String;\n\s*\.registers \d+\n)', s)
    if not m: print("[patch] WARN: TipsManager.getWakeUpTipsFromClick not found")
    else:
        marker="".join("\\u%04x"%ord(c) for c in "Слушаю")
        s=s[:m.end()]+f'\n    const-string v0, "{marker}"\n    return-object v0\n'+s[m.end():]
        open(tm,'w',encoding='utf-8').write(s)
        print("[patch] WAKE_CHIME marker -> TipsManager.getWakeUpTipsFromClick")
    # The REAL steering-wheel key path (verified on the car): CaCarManager$4 "keycode == 231
    # com.incall.action.KEY_CLICK" -> BusinessController.changeWakeupToLeft(10) -> playWakeUpTips(type=10)
    # -> the hard-coded literal 主驾请说 (TipsConst.KEY_WAKEUP_TIPS, inlined). Swap that literal for the marker.
    tp=f"{SM6}/com/incall/apps/speechassistant/tips/TipsPlayer.smali"
    s=open(tp,encoding='utf-8').read()
    m=re.search(r'\.method public static playWakeUpTips\(IILjava/lang/String;\)Ljava/lang/String;.*?\.end method', s, re.S)
    if not m: print("[patch] WARN: TipsPlayer.playWakeUpTips not found")
    else:
        body=m.group(0); lit=r'"\\u4e3b\\u9a7e\\u8bf7\\u8bf4"'   # 主驾请说
        marker="".join("\\u%04x"%ord(c) for c in "Слушаю")
        nb, cnt = re.subn(r'(const-string(?:/jumbo)? (?:p|v)\d+, )'+lit, lambda mm: mm.group(1)+'"'+marker+'"', body)
        if cnt==0: print("[patch] WARN: 主驾请说 literal not found in playWakeUpTips")
        else:
            s=s[:m.start()]+nb+s[m.end():]; open(tp,'w',encoding='utf-8').write(s)
            print(f"[patch] WAKE_CHIME marker -> TipsPlayer.playWakeUpTips type=10 literal ({cnt})")

# --- BRIDGE native tap: hook SrBaseSession ASR-audio callback -> RuBridge.feed ---
if BRIDGE:
    srb=f"{SM6}/com/incall/apps/speechassistant/sr/SrBaseSession.smali"
    s=open(srb,encoding='utf-8').read()
    # feed our recognizer; with NO_CN_SR also return-void here so the rest of the handler
    # (SpeechInterfaceImpl.sendSpeechData -> iFlytek SR, AiBoxManager.sendAsrRecord -> cloud,
    #  saveAsrData) never runs: no Mandarin recognition, no upload. wp(1/3) comes from the SE/MS
    #  front-end into our feed, so wake + endpoint still work.
    hook=("\n    invoke-static/range {p0 .. p6}, Lcom/stand/bridge/RuBridge;->feed(Ljava/lang/Object;IIJ[BI)V\n"
          + ("    return-void\n" if NO_CN_SR else ""))
    m=re.search(r'(\.method synthetic lambda\$registerAsrDataCallBack\$5\$com-incall-apps-speechassistant-sr-SrBaseSession\(IIJ\[BI\)V\n\s*\.registers \d+\n)', s)
    assert m, "SrBaseSession ASR callback not found"
    s=s[:m.end()]+hook+s[m.end():]
    # NO_CN_ENGINE: never start the iFlytek IAT engine. private startRecognize(IJ)I only calls
    # SrEngineProxy.start(IJ) (loads the Mandarin acoustic model + opens a decode session, incl. the
    # always-on "full time offline" path). No-op it (return 0) so the Chinese engine never loads/decodes.
    # The SE mic front-end (recorder_thread, com.incall.voicecore.se.IAudioDataListener) is registered
    # separately (registerAsrDataCallBack) and runs full-time, so our RuBridge.feed keeps getting PCM+wp.
    if NO_CN_ENGINE:
        m_sr=re.search(r'(\.method private startRecognize\(IJ\)I\n\s*\.registers \d+\n)', s)
        assert m_sr, "private startRecognize(IJ)I not found"
        skip="    const/4 p0, 0x0\n    return p0\n"
        s=s[:m_sr.end()]+skip+s[m_sr.end():]
    # NO_CN_SR: drop NATIVE SR results at the SR result listener. The iFlytek engine still decodes
    # Mandarin (it shares the SE audio hub IICaSr we tap for GigaAM, so we can't starve it), and its
    # result reaches the pipeline here via processVts()/DuplicateWakeUpManager — a path our
    # NluManager.onArbitrationResult guard does NOT cover, so Chinese results were interfering. Our own
    # ASR bypasses this listener (injectZh -> NluManager.onFinalAsrResult directly), so dropping every
    # non-ours SrBean here kills the Chinese interference without touching audio or our path.
    if NO_CN_SR:
        m_rl=re.search(r'(\.method synthetic lambda\$createSrResultListener\$2\$com-incall-apps-speechassistant-sr-SrBaseSession\(Lcom/incall/apps/voiceservice/sr/bean/SrBean;\)V\n\s*\.registers \d+\n)', s)
        assert m_rl, "lambda$createSrResultListener$2 not found"
        rl_guard=("    invoke-virtual {p1}, Lcom/incall/apps/voiceservice/sr/bean/SrBean;->getRequestId()Ljava/lang/String;\n"
                  "    move-result-object v0\n"
                  "    invoke-static {v0}, Lcom/stand/bridge/RuBridge;->isOurs(Ljava/lang/String;)Z\n"
                  "    move-result v0\n"
                  "    if-nez v0, :stand_rl_ours\n"
                  "    return-void\n"
                  "    :stand_rl_ours\n")
        s=s[:m_rl.end()]+rl_guard+s[m_rl.end():]
    open(srb,'w',encoding='utf-8').write(s)
    # SrPgsManager.appendPgs: replace displayed text with our RU text
    spm=f"{SM6}/com/incall/apps/voiceservice/sr/SrPgsManager.smali"
    s2=open(spm,encoding='utf-8').read()
    swap=("\n    if-eqz p2, :stand_skip\n"
          "    iget-object v0, p2, Lcom/incall/apps/voiceservice/sr/bean/PgsBean;->text:Ljava/lang/String;\n"
          "    invoke-static {v0}, Lcom/stand/bridge/RuBridge;->swap(Ljava/lang/String;)Ljava/lang/String;\n"
          "    move-result-object v0\n"
          "    iput-object v0, p2, Lcom/incall/apps/voiceservice/sr/bean/PgsBean;->text:Ljava/lang/String;\n"
          "    :stand_skip\n")
    m2=re.search(r'(\.method public appendPgs\(ILcom/incall/apps/voiceservice/sr/bean/PgsBean;\)V\n\s*\.registers \d+\n)', s2)
    assert m2, "appendPgs not found"
    s2=s2[:m2.end()]+swap+s2[m2.end():]
    open(spm,'w',encoding='utf-8').write(s2)
    # CloudNlu.getCloudNluResult(zone, reqId, query): replace query (p3) with our RU text,
    # so the native cloud dialog (Dubhe) receives Russian and answers via the native pipeline.
    # CAPTURE diagnostic: skip the swap so the authentic injected ZH reaches the REAL Dubhe server
    # (needs NATIVE_CLOUD=1 so endpoints aren't redirected); the raw response is logged at level i.
    if not CAPTURE:
        cnu=f"{SM6}/com/incall/apps/speechassistant/nlu/CloudNlu.smali"
        s3=open(cnu,encoding='utf-8').read()
        qswap=("\n    invoke-static {p3}, Lcom/stand/bridge/RuBridge;->swap(Ljava/lang/String;)Ljava/lang/String;\n"
               "    move-result-object p3\n")
        m3=re.search(r'(\.method private getCloudNluResult\(ILjava/lang/String;Ljava/lang/String;\)Ljava/lang/String;\n\s*\.registers \d+\n)', s3)
        assert m3, "getCloudNluResult not found"
        s3=s3[:m3.end()]+qswap+s3[m3.end():]
        open(cnu,'w',encoding='utf-8').write(s3)
    # NluManager.onArbitrationResult: drop NATIVE results (Chinese), keep only ours (requestId=stand*)
    nmg=f"{SM6}/com/incall/apps/speechassistant/nlu/NluManager.smali"
    s4=open(nmg,encoding='utf-8').read()
    guard=("\n    invoke-static {p1}, Lcom/stand/bridge/RuBridge;->isOurs(Ljava/lang/String;)Z\n"
           "    move-result v0\n"
           "    if-nez v0, :stand_ours\n"
           "    return-void\n"
           "    :stand_ours\n"
           # detect an unresolved injected command (domain=unknown/UNDEFINED) -> LLM fallback (collect)
           "    invoke-static {p1}, Lcom/stand/bridge/RuBridge;->checkArbResult(Ljava/lang/String;)V\n")
    m4=re.search(r'(\.method private onArbitrationResult\(Ljava/lang/String;\)V\n\s*\.registers \d+\n)', s4)
    assert m4, "onArbitrationResult not found"
    s4=s4[:m4.end()]+guard+s4[m4.end():]
    open(nmg,'w',encoding='utf-8').write(s4)
    # Localize rotating UI hints: GuideWordsStore.getSleepText/getSrText -> Russian TextColorBean
    gws=f"{SM6}/com/incall/apps/speechassistant/guide/GuideWordsStore.smali"
    TCB="Lcom/incall/apps/speechassistant/guide/TextColorBean;"
    repl(gws, "getSleepText(Ljava/lang/String;)"+TCB,
         "    .registers 3\n"
         "    invoke-static {}, Lcom/stand/bridge/RuBridge;->ruGuideSleep()Ljava/lang/Object;\n"
         "    move-result-object v0\n"
         f"    check-cast v0, {TCB}\n"
         "    return-object v0")
    repl(gws, "getSrText(Ljava/lang/String;I)"+TCB,
         "    .registers 4\n"
         "    invoke-static {}, Lcom/stand/bridge/RuBridge;->ruGuideSr()Ljava/lang/Object;\n"
         "    move-result-object v0\n"
         f"    check-cast v0, {TCB}\n"
         "    return-object v0")

# --- classes6: VoiceApp.onCreate inject receiver + (emu) swallow ---
va=f"{SM6}/com/incall/apps/speechassistant/application/VoiceApp.smali"
s=open(va,encoding='utf-8').read()
inj=""
if BRIDGE:
    inj+=("\n    invoke-static {p0}, Lcom/stand/bridge/RuBridge;->init(Landroid/content/Context;)V\n")
if SWALLOW:
    inj+=("\n    new-instance v0, Lcom/incall/apps/speechassistant/application/CrashSwallow;\n"
          "    invoke-direct {v0}, Lcom/incall/apps/speechassistant/application/CrashSwallow;-><init>()V\n"
          "    invoke-static {v0}, Ljava/lang/Thread;->setDefaultUncaughtExceptionHandler(Ljava/lang/Thread$UncaughtExceptionHandler;)V\n")
if TRIG:
    inj+=("\n    new-instance v0, Lcom/stand/bridge/StandNluReceiver;\n"
          "    invoke-direct {v0}, Lcom/stand/bridge/StandNluReceiver;-><init>()V\n"
          "    new-instance v1, Landroid/content/IntentFilter;\n"
          "    const-string v2, \"com.stand.NLU\"\n"
          "    invoke-direct {v1, v2}, Landroid/content/IntentFilter;-><init>(Ljava/lang/String;)V\n"
          "    const/4 v2, 0x2\n"
          "    invoke-virtual {p0, v0, v1, v2}, Landroid/content/Context;->registerReceiver(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;I)Landroid/content/Intent;\n")
if inj:
    m=re.search(r'(\.method public onCreate\(\)V\n.*?invoke-super \{p0\}, Landroid/app/Application;->onCreate\(\)V\n)', s, re.S)
    assert m, "onCreate not found"
    s=re.sub(r'(\.method public onCreate\(\)V\n\s*\.registers )\d+', lambda mm:mm.group(1)+"5", s, count=1)
    s=s[:m.end()]+inj+s[m.end():]
    open(va,'w',encoding='utf-8').write(s)

print("[patch] host=%s (all dubhe+ASR+tracer redirected/neutralized) swallow=%s ru_asr=%s trigger=%s"%(HOST,SWALLOW,RU,TRIG))
PY

java -jar "$PROJ_DIR/tools/smali.jar" a --api 34 "$wd/smali5" -o "$wd/classes5.dex"
java -jar "$PROJ_DIR/tools/smali.jar" a --api 34 "$wd/smali6" -o "$wd/classes6.dex"
cp "$SRC" "$wd/_p.apk"; ( cd "$wd" && zip -j -q _p.apk classes5.dex classes6.dex )
if [ "${BRIDGE:-0}" = "1" ]; then
  VDIR="$STAND_DIR/asr-android"
  echo "[build_sa] +BRIDGE: classes7.dex (RuBridge + GigaAM ASR + TeraTTS)"
  cp "$VDIR/build/dex7/classes.dex" "$wd/classes7.dex"
  ( cd "$wd" && zip -j -q _p.apk classes7.dex )
fi

# --- PIPER (repurposed): now ONLY the shared native JNI libs — libsherpa-onnx-jni.so (GigaAM ASR)
#     + libonnxruntime4j_jni.so (TeraTTS). Piper RU TTS model dropped (TeraTTS is the sole voice). ---
if [ "${PIPER:-0}" = "1" ]; then
  PDIR="$STAND_DIR/asr-android/piper"
  echo "[build_sa] +JNI: sherpa-onnx + onnxruntime4j libs (no Piper model — TeraTTS is the voice)"
  mkdir -p "$wd/lib/arm64-v8a"
  cp "$PDIR/jni/arm64-v8a/"*.so "$wd/lib/arm64-v8a/"
  ( cd "$wd" && zip -q -0 -r _p.apk lib )
fi

# --- TERA: TeraTTS ru_f2 neural TTS assets (~370MB) -> assets/tera. Needs BRIDGE=1 (classes7 has TeraTTS)
#     and PIPER=1 (bundles libonnxruntime4j_jni.so from piper/jni). Uses stock libonnxruntime.so. ---
if [ "${TERA:-0}" = "1" ]; then
  TSRC="$PROJ_DIR/tools/tera-tts-java/assets"
  echo "[build_sa] +TERA: TeraTTS ru_f2 assets (~370MB, stored)"
  rm -rf "$wd/assets/tera"; mkdir -p "$wd/assets/tera"
  cp -r "$TSRC/models" "$TSRC/styles" "$TSRC/unicode_indexer.json" "$wd/assets/tera/"
  [ -f "$TSRC/ruaccent.bin" ] && cp "$TSRC/ruaccent.bin" "$wd/assets/tera/"   # frequency accent dict (~2MB)
  find "$wd/assets/tera" -name '.DS_Store' -delete
  ( cd "$wd" && zip -q -0 -r _p.apk assets/tera )
fi

# --- GIGAAM: GigaAM CTC offline ASR (sherpa-onnx) -> assets/gigaam (~224MB int8, stored).
#     The recognizer (RuBridge.ENGINE_GIGAAM=true). Needs BRIDGE=1 (classes7 has
#     GigaAsr + the smali feed tap) and PIPER=1 (bundles libsherpa-onnx-jni.so from piper/jni).
#     GIGAAM_ML=1 ships GigaAM-Multilingual (ru/kk/ky/uz/en, asr-android/gigaam-ml/) instead of the
#     Russian-only v3 (asr-android/gigaam/). Same runtime, same asset path; GigaAsr tells them apart
#     by the vocabulary in tokens.txt and routes non-Russian phrases to the backend. ---
if [ "${GIGAAM:-0}" = "1" ]; then
  if [ "${GIGAAM_ML:-0}" = "1" ]; then
    GDIR="$STAND_DIR/asr-android/gigaam-ml"
    echo "[build_sa] +GIGAAM: GigaAM-Multilingual CTC model ru/kk/ky/uz/en (~225MB, stored)"
  else
    GDIR="$STAND_DIR/asr-android/gigaam"
    echo "[build_sa] +GIGAAM: GigaAM-v3 CTC model, Russian (~224MB, stored)"
  fi
  [ -f "$GDIR/model.int8.onnx" ] || { echo "[build_sa] missing $GDIR/model.int8.onnx (see MODELS.md)"; exit 1; }
  rm -rf "$wd/assets/gigaam"; mkdir -p "$wd/assets/gigaam"
  cp "$GDIR/model.int8.onnx" "$GDIR/tokens.txt" "$wd/assets/gigaam/"
  ( cd "$wd" && zip -q -0 -r _p.apk assets/gigaam )
fi
# --- WAKE_CHIME: the key-wake chime (stock res/raw/sr.mp3 converted to 24 kHz mono 16-bit WAV, ~32KB)
#     -> assets/stand/wake_chime.wav (WakeChime loads it; falls back to res/raw/wakeup.wav if absent).
if [ "${WAKE_CHIME:-1}" = "1" ] && [ -f "$STAND_DIR/asr-android/assets/wake_chime.wav" ]; then
  mkdir -p "$wd/assets/stand"; cp "$STAND_DIR/asr-android/assets/wake_chime.wav" "$wd/assets/stand/wake_chime.wav"
  ( cd "$wd" && zip -q _p.apk assets/stand/wake_chime.wav )
  echo "[build_sa] +wake chime: assets/stand/wake_chime.wav"
fi

# --- LICENSE/NOTICE: bundle the legal notice into the APK (assets/NOTICE.txt) so it ships in the
#     published binary and is visible on unpack. Copyright (c) 2026 Tecrow, PolyForm Noncommercial 1.0.0.
if [ -f "$STAND_DIR/release/NOTICE.txt" ]; then
  mkdir -p "$wd/assets"; cp "$STAND_DIR/release/NOTICE.txt" "$wd/assets/NOTICE.txt"
  ( cd "$wd" && zip -q _p.apk assets/NOTICE.txt )
  echo "[build_sa] +NOTICE: assets/NOTICE.txt (license notice bundled)"
fi

"$ZIPALIGN" -p -f 4 "$wd/_p.apk" "$wd/_a.apk"
"$APKSIGNER" sign --key "$PLATFORM_PK8" --cert "$PLATFORM_CERT" --out "$OUT" "$wd/_a.apk"
rm -f "$wd/_p.apk" "$wd/_a.apk"
echo "[build_sa] built: $OUT"
