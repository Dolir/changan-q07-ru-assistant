# UI-стенд: оболочка Changan + виджет ассистента на AAOS (полное UI-соответствие)

Цель: запустить настоящую оболочку Changan (`com.incall.apps.sdalauncher`) как HOME
на эмуляторе, с виджетом ассистента (`VpaView`/aiavpasdk) и **выводом ответа на экран**.
Достигнуто ✅ (см. `logs/shots/ui5.png`, `ui6.png`, `ui7.png`).

## Почему AAOS, а не телефонный `default`
Лаунчер — автомобильное приложение: `sharedUserId=android.uid.system`, `persistent=true`,
пермишены `android.car.permission.*`. Классы `android.car` должны быть на bootclasspath —
это есть только в **Android Automotive (AAOS)**. Поэтому база: `system-images;android-34-ext9;android-automotive;arm64-v8a` (Android 14 = как на авто).

## Ключевой блокер и его решение
AAOS-образ подписан **Google-ключами** (`framework-res` cert `301aa3cb…`), а наши apk — AOSP
**test-keys** (`c8a2e9bc…`). Установить наш лаунчер как `android.uid.system` нельзя
(`INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`). Решение: снять системность и ставить обычным
приложением, делать HOME отключением штатного CarLauncher.

## Сборка `launcher_norm.apk` (обычное приложение)
1. `apktool d -s -f ui_apps/launcher.apk -o stand/work/launcher`  (только манифест+ресурсы, dex сырые — быстро)
2. В `AndroidManifest.xml`: убрать `android:sharedUserId="android.uid.system"` и `android:persistent="true"`.
3. В `apktool.yml`: `targetSdkVersion: 34` → `33`  (снимает Android-14 требование флага
   `RECEIVER_EXPORTED/NOT_EXPORTED` у динамических ресиверов — иначе краш в `MainActivityVM.registerSuperEcoReceiver`).
4. `apktool b` → zipalign → подпись test-keys  (скрипт `stand/build_ui.sh launcher launcher_norm`).

## Smali-патч framework-mismatch (Changan-ROM скрытые API)
`LauncherWindowManager.getAppTopForDisplay` / `getAppTopVisibleForDisplay` зовут
`IActivityManager.getAllRootTaskInfos()` — есть в ROM Changan, нет в AOSP → `NoSuchMethodError`
(не ловится `catch Exception`). Патч: оба метода → сразу `return null` (`const/4 v0,0x0; return-object v0`).
Класс в `classes5.dex`. Быстрая подмена dex без полного apktool:
`stand/swapdex.sh stand/out/launcher_norm.apk launcher_norm classes5.dex`
(smali: `java -jar tools/smali.jar a --api 34 classes5_smali -o classes5.dex`; правленый smali в `ui_apps/launcher_dex/classes5_smali`).
Прочие ошибки (Glide ENOENT, WallPager set system property, CarManager на воркере,
SELinux find vendor-сервисов `ca.evs.app.service`/`CaConfigManagerService`/`DataCollect`) —
**некритичные**, проглатываются, лаунчер живёт.

## Запуск (репитабельно)
`stand/ui_stand.sh boot`  — поднять AAOS (окно)
`stand/ui_stand.sh up`    — поставить лаунчер+оверлей, сделать HOME (отключает CarLauncher)
`stand/ui_stand.sh text "любой русский текст"` — вывести в виджет ассистента
`stand/ui_stand.sh shot имя` — скриншот в `logs/shots/`
`stand/ui_stand.sh restore` — вернуть штатный CarLauncher

## Вывод ответа на экран — механизм (ГЛАВНОЕ)
Оболочка рисует текст ответа «печатной машинкой» `PgsView`/`PgsSwitcherView` (classes5),
которая слушает broadcast **`com.incall.action.UPDATE_TEXT`**, extra `"text"`.
(`VpaManager.showVoiceText`→`sendTextToVoice` в aiavpasdk шлёт именно его; приём `tvVpaContent`.)
→ Наш `RuBridge.showOnScreen(text)` делает `sendBroadcast(Intent("com.incall.action.UPDATE_TEXT").putExtra("text",…))`.
Вызывается в `sendToCloud` при получении ответа LLM. Работает и на стенде, и на авто.

## Все состояния/каналы виджета ассистента (разобрано по коду)
Источник: `NotifierUtil` (classes5) — фабрика всех broadcast'ов; `PgsSwitcherView`/`AutoScrollTextView`
рисуют текст, пакет `model_vpa` (`SpeechStateReceiver`,`VpsControl`) — аватар/VPA.

**Текстовая строка — один broadcast `com.incall.action.UPDATE_TEXT`** (`NotifierUtil.notifyTextChanged`).
Extras: `text`(CharSequence), `textList`(ArrayList — несколько строк), `colorList`(цвета сегментов,
`TextColorUtil.getFinalText`), `tips`, `direct`(позиция ALL0/LEFT1/RIGHT2/MID5/REAR..), `level`
(приоритет HIGH1/MED2/LOWER3/DEFAULT4/CLEAR9), `time`(автоскрытие, мс), `scroll`(bool), **`type`** =
состояние:
- `1` PGS — надиктовка (живой частичный ASR)
- `2` NLP — распознанная команда (эхо фразы)
- `3` GUIDE/DEFAULT — подсказки в покое (крутятся, `scroll=true`, `textList`)
- `4` FEEDBACK — ответ/реплика
- `5` FORCE, `6` OTHER/IMMEDIATE — форс/приоритетное
- `9` CLEAR — очистить
Демо: `ui_stand.sh state {guide|pgs|nlp|feedback|clear} [text]` · `ui_stand.sh demo` (все + скрины `logs/shots/st_*`).

**Аватар/VPA — отдельные каналы:**
- `com.incall.action.TTS_CONTENT` (`text`) — субтитр того, что СЕЙЧАС озвучивается. `ui_stand.sh tts "..."`
- `com.incall.action.VW_STATE` (`type`,`vwState`; permission `com.incall.permission.RECEIVE_VOICE`) — анимация аватара (покой/слушает/думает/говорит)
- `com.incall.action.SPEECH_STATE` (`state`) — общий стейт ассистента
- `com.incall.action.NLU_STATE` (`nluState`) — прогресс NLU
- `com.android.systemui.mic.status.change` (`isUse`,`isOutside`,`packageName`) — индикатор микрофона
- `com.incall.action.VIEW_VISIBLE` (`visible`) — показать/скрыть весь виджет. `ui_stand.sh hide|show`
- карточки картинка/видео (`iv_vpa_image/video` в `VpaView`) — сцен-протокол `com.changan.aiassist.message`
  (`VpaManager.receiveEvent` парсит JSON `{eventCode,extra}`) + провайдер `content://com.changan.aia_sharevpa/aiavpashare_tb`

**Наш пайплайн `RuBridge` → эти состояния** (обновлено): частичный результат ASR → `showOnScreen(p,TYPE_PGS)`;
финальная фраза → `showOnScreen(query,TYPE_NLP)`; ответ LLM → `showOnScreen(answer,TYPE_FEEDBACK)` +
`showTtsSubtitle(answer)`. Подсказки в покое — уже через хук `GuideWordsStore` ([[russian-assistant-full]]).

## RU-локализация
RRO-оверлей `ru.lang.incall.apps.sdalauncher` (test-keys = совпадает с нашим лаунчером):
`adb install launcher_ru_overlay.apk` → `cmd overlay enable ru.lang.incall.apps.sdalauncher`.
Строки виджета мы задаём сами (UPDATE_TEXT), поэтому русский там в любом случае.

## SpeechAssistant на стенде — результат и ГРАНИЦА
Собран `stand/out/sa_stand.apk` (снят shareduid=system, target→33, подпись test-keys — `build_ui.sh sa_stand sa_stand`).
Установка: `adb install -r -d -g sa_stand.apk` → `pm grant … WRITE_SECURE_SETTINGS`. Крашы боота:
- `no such table: role` (DataProvider/TtsRoleDbHelper `voice_role.db`) → создать таблицу. **ВАЖНО: SA бежит под user 10**,
  БД класть в `/data/user/10/com.incall.apps.speechassistant/databases/` (не `/data/data`), chown на uid. Схема — см. `speechassistant-boot.md`.
- Аватар (VPA) не регистрирует обсервер: `VpsControl.registerMainObserver`→`isNormalFont` кидает
  `SettingNotFoundException: font_scale` → фикс без патча: `adb shell settings put system font_scale 1.0`.

**ГРАНИЦА СТЕНДА (главное):** на AAOS **нет раздела `/resources`** прошивки Changan. В нём живут:
`/resources/iflytek/...` (движки речи SR/NLU/TTS — на стенде `AINluEngine init state -1`, не стартуют) и ассеты
**аватара BDCarSDK** (Baidu «цифровой человек»: `AvatarManager.initBdSdk`, `BDCarSDK setDigitalListener`) + темы/обои.
Поэтому на стенде: **оболочка + текстовая строка виджета (все состояния) работают**, а **аватар статичен,
сцены/карточки и голосовой пайплайн не оживают** — это не код, а отсутствующий проприетарный раздел.
→ Чтобы получить аватар на стенде: при подключённом авто вытащить `/resources` (ветку BD-аватара + iflytek res)
и залить в эмулятор (`adb push` в тот же путь), затем движок сможет загрузиться. TODO следующей сессии у авто.

## Скачано с авто (2026-09-01) → `changan-car/car_resources/`
- `iflytek/` (1.0G) — движки речи (`res/`: SRRes 279M, ChanganTts 190M, MVWRes/SERes/CataRes; `speech/`: nlu/dm/nlg конфиги). Для инициализации SR/NLU/TTS.
- `SceneBrain/` (555M) — видео сцен/карточек (cake/loveheart/luying_gouhuo/wallpaper*.mp4/.mov). Это и есть «карточки».
- `theme/` (2.7M) — обои `wallpaper.png` day/night + `pretheme_config.json` (путь на авто `/resources/custormer/ivi/theme/preset_theme/CornerThemeSkinPack/wallpaper/`).

## БЛОКЕР: почему это НЕ ставится на AAOS-эмулятор
Все Changan-приложения читают ассеты по **абсолютному пути `/resources/...`** (жёстко зашит). На эмуляторе
корень `/` — **read-only ext4 dm-verity**, `mkdir /resources` невозможен даже с root+`adb remount` (remount
покрывает только /system,/vendor,/product,/system_ext — не `/`). Overlay-на-корень / bind в namespace init —
хрупко/не сработало (dm-verity root, изоляция namespace adbd). Итог: **iflytek-движки и SceneBrain-карточки
некуда положить по нужному пути на этом эмуляторе.**
Пути к цели: (а) **rooted тестовое устройство** или (б) **кастомный system-образ AAOS с вшитым `/resources`**
(собрать ext4 и добавить в fstab/образ) — тогда `adb push car_resources/* /resources/` и движки поднимутся.

## Аватар — окончательно НЕ воспроизводим на стенде
BD digital-human (`com.baidu.ar.digital`/`carsdk`) внутри `com.changan.aiassist`: нативный движок + WebSocket,
фигура **скачивается из облака** (`preprod.changan.com.cn/standalone-nginx/plat/propertyFile/*.zip`,
`digital-buddy-list`/`-binding`, `AvatarCacheManager`) и **привязана к аккаунту**. Кэш лежит в data-каталоге
aiassist, но авто — **production build, root недоступен** (`adbd cannot run as root`) → кэш не вытащить.
Вывод: аватар = облачный+нативный+account-bound → на эмуляторе не оживить.

## Осталось / развитие
- systemui Changan НЕ ставим (это `com.android.systemui`, конфликт с CarSystemUI; печатная
  вьюха есть и в лаунчере — не нужен).
- Поставить наш `sa_ru_exec.apk` (SpeechAssistant) на AAOS: тоже снять sharedUserId=system,
  target→33, пересобрать; тогда виджет будет управляться реальным голосовым пайплайном, а не `am broadcast`.
- «Come and customize your own desktop!» и часть строк — англ.: их нет в ru-оверлее.
