# Q07 русификация ассистента — план установки и отката

Собранный APK: speechassistant-ru-q07-test.apk (733 МБ)
MD5: 30527f8c800fe793025a340406b4d202
Подпись: AOSP test-keys (валидна для Q07, т.к. прошивка подписана теми же ключами)

## ЧТО ВНУТРИ
- Хуки RuBridge в штатный SpeechAssistant.apk (наш Q07, 216 МБ):
  1. VoiceApp.onCreate -> RuBridge.init + StandNluReceiver (отладка)
  2. SrSession.appendAudioData -> GigaAsr.accept (офлайн РУ распознавание PCM)
  3. NluManager.onArbitrationResult -> фильтр «только наши результаты»
  4. TtsPlayer.start / TtsPlayer2.start -> RuBridge.onTtsText (русский голос)
  5. CommonConfig.getDialogUrl -> 127.0.0.1 (облако выключено, офлайн)
  6. TipsManager.getWakeUpTipsFromClick -> маркер «Слушаю» (wake chime)
- classes7.dex (RuBridge + Ru2Zh маппер 240 интентов + GigaAM ASR + TeraTTS)
- JNI: libsherpa-onnx-jni.so + libonnxruntime4j_jni.so
- Модели: GigaAM int8 (224МБ) + TeraTTS (4 модели) + wake_chime.wav

## ПЕРЕД УСТАНОВКОЙ (страховка)
1. Подключитесь по Wi-Fi:
   adb connect <IP>:5555
   adb root
2. Снять ШТАТНЫЙ APK (если ещё не сняли):
   adb shell pm path com.incall.apps.speechassistant
   adb pull /system/app/SpeechAssistant/SpeechAssistant.apk ./SpeechAssistant_orig.backup.apk
   (у нас уже есть our_SpeechAssistant_216.apk — ЭТО ОН)
3. Запомнить версию (на случай отката):
   adb shell dumpsys package com.incall.apps.speechassistant | grep versionName

## УСТАНОВКА (два способа)

### Способ 1 — как обновление (предпочтительно, откат = pm uninstall)
   # push по частям не нужен: 733МБ по Wi-Fi долго (~10-20 мин), но терпимо
   adb push speechassistant-ru-q07-test.apk /data/local/tmp/sa_q07.apk
   adb shell pm install -r -d -g -t /data/local/tmp/sa_q07.apk
   adb shell am force-stop com.incall.apps.speechassistant

### Способ 2 — замена в /system (полный контроль, откат = замена обратно)
   ТОЛЬКО если pm install не пройдёт (SDK/подпись):
   adb push speechassistant-ru-q07-test.apk /data/local/tmp/sa_q07.apk
   adb shell "mount -o remount,rw /"
   adb shell "cp /system/app/SpeechAssistant/SpeechAssistant.apk /system/app/SpeechAssistant/SpeechAssistant.apk.bak_q07mod"
   adb shell "cp /data/local/tmp/sa_q07.apk /system/app/SpeechAssistant/SpeechAssistant.apk"
   adb shell "chmod 644 /system/app/SpeechAssistant/SpeechAssistant.apk"
   adb shell "rm -rf /data/dalvik-cache /data/system/package_cache"
   adb reboot

## ПЕРВЫЙ ЗАПУСК
- После установки первый запуск ~1 минута (распаковка моделей ~600МБ).
- Проверить: «нихао сяоань» или кнопка на руле -> «включи обогрев руля»,
  «открой окно водителя», «сделай температуру 22», «выключи кондиционер».
- Лог проверки (RU узнал фразу):
  adb logcat -s RuBridge GigaAsr | grep -iE "recognized|ru2zh|inject|result"

## ОТКАТ (если что-то не так)
### Способ 1 (если ставили pm install):
   adb shell pm uninstall com.incall.apps.speechassistant
   adb reboot
### Способ 2 (если меняли /system):
   adb root
   adb shell "mount -o remount,rw /"
   adb shell "cp /system/app/SpeechAssistant/SpeechAssistant.apk.bak_q07mod /system/app/SpeechAssistant/SpeechAssistant.apk"
   adb shell "chmod 644 /system/app/SpeechAssistant/SpeechAssistant.apk"
   adb shell "rm -rf /data/dalvik-cache /data/system/package_cache"
   adb reboot

## ЧТО ДЕЛАТЬ ЕСЛИ АССИСТЕНТ УМЕР
- НЕ паниковать: откат выше возвращает всё на место (проверено на A06-стоке).
- Полный бэкап у нас уже есть: /data/local/tmp/speechassistant_full_backup_20260905_225952

## ОЖИДАЕМЫЙ РЕЗУЛЬТАТ
- Ассистент понимает РУССКИЙ офлайн, отвечает РУССКИМ голосом.
- Китайское распознавание отключено (NO_CN_SR эквивалент: фильтр в NluManager).
- Облако Changan недостижимо (127.0.0.1) — всё офлайн, приватно.
