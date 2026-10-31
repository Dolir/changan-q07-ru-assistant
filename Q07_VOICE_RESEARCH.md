# Q07 Голосовой ассистент — ресёрч архитектуры аудио-конвейера

Дата: 2026-09-12. Обновлено: 2026-09-13. Машина: Qiyuan Q07 (C798), Android 11, build SWC.96.9933.

## Цель
Портировать русификацию голосового ассистента из changan-a06-ru-assistant (A06/C390)
на Q07/C798. На A06 мод встраивается Java-smali-хуком в `SrBaseSession`, на Q07 это
не работает — потребовался полный ресёрч реального пути аудио.

## Методы
1. Распаковка SpeechAssistant.apk (34 dex) через baksmali.
2. Анализ smali всех кандидатов.
3. Live-след logcat при произнесении команд (сток и мод).
4. Ghidra-декомпиляция native `libisssr.so` (JNI-функция appendAudioData).
5. Итеративная сборка мода (build_q07.sh) + установка подменой /system APK.

## АРХИТЕКТУРА Q07 (уточнено 2026-09-13)

```
микрофон
  → PcmRecorder (AudioRecord.read)            [лог "Reading 5s [1280000] bytes", 16кГц 8ch s16le]
      → SeEngineProxy.process([BI)I           ← ✅ РАБОЧАЯ ТОЧКА ХУКА (classes14)
          [вход SE/AEC; вызывается ВСЕГДА в живом потоке]
          → native LibISSSE2_process (libissse.so)   [AEC, шумоподавление]
              → (нативный внутренний downmix) → libisssr / ISSSRAppendAudioData
```

ВАЖНО (исправление ранней гипотезы): downmix 8ch→mono делаем МЫ, в Java-хуке на
`SeEngineProxy.process`. Java-метод `SeCallBack.processMsrData` в живом потоке
НЕ вызывается. Формат PCM: 16 кГц, **8 каналов** (SettingsUtil.getChannelNum()==0x8),
16-bit LE interleaved, шаг кадра 16 байт.

## Ghidra-подтверждение (libisssr.so)

```c
int Java_com_iflytek_speech_libisssr_appendAudioData(JNIEnv* env, jobject, jbyteArray data, jint len) {
    if (g_handle == 0) return 10000;
    void* pcm = GetByteArrayElements(env, data, 0);   // JNI: PCM байты
    int ret = ISSSRAppendAudioData(g_handle, pcm, len); // native SR вход
    ReleaseByteArrayElements(env, data, pcm, 2);
    return ret;
}
```
Вывод: native SR получает PCM массивом байтов через JNI. Значит Java-хук ВОЗМОЖЕН —
нужно только найти правильный Java-метод, который вызывается в живом сценарии.

## Какие точки проверяли (итог 2026-09-13)

| Точка | Статус |
|---|---|
| `IssSrImpl.appendAudioData` | ❌ не вызывается в живом потоке |
| `SREngine.appendAudioData` | ❌ не вызывается |
| `ISSSeImpl.processPcmData` | ❌ не вызывается |
| `SeSession$2.appendData` | ❌ не вызывается |
| `SrSession.appendAudioData` | ❌ только из тестов |
| `PcmRecorder.read` | ⚠️ вызывается, но 8ch сырьё / редкий буфер 5 с |
| `SeCallBack.processMsrData` | ❌ не вызывается (ранняя ошибочная гипотеза) |
| **`SeEngineProxy.process([BI)I`** | ✅ **ВЫЗЫВАЕТСЯ всегда — здесь и хукаем** (downmix 8ch→mono сами) |

## Безопасность native-патчей (не понадобились)

- Патчить копию .so, оригинал → .bak (+ полный бэкап на ПК)
- Откат = cp одной командой
- Риск минимален (библиотека в /system/app/.../lib/arm64/, не ядро/загрузчик)
- Найденная Java-точка сделала native-патч НЕНУЖНЫМ.

## Итог
Русское распознавание (GigaAM) требует моно 16кГц PCM. На Q07 правильный хук —
`SeEngineProxy.process([BI)I`: делаем downmix 8ch→mono в Java и кормим
`GigaAsr.accept`. Формат GigaAM: setSampleRate(0x3e80=16кГц), feature_dim 0x40=64.
(Ранняя гипотеза про `SeCallBack.processMsrData` опровергнута — см. таблицу выше.)

---

# ОБНОВЛЕНИЕ 2026-09-13: РАБОЧАЯ ЦЕПОЧКА НАЙДЕНА

## Что сработало (подтверждено логами)

Итоговая рабочая конфигурация хуков в `build_q07.sh` (все 10):

1. **PCM → GigaAM**: хук в `SeEngineProxy.process([BI)I` (classes14):
   downmix **8ch→mono** (getChannelNum()=0x8, шаг 16 байт) + `GigaAsr.accept(v4, v6)`.
   Признак работы: лог `DMXACCEPT: hook-ok` (спамит постоянно).
2. **finish**: хук в `SrSession.stopRecognize()I` (classes26):
   `RuBridge.feed(null, 0, 0, 3L, null, 0)` — КРИТИЧНО: **3 в LONG (v3/v4)**, не в int!
   (feed передаёт в feedGigaam только p0,p3,p4,p5 — int игнорируется).
   Признак: лог `FEED3: call` + `GigaAsr: decoded N samples ... : <текст>`.
3. **reset**: хук в `SrSession.startRecognize(IZ)I` → `GigaAsr.reset()` при p2!=0.
4. Остальные: NluManager-фильтр isOurs, TtsPlayer/TtsPlayer2.onTtsText,
   CommonConfig.getDialogUrl→127.0.0.1, TipsManager маркер, PgsController.swap (UI).

## Подтверждённая рабочая цепочка (лог 02:26-02:27)

```
RuBridge: RU ASR (GigaAM): могу пвыключи кондиционер     ← GigaAM распознал русский!
RuBridge: ru2zh: [могу пвыключи кондиционер] -> 关闭制冷模式
RuBridge: injectZh sent (zone 1) reqId=standzh-...: 关闭制冷模式
AINLU: nlu query is:关闭制冷模式 ret: {domain:carControl, intent:SET_AIR_CONDITIONER_MODE, action:CLOSE, mode:AC}
ArbitrationManager: sourceType: localConfidence
ExternalAdapter.dispatch → startService register_service  ← команда ушла на исполнение!
```

Другой прогон (лог 02:40):
```
02:40:44.170 I/GigaAsr: decoded 290752 samples (18172ms) in 2821ms: выключи кондиционер выключи кондиционер
02:40:44.189 I/RuBridge: injectZh sent (zone 1) reqId=standzh-1789249244180: 关闭制冷模式
```

GigaAM РАСПОЗНАЁТ русскую речь. Примеры из логов: «выключи кондиционер»,
«открой окно закрой окно», «открыть переднее пассажирское окно», «что ты еще умеешь».

## Осталось дошлифовать (известные проблемы)

1. **Задержка выключения ассистента кнопкой руля — ПОДТВЕРЖДЕНА пользователем**
   («выключение через кнопки работает, но с задержкой в несколько секунд, как будто
   заторможена»). Причина: `GigaAsr.finish()` (decode) выполняется СИНХРОННО внутри
   хука `SrSession.stopRecognize()`, а буфер к тому моменту содержит 8–18 секунд аудио:
   лог `decoded 290752 samples (18172ms) in 2821ms` — decode блокирует путь выключения
   почти на 3 секунды. Фикс: асинхронный decode + чистка буфера (см. шаги ниже).
2. **Буфер аккумулируется между сессиями** — decode по 8–18 секунд аудио, включая
   TTS-эхо ассистента ("могу п" — эхо "Чем могу помочь") и тишину. Следствия:
   пустые decode (`decoded 135744 samples (8484ms) in 860ms: ` — текст пуст),
   мусорные распознавания («нига», «кооет» → «Не расслышала. Повторите, пожалуйста»).
   Причина: `SeEngineProxy.process` вызывается НЕПРЕРЫВНО, `accept` копит всё с момента
   последнего `reset()`. Фикс: флаг «сессия активна» + лимит буфера (последние ~10 сек).
3. **`forceWakeIfAsleep` падает с ClassNotFoundException на Q07** — RuBridge ищет
   `com.incall.apps.voicebase.consts.common.VoiceStateCache` (путь A06), в Q07 класса нет.
   Вызывается в `injectZh` с комментарием "injected command must execute past the
   free-wake gate": если этот гейт реально блокирует исполнение — это объясняет
   «распознано, но не выполнено». Проверить в первую очередь.
4. **RU-fallback TTS не работает на Q07** — `RuBridge.speak` рефлексией зовёт
   `com.changan.speech.tts.GlobalTtsClient`, которого на Q07 НЕТ (`ClassNotFoundException`),
   поэтому сгенерированные модом ответы («Не расслышала. Повторите, пожалуйста.»)
   могут не озвучиваться. Штатный путь (`TtsPlayer` + `RuBridge.onTtsText`) работает —
   именно он переводит ответы ZH→RU (`TTS ZH->RU: [我在] -> Чем могу помочь`).
5. **NoSuchMethodException GlobalTtsClient.onStartState** — косметика (только лог).
6. **Расхождение «логи vs опыт пользователя»** — в логах русская речь распознаётся
   (GigaAM: «выключи кондиционер», «открой окно закрой окно», «открыть переднее
   пассажирское окно»), ru2zh → injectZh → NLU (SET_AIR_CONDITIONER_MODE) → арбитраж
   (localConfidence); для окон был `ExternalAdapter.dispatch`. При этом пользователь
   по своим тестам сообщает: «ассистент по-прежнему не понимает русский» (уточнение
   пользователя: «я просто пару раз на китайском сказал» — часть тестов шла
   по-китайски, что могло давать «успешные» цепочки по стоковому пути). Расхождение
   НЕ разрешено — нужен чистый тест со снятием полного следа до физического действия.

## СЛЕДУЮЩИЕ ШАГИ (для следующей итерации)

> СТАТУС на 2026-09-13 02:57: мод ОТКАЧЕН на сток (инструкция ниже УЖЕ ВЫПОЛНЕНА),
> ГУ перезагружено. Для продолжения работ — пересобрать мод с фиксами ниже и поставить заново.

1. **Проверить исполнение (главное)**: русская команда → полный след до
   `ExternalAdapter.dispatch` и физического действия; разобраться с free-wake gate (п.3).
2. **Убрать задержку выключения**: `feed(3)`/decode — в фоновый поток, кнопка руля
   должна гасить ассистента мгновенно. Варианты: (а) правка smali `RuBridge.feedGigaam`
   (ветка wp==3 в `new Thread`); (б) пересборка `classes35.dex` из исходников
   (`stand/asr-android`, шаг BRIDGE в build_sa.sh).
3. **Чистка буфера GigaAM**: флаг «сессия активна» (ставить в `startRecognize`,
   снимать после `finish()`), не копить TTS-эхо и тишину, лимит ~10 сек.
4. **Ускорить decode**: обрезка тишины по краям (VAD) вместо декодирования всего буфера.
5. **Меньше ждать облако** для известных ru2zh-команд (`confident=true`) — срезать
   `wait cloud timeout` ≈ 2 с.
6. **Fallback-озвучку** направить через штатный `TtsPlayer` (а не отсутствующий
   `com.changan.speech.tts.GlobalTtsClient`).

---

# СБОРКА И УСТАНОВКА МОДА (Q07)

## Сборка (на ПК)

```bash
cd changan-a06-ru-assistant-main
bash build_q07.sh <stock.apk> <v1.0.3.apk> <out.apk>
# stock.apk   — our_SpeechAssistant_216.apk (штатный Q07)
# v1.0.3.apk  — APK мода A06 (источник classes35.dex: RuBridge+GigaAsr+TeraTTS)
# out.apk     — результат (dex-хуки + classes35 + lib/arm64-v8a + assets gigaam/tera/stand,
#               zipalign, подпись test-keys)
```

Скрипт (7 шагов): baksmali всех 34 dex → smali-хуки → smali назад в dex
(`classes33 26 24 23 20 21 19 18 14 11`) → + `classes35.dex` (RuBridge из v1.0.3;
в RU-APK он лежит как classes7.dex) → lib/arm64-v8a + assets (gigaam/tera/stand)
→ сборка APK → zipalign → подпись test-keys.

## Установка (подмена файла в /system — Vecentek блокирует pm install)

```bash
adb connect <IP>:5555
adb root
adb push sa_q07.apk /data/local/tmp/            # ~730 МБ, по Wi-Fi 8–19 мин
adb shell md5sum /data/local/tmp/sa_q07.apk     # сверить с локальным
adb shell "mount -o remount,rw /"
adb shell "cp /data/local/tmp/sa_q07.apk /system/app/SpeechAssistant/SpeechAssistant.apk"
adb shell "chmod 644 /system/app/SpeechAssistant/SpeechAssistant.apk"
adb shell "rm -rf /data/dalvik-cache/arm64/*SpeechAssistant*"
adb reboot
```

Замечания:
- **Обязателен полный reboot** — иначе `VoiceApp.onCreate` не переиграется и мод не активируется.
- JNI .so в APK должны быть НЕСЖАТЫМИ (`7z a -tzip -mx=0`), иначе
  `INSTALL_FAILED_INVALID_APK: Failed to extract native libraries, res=-2`.
- Наш RuBridge dex должен лежать как `classes35.dex` — в стоке Q07 `classes7.dex`
  занят (перезапись валит `NoClassDefFoundError: LocationController`).
- В Git Bash для adb push/pull — `MSYS_NO_PATHCONV=1` (иначе пути ломаются).
- Проверка активации: `adb shell "logcat -d | grep -E 'DMXACCEPT|FEED3|RuBridge' | head"`.

---

# ИНСТРУКЦИЯ: ОТКАТ НА СТОК (Q07)

## Что где лежит

| Файл | Где | Что |
|---|---|---|
| `stock_sa_20260912_003802.apk` | `/sdcard/` на ГУ | Штатный SpeechAssistant.apk (216MB) |
| `SpeechAssistant.apk.bak_mod*` | `/system/app/SpeechAssistant/` | Наши мод-версии (бэкапы шагов) |
| `our_SpeechAssistant_216.apk` | ПК: `%TEMP%/q07/` | Копия штатного APK |
| `sa_backup/` (51 .so + oat) | ПК: `%TEMP%/q07/` | Штатные native-lib ассистента |
| `native_lib_backup/arm64/` | ПК | Все 51 .so из /system |
| `/sdcard/native_lib_backup/` | ГУ | libisssr.so + libissse.so (бэкап) |

## Быстрый откат (мы ставили замeной /system APK)

```bash
adb connect <IP>:5555
adb root

# 1. Вернуть штатный APK (замена в /system)
adb shell "mount -o remount,rw /"
adb shell "cp /sdcard/stock_sa_20260912_003802.apk /system/app/SpeechAssistant/SpeechAssistant.apk"
adb shell "chmod 644 /system/app/SpeechAssistant/SpeechAssistant.apk"

# 2. Сбросить кэши, чтобы система перечитала APK
adb shell "rm -rf /data/dalvik-cache/arm64/*SpeechAssistant* /data/dalvik-cache/profiles/com.incall.apps.speechassistant"

# 3. Перезагрузить ГУ (обязательно — VoiceApp.onCreate иначе не инвалидируется)
adb reboot
```

После перезагрузки IP сменится — переподключиться по новому.

## Проверка, что откат прошёл

```bash
adb shell dumpsys package com.incall.apps.speechassistant | grep -E 'codePath|lastUpdateTime'
# Ожидаем: codePath=/system/app/SpeechAssistant, lastUpdateTime = заводская дата (2026-02-04)

adb shell "logcat -d | grep -iE 'RuBridge|DMXACCEPT|FEED3' | head"
# Ожидаем: пусто (наш мод не активен)
```

## Полный откат (если меняли native libs — libisssr.so / libissse.so)

```bash
adb root
adb shell "mount -o remount,rw /"
# Вернуть оригинальные .so из бэкапа на устройстве:
adb shell "cp /sdcard/native_lib_backup/libisssr.so /system/app/SpeechAssistant/lib/arm64/"
adb shell "cp /sdcard/native_lib_backup/libissse.so /system/app/SpeechAssistant/lib/arm64/"
adb shell "chmod 644 /system/app/SpeechAssistant/lib/arm64/libisssr.so /system/app/SpeechAssistant/lib/arm64/libissse.so"
adb reboot
```

## Откат, если ставили через pm install (data-слой)

```bash
adb shell "pm uninstall com.incall.apps.speechassistant"   # убирает data-слой
adb reboot
# /system остаётся нетронутым — вернётся штатная версия.
# ВНИМАНИЕ: если /system тоже заменён на мод — сначала шаг «Быстрый откат».
```

## Важные грабли (проверено)

- **Vecentek**: `pm install` может падать с `INSTALL_FAILED_INVALID_APK:
  Vecentek failed to verify APK result` — обходится заменой файла в /system
  (не через pm install).
- **data-слой перекрывает /system**: если раньше делали `pm install -r`,
  APK грузится из `/data/app/...`, а не из /system. Удалять data-слой
  (`rm -rf /data/app/*speechassistant*` или `pm uninstall`).
- **После замены APK обязателен reboot** — иначе процессы продолжат
  использовать старый dex из памяти.
- **IP ГУ меняется после каждой перезагрузки** (Wi-Fi ADB). Переподключаться:
  `adb connect <новый IP>:5555`.
- Наши хуки не трогают загрузчик/ядро/разделы QNX — риск «кирпича» отсутствует,
  откат всегда сводится к возврату одного APK-файла.
