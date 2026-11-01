/*
 * Russian Voice Assistant modification for Changan A06 (C390).
 * Copyright (c) 2026 Tecrow.
 * Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only. See LICENSE.
 * Independent modification — not affiliated with or endorsed by Changan Automobile.
 */
package com.stand.bridge;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import org.json.JSONObject;
// ru2zh mapper + shared phrase helpers (zoneOf/zoneCode/gradeOf/isOn/isOff/numIn/colorOf) live in Ru2Zh.
import static com.stand.bridge.Ru2Zh.*;

/**
 * In-process offline Russian ASR tap for SpeechAssistant.
 * Hooked into SrBaseSession's processed-audio callback (onMSDataProc): the same
 * AEC/beamformed PCM the native iFlytek ASR consumes is streamed into our Russian recognizer (GigaAM).
 * Triggered automatically by the native wake (knob), no separate mic/AudioRecord.
 */
public final class RuBridge {
    private static final String TAG = "RuBridge";
    /** Bump with every published dex: the log shows which code actually runs. */
    private static final String CODE_VERSION = "q07-18";


    // Stock TTS-engine registry (world-writable dir; the app runs as uid=system). Registering our
    // engine here is what makes install one-step — no adb edit of the config needed.
    private static final String TTS_CFG =
        "/resources/iflytek/speech/changan_assets/tts_config.txt";
    private static final String OUR_TTS_CLASS = "com.stand.tts.PiperCaTts";
    private static final String[] STOCK_TTS_CLASSES = {
        "com.iflytek.speech.tts.IssTtsStreamImpl",
        "com.incall.apps.speechassistant.tts.ChanganTtsImpl",
    };

    /** Self-install: point the active TTS engines in tts_config.txt at our PiperCaTts (idempotent,
     *  keeps a one-time .bak). Targeted className replacement preserves all other unit-specific fields.
     *  May be blocked by SELinux on some builds → then the manual adb step is the fallback. Takes full
     *  effect after the next app restart (the stock config is already loaded on the current launch). */
    static void ensureTtsRegistered() {
        try {
            File cfg = new File(TTS_CFG);
            if (!cfg.exists()) { Log.i(TAG, "tts_config not found, skip self-register"); return; }
            String txt = readTextFile(cfg);
            if (txt == null || txt.isEmpty()) return;
            if (txt.contains(OUR_TTS_CLASS)) return;                 // already registered
            File bak = new File(TTS_CFG + ".bak");
            if (!bak.exists()) writeTextFile(bak, txt);              // one-time backup of the stock config
            String patched = txt;
            for (String s : STOCK_TTS_CLASSES) patched = patched.replace(s, OUR_TTS_CLASS);
            if (patched.equals(txt)) { Log.w(TAG, "tts_config: no stock TTS class to replace"); return; }
            boolean ok = writeTextFile(cfg, patched);
            Log.i(TAG, "tts_config: PiperCaTts " + (ok ? "registered (self-install; restart to activate)"
                                                       : "WRITE FAILED (SELinux? use manual adb step)"));
        } catch (Throwable t) { Log.e(TAG, "ensureTtsRegistered", t); }
    }
    private static String readTextFile(File f) {
        try { byte[] b = new byte[(int) f.length()]; java.io.FileInputStream in = new java.io.FileInputStream(f);
              int off = 0, n; while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n; in.close();
              return new String(b, 0, off, "UTF-8"); }
        catch (Throwable t) { Log.e(TAG, "readTextFile " + f, t); return null; }
    }
    private static boolean writeTextFile(File f, String s) {
        try { java.io.FileOutputStream o = new java.io.FileOutputStream(f);
              o.write(s.getBytes("UTF-8")); o.flush(); o.close(); return true; }
        catch (Throwable t) { Log.e(TAG, "writeTextFile " + f, t); return false; }
    }

    /** True if our TTS engine is currently registered in tts_config.txt (for the settings switch state). */
    public static boolean isRuTtsOn() {
        try { String t = readTextFile(new File(TTS_CFG)); return t != null && t.contains(OUR_TTS_CLASS); }
        catch (Throwable t) { return false; }
    }

    /** Default the custom wake word to «你好» so users can activate the assistant by saying "нихао".
     *  Stored in Settings.Global "voice_custom_name"; a ContentObserver re-registers it with the IVW
     *  engine. Only set when empty — never clobber a word the user chose. App is uid=system → allowed. */
    static void ensureCustomWakeword() {
        try {
            // Default custom wake word «你好你好» (nǐ hǎo nǐ hǎo = «нихао нихао») — easiest for RU speakers.
            // Applied ONCE via a fresh marker (so it runs on this build), then the user's choice is kept.
            android.content.SharedPreferences sp = appCtx.getSharedPreferences("stand", 0);
            if (sp.getBoolean("wakeword_nihao2", false)) return;
            android.content.ContentResolver cr = appCtx.getContentResolver();
            boolean ok = android.provider.Settings.Global.putString(cr, "voice_custom_name", "你好你好"); // «нихао нихао»
            sp.edit().putBoolean("wakeword_nihao2", true).apply();
            Log.i(TAG, "wakeword: default «你好你好» (нихао нихао) applied once (" + ok + ")");
        } catch (Throwable t) { Log.e(TAG, "ensureCustomWakeword", t); }
    }

    /** Restore the stock TTS engine from the one-time backup (.bak). Effect after the next restart. */
    static void restoreTts() {
        try {
            File cfg = new File(TTS_CFG), bak = new File(TTS_CFG + ".bak");
            if (!bak.exists()) { Log.w(TAG, "restoreTts: no .bak"); return; }
            String orig = readTextFile(bak);
            if (orig == null || orig.isEmpty()) return;
            boolean ok = writeTextFile(cfg, orig);
            Log.i(TAG, "tts_config: stock " + (ok ? "restored from .bak (restart to activate)" : "restore FAILED"));
        } catch (Throwable t) { Log.e(TAG, "restoreTts", t); }
    }

    /** Settings-switch entry point: on → register our TTS engine, off → restore stock. Returns the
     *  resulting state (registered?). Safe to call from the UI thread (small file I/O). */
    public static boolean setRuTts(boolean on) {
        if (on) ensureTtsRegistered(); else restoreTts();
        return isRuTtsOn();
    }

    /** "верни/восстанови заводскую/оригинальную/китайскую озвучку/голос" → restore stock TTS. */
    private static boolean isRestoreVoice(String s) {
        boolean voice = s.contains("озвуч") || s.contains("голос");
        boolean stock = s.contains("заводск") || s.contains("оригинальн") || s.contains("штатн")
                     || s.contains("стандартн") || s.contains("китайск");
        return voice && stock;
    }
    /** "включи/верни русскую озвучку" → re-register our TTS. */
    private static boolean isEnableVoice(String s) {
        boolean voice = s.contains("озвуч") || s.contains("голос");
        return voice && s.contains("русск") && (s.contains("включ") || s.contains("верни") || s.contains("вернуть"));
    }

    /** Injected at the end of SettingsActivity.onCreate: add a native-looking "Русская озвучка" switch
     *  row at the top of the settings list (above the wakeup row) so the user can turn our TTS engine
     *  on/off without adb. Everything is done in code — no stock layout/resource edit. Idempotent. */
    public static void installTtsSwitch(final android.app.Activity act) {
        try {
            final android.content.Context ctx = act;
            android.view.View content = act.findViewById(android.R.id.content);
            if (!(content instanceof android.view.ViewGroup)) return;
            android.widget.Switch wake = findFirstSwitch((android.view.ViewGroup) content);
            if (wake == null) return;
            // Climb to the row that sits inside the vertical rows-list container.
            android.view.View row = wake;
            android.view.ViewParent vp = row.getParent();
            while (vp instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) vp;
                if (g instanceof android.widget.LinearLayout
                        && ((android.widget.LinearLayout) g).getOrientation() == android.widget.LinearLayout.VERTICAL
                        && g.getChildCount() >= 2) break;
                row = g; vp = g.getParent();
            }
            if (!(vp instanceof android.view.ViewGroup)) return;
            final android.view.ViewGroup container = (android.view.ViewGroup) vp;
            if (container.findViewWithTag("ru_tts_row") != null) return;   // already added
            int idx = container.indexOfChild(row); if (idx < 0) idx = 0;

            android.widget.LinearLayout myRow = new android.widget.LinearLayout(ctx);
            myRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            myRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            myRow.setTag("ru_tts_row");
            try { myRow.setPadding(row.getPaddingLeft(), row.getPaddingTop(),
                                   row.getPaddingRight(), row.getPaddingBottom()); } catch (Throwable ignored) {}
            try { android.view.ViewGroup.LayoutParams src = row.getLayoutParams();
                  if (src != null) myRow.setLayoutParams(new android.view.ViewGroup.LayoutParams(src)); }
            catch (Throwable ignored) {}

            android.widget.TextView label = new android.widget.TextView(ctx);
            label.setText("Русская озвучка");
            try { label.setTextColor(0xFFFFFFFF); } catch (Throwable ignored) {}
            try { label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 40f); } catch (Throwable ignored) {}
            label.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            final android.widget.Switch sw = new android.widget.Switch(ctx);
            sw.setChecked(isRuTtsOn());
            sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    boolean now = setRuTts(on);
                    if (now != on) b.setChecked(now);
                    try { android.widget.Toast.makeText(ctx, now
                            ? "Русская озвучка включена. Перезапустите ассистента."
                            : "Заводская озвучка восстановлена. Перезапустите ассистента.",
                            android.widget.Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
                }
            });

            myRow.addView(label);
            myRow.addView(sw);
            container.addView(myRow, idx);          // above the wakeup row
            Log.i(TAG, "settings: RU-TTS switch installed at idx " + idx);
        } catch (Throwable t) { Log.e(TAG, "installTtsSwitch", t); }
    }

    private static android.widget.Switch findFirstSwitch(android.view.ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            android.view.View c = g.getChildAt(i);
            if (c instanceof android.widget.Switch) return (android.widget.Switch) c;
            if (c instanceof android.view.ViewGroup) {
                android.widget.Switch s = findFirstSwitch((android.view.ViewGroup) c);
                if (s != null) return s;
            }
        }
        return null;
    }
    private static final String ACTION = "com.stand.NLU";
    private static Context appCtx;
    private static volatile String lastText = "";   // latest ASR hypothesis for current utterance
    /** Language of the current utterance ("ru" | "kk" | "ky" | "uz" | "en"), sent to the backend as
     *  `lang`. Only the multilingual build (GigaAM-Multilingual, GIGAAM_ML=1) ever sets it to non-"ru". */
    static volatile String curLang = "ru";
    private static volatile int wakeZone = 1;        // detected speaking zone (SrBaseSession.getCurrentDirect: 1=driver,2=passenger,3/4=rear,5=rear-mid)
    // Force offline: online chat backend isn't ready, so unrecognized phrases give a local RU reply
    // instead of hitting the cloud. Flip to false once the LLM backend is live.
    /** The OpenQiyuan switch «Отправка диагностики». Off by default: nothing leaves the car for sda.tecrow.org. */
    private static final String DIAGNOSTICS_SETTING = "openqiyuan_assistant_diagnostics";
    private static boolean offlineOnly() {
        try { return android.provider.Settings.Global.getInt(appCtx.getContentResolver(), DIAGNOSTICS_SETTING, 0) == 0; }
        catch (Throwable t) { return true; }
    }

    // Online conversational path via the NATIVE (device-signed) Dubhe cloud + MyMemory translation:
    // a free-form phrase the rule-based ru2zh can't map is translated RU->ZH (MyMemory) and injected
    // into the stock NLU, which consults the real Changan cloud; the Chinese answer is turned back to
    // Russian on the way out. Independent of offlineOnly() (that only gates the dead 127.0.0.1 backend).
    // Our backend answers in Russian and understands Russian, so no MyMemory RU<->ZH is needed on the
    // server path. ru2zh still maps COMMANDS to Chinese for the stock LOCAL NLU (injectZh) — that stays.
    private static final boolean CLOUD_MT = false;

    // Consult the real Changan Dubhe cloud in parallel with the local NLU on every injected Chinese
    // query (see injectZh): cloud carControl executes and the Chinese answer (dmResults.tts) returns.
    // Requires a NATIVE_CLOUD build (else the endpoint is redirected to a dead 127.0.0.1).
    private static final boolean CLOUD_INJECT = false;

    // ---- ASR: GigaAM-v3 (sherpa-onnx offline CTC) ------------------------------------------------
    // GigaAM-v3 CTC is a full-utterance Conformer (no streaming): we accumulate the whole utterance
    // (wp==1..wp==3) and decode it once at the end via com.stand.asr.GigaAsr, yielding a single
    // hypothesis. That text is fuzzy-corrected against GRAMMAR_WORDS (see chooseQuery / fuzzyFix).
    //
    // ---- ASR strategy: open vocabulary + fuzzy correction ----------------------------------------
    // The decoder is open-vocabulary (no grammar restriction). GRAMMAR_WORDS is NOT a grammar — it is
    // a vetted CORRECTION dictionary for fuzzyFix() (every ru2zh contains()-stem has a form here);
    // since it includes "не"/question words, the negation/question guards in ru2zh always see them.
    private static volatile java.util.Set<String> grammarSet; // GRAMMAR_WORDS as a set (for fuzzyFix)
    private static final String[] GRAMMAR_WORDS = {
        // ---- verbs / actions ----
        "включи","включить","включай","вруби","выключи","выключить","выключай","выруби","вырубай",
        "отключи","открой","открыть","открывай","закрой","закрыть","закрывай","закрытие","подними",
        "поднять","опусти","опустить","прибавь","убавь","увеличь","уменьши","повысь","понизь",
        "установи","поставь","сделай","переключи","смени","поменяй","верни","добавь","запусти",
        "активируй","убери","убрать","убирай","приоткрой","заблокируй","разблокируй","разблокировку",
        "запри","отопри","сложи","разложи","разверни","сверни","погаси","разбуди","усыпи","проснись",
        "помой","протри","вытри","брызни","найди","поищи","покажи","запомни","забудь","сохрани",
        "удали","выбери","продолжи","продолжай","останови","перемотай","перезвони","позвони",
        "набери","ответь","возьми","сбрось","отклони","отбой","отвечай","подожди","подзови",
        "подъезжай","припаркуйся","выезжай","выехать","заезжай","перестройся","обгони","держи",
        "держись","следуй","отмени","выйди","закройся","отстань","уйди","отвали","листай","проветри",
        "прогрей","согрей","погрей","подогрей","греть","охлади","синхронизируй","выровняй","начни",
        "сними","запиши","сфоткай","скачай","замолчи","замолкни","перестань","говорить","хватит",
        "стоп","поехали","вези","едем","ехать","объезжай","объедь","объехать","приблизь","отдали",
        "поверни","заверши","положи","продли","погрузи","погрузить","раздай","вернись",
        // ---- climate ----
        "температуру","температура","градус","градуса","градусов","кондиционер","кондиционеры",
        "кондей","кондер","климат","печку","печка","обдув","вентилятор","вентиляцию","вентиляция",
        "рециркуляцию","рециркуляция","циркуляцию","воздух","воздуха","обогрев","подогрев",
        "разморозку","запотели","потеют","стекло","стекла","лобовое","лобового","авто",
        "автоматический","охлаждение","нагрев","осушение","влажность","дуй","лицо","ноги","поток",
        "качание","теплее","холоднее","потеплее","похолоднее","прохладнее","жарко","душно","замерз",
        "холодно","синхронизацию","одинаковая","аромат","ароматизатор","парфюм","запах","электро",
        // ---- seats ----
        "сиденье","сиденья","сидение","сидений","кресло","кресла","кресел","массаж","массажа",
        "поясницу","поясница","спинку","спинка","подушку","подставку","подножку","опору","наклон",
        "позицию","вип","посадку","выход","вперед","назад",
        // ---- windows / roof / doors / locks ----
        "окно","окна","окон","окнах","окошко","стеклоподъемники","люк","шторку","шторки","шторка",
        "шторы","солнцезащитную","боковые","багажник","багажника","багажнике","капот","фрунк",
        "дверь","двери","дверцу","замок","замки","блокировку","лючок","бак","бензобака","детский",
        "половину","наполовину","процентов","процент","треть","четверть","щелочку","щель","борт",
        "верхнюю","нижний","охране","охрану","охраны","дождь","дожде","дождя","подходе","уходе",
        "отходе",
        // ---- lights ----
        "подсветку","подсветка","подсветки","атмосферную","цвет","красный","синий","зеленый","белый",
        "желтый","фиолетовый","оранжевый","розовый","ярче","темнее","притуши","приглуши","фары",
        "свет","дальний","ближний","аварийку","аварийную","сигнализацию","габариты","противотуманки",
        "стояночные","позиционные","плафон","салона","салоне","чтения","лампу","лампа","фонари",
        "фонарь","шоу","светомузыку","световое","эффект","градиент","переливы","такт","ритм",
        // ---- mirrors / steering / wipers ----
        "зеркало","зеркала","зеркал","руль","руля","рулевое","дворники","дворников","щетки",
        "стеклоочистители","омыватель","чувствительность","датчик","датчика","легче","тяжелее",
        "тяжелый","усилие","автоскладывание","ремонт","сервисный","замену","стриминговое",
        // ---- hud / displays ----
        "проекцию","проекция","проекции","хад","худ","экран","экрана","экране","дисплей","яркость",
        "автояркость","цветовую","шрифт","ночной","дневной","горизонтально","вертикально","угол",
        "высоту","защиту","глаз","очистку","почистить","пассажирского",
        // ---- media / sound ----
        "громкость","громче","тише","потише","погромче","звук","звука","звуком","звуковую","музыку",
        "музыка","музычку","песню","песня","песенку","песни","трек","композицию","следующий",
        "следующую","следующая","предыдущий","предыдущая","предыдущую","пауза","паузу","играй",
        "играет","радио","станцию","станция","канал","частоту","волну","источник","плейлист",
        "избранное","избранного","повтор","повтори","заново","сначала","начало","запись","записи",
        "минут","минуту","минуты","секунд","скорость","воспроизведения","лайк","нравится","текст",
        "караоке","качество","юсб","флешка","флешку","историю","история","прослушивания","перемешай",
        "кругу","порядку","случайно","сцену","улучшение","динамик","список","онлайн","прошлую",
        "другой","другую",
        // ---- navi ----
        "навигацию","навигатор","навигации","навигационный","карту","карта","карточку","слои",
        "маршрут","домой","работу","заправку","заправка","заправиться","зарядку","зарядка","зарядки",
        "зарядный","парковку","стоянку","кафе","кофе","ресторан","поесть","кушать","есть",
        "проголодался","аптеку","больницу","туалет","супермаркет","банкомат","гостиницу","отель",
        "мойку","магазин","продуктов","пробки","пробок","спутник","обычную","платных","шоссе",
        "трассы","дорог","дорога","дороге","дорогу","пути","подсказки","кратко","подробно","север",
        "курсу","далеко","долго","приедем","доедем","место","места","адрес","пункт","назначения",
        "аэропорт","вокзал","центр","куда","километров","километра","осталось","время","полный",
        "ближайшая","ближайший","азс",
        // ---- phone ----
        "трубку","контакты","контакт","контактах","маме","папе","жене","мужу","брату","сестре",
        "журнал","вызовов","звонков","звонки","пропущенные","звонок","вызов","номер","сервис",
        "телефон","телефона",
        // ---- modes / vehicle / misc ----
        "режим","режимы","спорт","эко","экономичный","комфорт","комфортный","стандартный","снежный",
        "снег","зимний","бездорожье","внедорожный","подвеску","подвеска","клиренс","мягче","жестче",
        "рекуперацию","рекуперация","электрический","электричестве","гибрид","гибридный","чисто",
        "круиз","круизом","адаптивный","автопилот","пилот","вождения","дистанцию","следования",
        "следование","полосу","полосе","ограничение","автопарковку","сама","машина","машину",
        "машины","холодильник",
        "холодильника","приватный","блютус","вайфай","интернет","точку","точка","доступа","розетку",
        "беспроводную","регистратор","регистратора","камеру","камера","камеры","обзор","панораму",
        "круговой","вида","видео","кадр","фото","фотографии","селфи","галерею","альбом","виджеты",
        "минус","персонажа","аватар","тему","темы","тема","оформления","обои","заставку","голос",
        "мужской","женский","слово","пробуждения","активации","будильник","напоминание",
        "напоминания","ремнях","кино","фильм","кровать","отдых","кемпинг","кемпинга","палатку",
        "бодрость","взбодри","вздремнуть","подремать","поспать","макияж","встречи","погрузки",
        "очиститель","осушитель","пылесос","дома","домашний","квартире","расширения","общение",
        "свободное","ассистент","помощник","внешний","настройки","настройку","приложение",
        "приложения","браузер","календарь","беспокоить","сценарии","сценарий","главный","главную",
        "рабочий","стол","страница","страницу","батарея","батареи","давление","шин","шинах","колес",
        "пробег","бензина","топлива","заряд","заряда","заряжена","уровень","запас","хода","статус",
        "пожаловаться","укачивает","тошнит","часов",
        // ---- zones ----
        "все","всех","весь","салон","водителю","водителем","водителя","водительское","пассажиру",
        "пассажира","пассажиром","пассажирам","пассажирское","заднему","задней","заднее","заднего",
        "задние","задних","передние","переднее","передних","спереди","сзади","слева","справа",
        "правое","правому","правее","левое","левому","левее","вверх","вниз","влево","вправо",
        // ---- grades / quantities ----
        "максимум","максимально","минимум","минимально","полностью","больше","меньше","выше","ниже",
        "сильнее","слабее","посильнее","послабее","побольше","поменьше","повыше","пониже","быстрее",
        "медленнее","длиннее","короче","крупнее","мельче","чуть","немного","еще","ближе","дальше",
        "впереди","снова","раз",
        // ---- numbers (spoken) ----
        "ноль","один","одну","два","две","три","четыре","пять","шесть","семь","восемь","девять",
        "десять","одиннадцать","двенадцать","тринадцать","четырнадцать","пятнадцать","шестнадцать",
        "семнадцать","восемнадцать","девятнадцать","двадцать","тридцать","сорок","пятьдесят",
        "шестьдесят","семьдесят","восемьдесят","девяносто","сто","полтора",
        "первый","первую","первая","второй","вторую","вторая","третий","третья","третью",
        "четвертый","четвертую","пятый","шестой","седьмой","восьмой","девятый","десятый",
        // ---- particles / question & guard words (нужны для negation/question guard!) ----
        "на","до","в","и","с","у","за","по","не","о","про","или","без","при","надо","нет","да",
        "мне","меня","мы","я","нас","это","эту","этой","эта","ли","было","очень",
        "пожалуйста","слушай","давай","сколько","где","когда","как","что","почему","зачем","можно",
        "хочу","хочется","нужно","нужна","какой","какая","стоит","лучше","такое","значит","менять",
        "работает","пользоваться","опасен","опасно","вчера","забыл","оставил","потерял","соседа",
        "погода","погоду","расскажи","объясни",
        // народные формулировки (обдув в рот, продув пердака и родня)
        "рот","морду","морда","харю","пердак","пердака","жопу","жопы","жопа","булки","булок",
        "пятую","пятой","точки","дубак","колотун","холодрыга","сауна","пекло","духота","бане",
        // ---- слова из tests.tsv, недостававшие после переезда словаря в RuBridge (2026-09-03) ----
        "автоматические","автомобиле","амбиентную","аудиокнигу","бензобак","быстро","веди","вокзала","врубай","вспотел",
        "вызови","говори","деактивируй","дистанция","для","дует","дхо","едь","жару","жесткая",
        "заглуши","задний","заедем","зажги","запарился","заряди","зарядке","зафиксируй","звякни","избранные",
        "капли","ко","кондишку","контроль","крыше","крышу","лампочка","лампочку","лампы","машине",
        "машиной","морозилку","музон","музыки","мягкая","навигация","наклони","направь","натопи","неоновую",
        "огни","освещение","остуди","отвези","откинь","отодвинь","отопление","панорамную","парковки","паркуйся",
        "пассажирский","переверни","перегорела","передний","переключись","поближе","поддай","поедем","полную","положение",
        "полоса","полчаса","помассируй","понравилась","попогрейку","порт","порядок","послушать","потуши","правый",
        "придвинь","продрог","продув","рядом","салонный","свежего","светлую","сильно","сильное","скинь",
        "скриншот","слабо","слишком","сломался","случайный","смахни","сна","снимок","со","совсем",
        "сократи","спину","спины","срочно","такси","тачку","тело","темную","тепла","той",
        "триста","туманки","тут","форточки","форточку","ходовые","холодос","цветовая","цели","через",
        "эконом",
        // ---- словоформы под стемы ru2zh без тест-фраз ----
        "авторежим","везде","ветер","вперемешку","всю","выгони","выеду","грудь","двигатель","жопогрейку","заскочим","ионизацию","катушку","колонки","корпус","медиа","нуля","обогреватель","отруби","парилка","пододвинь","поезжай","помощь","послушаем","пригаси","пятая","разбил","резко","скорее","там","торс","улице","эвакуатор","эмбиент","энергосбережение",
        "[unk]"
    };

    /** GRAMMAR_WORDS as a fast lookup set (built once), for fuzzyFix — excludes the "[unk]" token. */
    private static java.util.Set<String> grammarSet() {
        java.util.Set<String> g = grammarSet;
        if (g == null) {
            g = new java.util.HashSet<>();
            for (String w : GRAMMAR_WORDS) if (!w.equals("[unk]")) g.add(w);
            grammarSet = g;
        }
        return g;
    }

    /** Nudge near-miss ASR words back to the vetted command vocabulary so ru2zh's contains()-stems
     *  match. Open-vocab ASR sometimes mis-hears a term by a letter or two ("кондицанер"); we replace
     *  only when a single close candidate exists (edit distance within ~1 per 4 chars), leaving normal
     *  words, names and destinations untouched. Used as a RESCUE when the raw text fails (see chooseQuery). */
    static String fuzzyFix(String phrase) {
        if (phrase == null || phrase.isEmpty()) return phrase;
        java.util.Set<String> voc = grammarSet();
        String[] toks = phrase.split("\\s+");
        boolean changed = false;
        for (int i = 0; i < toks.length; i++) {
            String w = toks[i];
            if (w.length() < 4 || voc.contains(w)) continue;      // short or already valid -> keep
            int budget = Math.max(1, w.length() / 4);   // len/4 — проверено: len/3 портит имена («андрею»->«адрес») и тянет к ближнему чужому слову
            String best = null; int bestD = budget + 1;
            for (String c : voc) {
                if (Math.abs(c.length() - w.length()) > budget) continue;
                int d = lev(w, c, budget);
                if (d < bestD) { bestD = d; best = c; if (d == 0) break; }
            }
            if (best != null && bestD <= budget) { toks[i] = best; changed = true; }
        }
        return changed ? String.join(" ", toks) : phrase;
    }

    /** Bounded Levenshtein: exact distance, or budget+1 once it provably exceeds budget. */
    private static int lev(String a, String b, int budget) {
        int n = a.length(), m = b.length();
        int[] prev = new int[m + 1], cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i; int rowMin = cur[0];
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
                if (cur[j] < rowMin) rowMin = cur[j];
            }
            if (rowMin > budget) return budget + 1;               // early exit — can't get under budget
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[m];
    }

    /** Choose the phrase to act on from the single GigaAM hypothesis: the RAW text if ru2zh understands
     *  it (keeps names / free-text intact), else the FUZZY-corrected text if that is understood (rescue),
     *  else the fuzzy-cleaned text — which falls through to the offline stub / chat. */
    static String chooseQuery(String text) {
        if (text == null || text.isEmpty()) return "";
        // SAFETY: negation/question («не закрывай окно», «как работает…») -> whole phrase to chat,
        // don't let fuzzyFix strip the «не» and turn it into a command.
        if (isGuarded(text.toLowerCase().replace('ё', 'е'))) return text;
        if (ru2zhAll(text, wakeZone) != null) return text;                       // understood as-is (simple or compound)
        String fx = fuzzyFix(text);
        if (!fx.equals(text) && ru2zhAll(fx, wakeZone) != null) return fx;       // fuzzy rescue
        return fuzzyFix(text);
    }

    public static void init(final Context ctx) {
        Log.i(TAG, "init code=" + CODE_VERSION);
        Ru2Zh.onUnsafeBlocked = new Ru2Zh.UnsafeListener() { public void blocked(String zh) {
            Log.w(TAG, "ru2zh: UNSAFE blocked (ALLOW_UNSAFE=false): " + zh);
            showOnScreen("Команда требует подтверждения", TYPE_FEEDBACK);
        }};
        appCtx = ctx.getApplicationContext();
        // VoiceApp.onCreate runs in EVERY process (main, :tts, :voiceprint), so init runs 3×. Load each
        // heavy model only in the process that actually uses it — otherwise GigaAM (~224 MB) and TeraTTS
        // (~300 MB) get loaded 3 times (measured: :voiceprint ballooned to ~686 MB for nothing).
        boolean mainProc = isMainProcess();
        boolean ttsProc  = isTtsProcess();
        if (mainProc) { ensureTtsRegistered(); ensureCustomWakeword(); }  // self-install once (shared file/setting)
        // TeraTTS is loaded ONLY in :tts (PiperCaTts synthesizes there). main speaks via GlobalTtsClient,
        // which routes to the :tts engine, so main needs no ~300 MB Tera copy of its own.
        if (ttsProc || mainProc) { try { com.stand.tts.TeraTts.init(appCtx); } catch (Throwable ignored) {} }
        // GigaAM ASR: the audio-callback feed (SrBaseSession) only runs in the main process.
        if (mainProc) {
            reconnectExecutor();
            try { com.stand.asr.GigaAsr.init(appCtx); } catch (Throwable t) { Log.e(TAG, "gigaam init", t); }
            new Thread(new Runnable() { public void run() {
                try {   // prime backend dicts + status + installed-apps cache (off the request hot path)
                    userAppPackages();   // warm the app allowlist now (~seconds) so requests don't pay it
                    try { Thread.sleep(4000); } catch (Throwable ignored) {}
                    pushDicts();
                    pushStatus();
                } catch (Throwable t) { Log.e(TAG, "init", t); }
            }}).start();
        }
    }

    /** Called from SrBaseSession ASR-data callback. session=this (unused); wp=phase; inst=SR instance.
     *  ASR engine is GigaAM-v3 (offline CTC) — the whole utterance is decoded once at wp==3. */
    public static void feed(Object session, int nm, int dt, long wp, byte[] pcm, int inst) {
        feedGigaam(session, wp, pcm);
    }

    /** GigaAM-v3 path: accumulate the utterance (wp==1..3) and decode once at the end (offline CTC).
     *  No streaming/partials — the stock SR gives clean start/end, so no VAD is needed. */
    private static void feedGigaam(Object session, long wp, byte[] pcm) {
        try {
            if (wp == 1) { com.stand.asr.GigaAsr.reset(); lastText = ""; }
            if (pcm != null && pcm.length > 0) com.stand.asr.GigaAsr.accept(pcm, pcm.length);
            if (wp == 3) {
                // ASYNC decode: the offline CTC pass costs 100 ms … ~2.8 s for a long buffer and used to
                // run ON the SR callback thread, so SrSession.stopRecognize — i.e. the steering-wheel
                // key OFF / end of utterance — stayed blocked for the whole decode ("stuck" button).
                // GigaAsr.finish() snapshots + resets the buffer under its own lock, so the worker never
                // races the next utterance's chunks; everything downstream (injectZh) is thread-safe too.
                final Object sess = session;
                new Thread(new Runnable() { public void run() { decodeAndAct(sess); } }, "stand-ru-asr").start();
            }
        } catch (Throwable t) { Log.e(TAG, "feedGigaam", t); }
    }

    /** Off-the-callback half of feedGigaam: decode the accumulated utterance, then actuate. */
    private static void decodeAndAct(Object session) {
        try {
            wakeZone = currentDirect(session, wakeZone); // which seat spoke
            String text = com.stand.asr.GigaAsr.finish();
            if (text != null && !text.isEmpty()) Log.i(TAG, "RU ASR (GigaAM): " + text);
            // Multilingual build: a non-Russian phrase (kk/ky/uz/en) has no ru2zh mapping and MUST
            // NOT pass through fuzzyFix (a Kazakh word one edit away from a Russian command word would
            // be "corrected" into an actuation). It goes straight to the backend, tagged with its
            // language; the backend answers in that language and may still return car commands.
            String lang = com.stand.asr.GigaAsr.multilingual() ? com.stand.asr.GigaAsr.detectLang(text) : "ru";
            curLang = lang;
            if (!"ru".equals(lang)) {
                String t = text == null ? "" : text.trim();
                int letters = 0;
                for (int i = 0; i < t.length(); i++) if (Character.isLetter(t.charAt(i))) letters++;
                if (letters <= 1) return;   // ASR tail noise, same rule as handlePhraseZh
                lastText = t;
                Log.i(TAG, "ASR final (" + lang + ", zone " + wakeZone + "): " + t);
                // Uzbek has an OFFLINE command mapper (Uz2Ru → ru2zh, unit-tested against the whole
                // Russian suite): a known car command actuates without the backend, like Russian.
                if ("uz".equals(lang)) {
                    String zh = Uz2Ru.uz2zh(t, wakeZone);
                    if (zh != null) { noteInjected(t, zh, 1); Log.i(TAG, "uz2zh: [" + t + "] -> " + zh); injectZh(zh); return; }
                }
                if (offlineOnly()) showOnScreen("Не поняла команду", TYPE_FEEDBACK);
                else sendToCloud(t);
                return;
            }
            String query = chooseQuery(text == null ? "" : text); // raw, then fuzzyFix
            if (query != null && !query.isEmpty()) {
                lastText = query;   // so swap()/CloudNlu surface our RU text, never the native Chinese
                showOnScreen(cap(query), TYPE_NLP);
                Log.i(TAG, "RU ASR final (zone " + wakeZone + "): " + query);
                handlePhraseZh(query); // ru2zh → stock NLU pipeline
            }
        } catch (Throwable t) { Log.e(TAG, "decodeAndAct", t); }
    }

    /** Speak text via the assistant's TTS engine. */
    /** Test hook (StandNluReceiver sayb64): push text through the stock TTS route. */
    public static void speakTest(String text) { speak(text); }

    /** Test hook (StandNluReceiver keywake): simulate the steering-wheel key wake exactly as the car does
     *  (verified in the car log: "keycode == 231 com.incall.action.KEY_CLICK" -> BusinessController.
     *  changeWakeupToLeft(10) -> playWakeUpTips type=10). Main process only. */
    public static void keyWakeTest() {
        if (!isMainProcess()) return;
        try {
            Class<?> bc = Class.forName("com.incall.apps.speechassistant.controller.BusinessController");
            Object inst = bc.getMethod("getInstance").invoke(null);
            bc.getMethod("changeWakeupToLeft", int.class).invoke(inst, 10);
        } catch (Throwable t) { Log.e(TAG, "keyWakeTest", t); }
    }

    /** Speak via the platform's global TTS client. Q07/C798: com.incall.apps.speechassistant.tts.
     *  GlobalTtsClient; A06/C390: com.changan.speech.tts.GlobalTtsClient — both expose
     *  getInstance() + startPlayAuto(String), so try ours first, fall back to the A06 path. */
    private static final String[] TTS_CLIENT_CLASSES = {
        "com.incall.apps.speechassistant.tts.GlobalTtsClient",   // Q07 (C798)
        "com.changan.speech.tts.GlobalTtsClient",                // A06 (C390)
    };

    private static void speak(String text) {
        for (String cn : TTS_CLIENT_CLASSES) {
            try {
                Class<?> tc = Class.forName(cn);
                Object tts = tc.getMethod("getInstance").invoke(null);
                tc.getMethod("startPlayAuto", String.class).invoke(tts, text);
                return;
            } catch (ClassNotFoundException e) {
                // not this platform's path — try the next one
            } catch (Throwable t) { Log.e(TAG, "speak", t); return; }
        }
        Log.w(TAG, "speak: no GlobalTtsClient found (neither Q07 nor A06 path)");
    }

    // Assistant text-bar states (PgsSwitcherView `type`), matching the stock voice UI:
    static final int TYPE_PGS = 1;      // live dictation (partial ASR)
    static final int TYPE_NLP = 2;      // recognized command echo
    static final int TYPE_GUIDE = 3;    // idle rotating hints
    static final int TYPE_FEEDBACK = 4; // answer / reply message
    static final int TYPE_CLEAR = 9;    // clear the bar

    /** Drive the launcher's assistant text-bar (PgsView/PgsSwitcherView) with the stock
     *  platform broadcast. `type` selects the visual state (see TYPE_* above); the stock
     *  voice UI uses the very same Intent (NotifierUtil.notifyTextChanged). */
    static void showOnScreen(String text, int type) {
        try {
            if (appCtx == null || text == null) return;
            text = text.replaceAll("^[.\\u2026\\u22ef\\s]+", "").trim();
            if (text.isEmpty()) return;
            Intent it = new Intent("com.incall.action.UPDATE_TEXT");
            java.util.ArrayList<String> list = new java.util.ArrayList<>();
            list.add(text);
            it.putExtra("text", text);
            it.putExtra("textList", list);
            it.putExtra("tips", "");
            it.putExtra("direct", wakeZone);
            it.putExtra("level", 1);            // LEVEL_HIGH
            it.putExtra("type", type);
            it.putExtra("time", 5000);
            if (type == TYPE_GUIDE) it.putExtra("scroll", true);
            it.addFlags(0x20);                  // FLAG_RECEIVER_FOREGROUND
            appCtx.sendBroadcast(it);
        } catch (Throwable t) { Log.e(TAG, "showOnScreen", t); }
    }

    // ===================== System-wide ZH→RU TTS interception =====================
    // Hooked into TtsPlayer.start(role, text, sid, bool, IPlayerListener) (classes5). Any Chinese
    // text the system hands to TTS is intercepted here: translate ZH→RU and speak it with our Piper
    // voice, driving the listener callbacks so the stock dialog flow continues. Unknown text →
    // return false → stock Mandarin TTS plays as fallback (low risk).

    private static boolean hasCJK(String s) { for (int i=0;i<s.length();i++){char c=s.charAt(i); if(c>=0x4E00&&c<=0x9FFF) return true;} return false; }
    private static boolean hasCyrillic(String s) { for (int i=0;i<s.length();i++){char c=s.charAt(i); if(c>=0x0400&&c<=0x04FF) return true;} return false; }

    /** Filter out Chinese UI notifications and partial dictation (type 1) */
    public static boolean filterShowText(Object info) {
        if (info == null) return false;
        try {
            Class<?> c = info.getClass();
            java.lang.reflect.Field ft = c.getField("type");
            int type = ft.getInt(info);
            if (type == 1) return false;

            java.lang.reflect.Field fText = c.getField("text");
            Object textObj = fText.get(info);
            if (textObj != null && hasCJK(textObj.toString())) return false;

            java.lang.reflect.Field fTips = c.getField("tips");
            Object tipsObj = fTips.get(info);
            if (tipsObj != null && hasCJK(tipsObj.toString())) return false;

            java.lang.reflect.Field fList = c.getField("textList");
            Object listObj = fList.get(info);
            if (listObj instanceof java.util.List) {
                for (Object item : (java.util.List<?>) listObj) {
                    if (item != null && hasCJK(item.toString())) return false;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "filterShowText", t);
        }
        return true;
    }

    /** A TTS request our engine must IGNORE: Chinese to be spoken (CJK present, no Cyrillic). By owner's
     *  request our TTS never voices Chinese — a pure-CJK stock NLG reply is dropped silently rather than
     *  translated+voiced. Mixed Russian+CJK still speaks (the Russian part; CJK cleaned inline). */
    public static boolean isChineseSpeech(String s) {
        return s != null && hasCJK(s) && !hasCyrillic(s);
    }

    /** Marker text the patched TipsManager.getWakeUpTipsFromClick returns on a steering-key/knob wake
     *  (build_sa WAKE_CHIME). Exact match only — PiperCaTts plays the wake chime for it instead of speech.
     *  Voice wake tips (我在/在呢/…) map to «Чем могу помочь» and are still spoken. */
    public static final String CLICK_WAKE_MARKER = "Слушаю";

    /** The VOICE wake greeting as it reaches the TTS engine («Чем могу помочь», «Пассажир, чем могу помочь»);
     *  PiperCaTts mixes the wake chime into it (spoken greeting + chime in parallel). */
    public static boolean isWakeGreeting(String ru) {
        if (ru == null) return false;
        String s = ru.toLowerCase().replaceAll("[^a-zа-яё ]", " ").replaceAll("\\s+", " ").trim();
        return s.endsWith("чем могу помочь");
    }
    public static boolean isClickWakeMarker(String ru) {
        if (ru == null) return false;
        String s = ru.toLowerCase().replaceAll("[^a-zа-яё ]", " ").replaceAll("\\s+", " ").trim();
        return s.equals(CLICK_WAKE_MARKER.toLowerCase());
    }

    /** Text → Russian for the native TTS engine (PiperCaTts). Strips prosody tags ([se55], [w0]);
     *  MIXED overlay text (mostly Russian with a stray Chinese word like «громкость 语音 уменьшена»)
     *  keeps the Russian and cleans the CJK inline; a PURE Chinese tip goes through zh2ru; latin/digits
     *  pass as-is. Returns null only when nothing speakable remains. */
    // Failed-local-command → LLM fallback. When ru2zh maps a phrase to a Chinese command the native NLU
    // can't handle, the intent comes back 'unknown', the DM falls to the (cut) cloud, and the NLG is a
    // "network error" — heard as a moo. We detect that NLG, suppress it, and re-send the ORIGINAL Russian
    // to our backend with a localCommandFailed marker (owner collects these to adapt ru2zh/the backend).
    private static volatile String lastLocalRu;   // original Russian query behind the last injected command
    private static volatile String lastLocalZh;   // the Chinese command we injected (ru2zh OR a backend zhCommand)
    private static volatile long lastLocalTs;
    private static volatile int lastLocalAttempt; // which try this injection was (1 = first)
    // Bounded retry: on failure the LLM gets ONE chance to reformulate the Chinese command; if the 2nd
    // try also fails, answer gracefully + collect — never loop. Backend gates on localAttempt; the device
    // hard-caps too. MAX = 2 injection attempts (first guess + one LLM correction).
    private static final int MAX_LOCAL_ATTEMPTS = 2;

    /** Record an injected command (try #attempt) so we can recover if the native NLU can't execute it. */
    static void noteInjected(String ru, String zh, int attempt) {
        lastLocalRu = (ru == null ? "" : ru); lastLocalZh = (zh == null ? "" : zh);
        lastLocalAttempt = attempt; lastLocalTs = System.currentTimeMillis();
    }

    /** Native NLG signalling the injected command was NOT understood (unknown intent → dead-cloud fallback).
     *  On this build the native cloud is cut, so this NLG == a failed local command. */
    private static boolean isLocalFailNlg(String s) {
        return s != null && (s.contains("网络异常") || s.contains("请检查网络") || s.contains("请求超时")
            || s.contains("我没听懂") || s.contains("没有听懂") || s.contains("不明白你的意思"));
    }

    // The firmware's OWN "not supported" reply pool: string-array resources not_support[] /
    // general_notsupport[] / general_result_notsupport[] (6 shared texts). Extracted from the speechadapter
    // string table (nontoxic/jadx_out/.../strings/com.incall.apps.speechadapter.json) by RESOURCE KEY — not
    // guessed. The DM picks one of these when a RECOGNIZED intent can't be performed on this car (e.g.
    // REPAIR_WIPER dispatched to CarControlAction but no wiper-service unit → no actuation). That is NOT
    // domain=unknown, so checkArbResult misses it; exact membership here catches it. Deliberately NOT in
    // this pool: specific replies with a reason (*_cannot_* "уже максимальная температура", *_not_support
    // "сзади не настроить") — those are understood commands with an honest boundary answer, not a gap.
    // Regenerate with the python snippet in the session notes if the overlay changes.
    private static final String[] NOT_SUPPORT_ZH = {
        "哎呀，你说的这个有点挑战性，让我再学习一下",
        "感谢你让我发现新大陆，这就去探索学习",
        "现在还在赶工中，再给我一点时间吧",
        "被你发现我不会这个了，马上就学",
        "被你发现我的技能缺口啦，这就去学习升级",
        "这个我还做不到，让我再提升一下"
    };
    private static final String[] NOT_SUPPORT_RU = {
        "Вы заметили, что я этого не умею. Сейчас научусь",
        "Вы нашли пробел в моих навыках — сейчас же подучусь",
        "Ох, это непростая задача — дайте мне ещё подучиться",
        "Пока я так не умею, мне нужно ещё подрасти",
        "Спасибо, вы открыли мне новое — иду изучать",
        "Я ещё над этим работаю, дайте мне немного времени"
    };
    /** Strip prosody tags ([se55]) and trailing punctuation/space so a pool entry matches exactly. */
    private static String normReply(String s) {
        return s.replaceAll("\\[[^\\]]*\\]", "").trim().replaceAll("[\\s.。!！?？~]+$", "").trim();
    }
    /** True iff the NLG is one of the firmware's generic "not supported / I'll learn" replies (ZH or the
     *  overlay RU) — i.e. a recognized command this car couldn't perform. Recency-guarded downstream. */
    private static boolean isNotSupportReply(String s) {
        if (s == null) return false;
        String n = normReply(s);
        if (n.isEmpty()) return false;
        for (String x : NOT_SUPPORT_ZH) if (n.equals(x)) return true;
        for (String x : NOT_SUPPORT_RU) if (n.equals(x)) return true;
        return false;
    }

    /** A local command failed: re-send its original Russian to the LLM, marked, so it isn't a dead moo.
     *  Detection may run in the TTS process; the query lives in main, so off-main we signal main by broadcast. */
    private static void signalLocalFailed() {
        if (isMainProcess()) { onLocalCommandFailed(); return; }
        try { if (appCtx != null) appCtx.sendBroadcast(new Intent(ACTION)
                .setPackage("com.incall.apps.speechassistant").putExtra("localfailed", "1")); }
        catch (Throwable ignored) {}
    }

    /** Native arbitration result for an injected command (runs in MAIN via the onArbitrationResult hook).
     *  If the NLU couldn't resolve it — domain 'unknown' / intent UNDEFINED_FUNCTION|UNKNOWN — the car
     *  can't do it: hand the original Russian to the LLM, marked for collection. This is the reliable
     *  failure signal (the "网络异常" NLG was only one cloud-fallback variant). */
    public static void checkArbResult(String json) {
        try {
            if (json == null) return;
            // Stock's Chinese recognizer also hears the Russian phrase and fails on it: that is not our command failing
            if (!isOurs(json)) return;
            org.json.JSONArray a = new JSONObject(json).optJSONArray("nluResults");
            if (a == null || a.length() == 0) return;
            JSONObject r = a.optJSONObject(0); if (r == null) return;
            String dom = r.optString("domain", ""), intent = r.optString("intent", "");
            if ("unknown".equalsIgnoreCase(dom) || intent.contains("UNDEFINED") || "UNKNOWN".equalsIgnoreCase(intent)) {
                Log.i(TAG, "arb unknown (" + dom + "/" + intent + ") -> local command failed");
                onLocalCommandFailed();
            }
        } catch (Throwable ignored) {}
    }

    /** Runs in main (from signalLocalFailed or the receiver): fire the LLM fallback once, if recent. */
    public static void onLocalCommandFailed() {
        if (!isMainProcess()) return;
        String ru = lastLocalRu, zh = lastLocalZh; int att = lastLocalAttempt; long ts = lastLocalTs;
        if (ru == null || ru.isEmpty() || System.currentTimeMillis() - ts > 8000) return;
        lastLocalRu = null;   // one-shot for THIS failure; a retry's own injection re-arms it
        if (att > MAX_LOCAL_ATTEMPTS) {   // hard safety cap (backend should already have stopped)
            Log.i(TAG, "local cmd failed, retries exhausted (" + att + "): " + ru);
            speak("Пока не умею это. Записал."); return;
        }
        // Re-send marked, carrying the attempt count. Backend: at attempt < MAX it MAY return ONE
        // corrected zhCommand (different from failedCommand); at attempt >= MAX it MUST answer without a
        // command (collect + graceful). Either way no loop — the device notes each try and caps here.
        Log.i(TAG, "local cmd failed -> LLM retry (attempt " + att + ", marked): [" + ru + "] zh=" + zh);
        sendToCloud(ru, true, zh, att);
    }

    /** The car refused audio focus for a reply. In this car's audio policy the assistant's voice
     *  (VOICE_COMMAND) has the LOWEST priority — below CALL, MUSIC and ANNOUNCEMENT — and
     *  com.incall.SoaBridgeTransit holds permanent ANNOUNCEMENT focus for minutes at a time. The stock
     *  player then drops the reply silently while the command still executes: "works but says nothing"
     *  (field report 22.09.2026, confirmed on the car: "play failed, because audio focus can not
     *  request" + CarAudioFocus REQUEST_FAILED). The build_sa TTS_NO_FOCUS_GATE patch makes the stock
     *  player speak anyway and call this, so the event stays visible in the log. Calls are unaffected:
     *  a real call is muted by the separate VoiceEnableUtils "phone" gate, not by focus. */
    public static void onTtsFocusDenied() {
        Log.w(TAG, "TTS focus denied by the car audio policy — speaking anyway");
    }

    public static String ttsTextToRu(String text) {
        if (text == null) return null;
        String s = text.replaceAll("\\[[^\\]]*\\]", "").trim();   // drop [se55]/[w0]… prosody tags
        if (s.isEmpty()) return null;
        if (isLocalFailNlg(s) || isNotSupportReply(s)) { signalLocalFailed(); return null; }   // failed command → LLM
        String out;
        if (hasCyrillic(s)) {                                      // overlay Russian (maybe + stray CJK)
            if (hasCJK(s)) s = fixInlineZh(s);
            out = s.trim().isEmpty() ? null : s.trim();
        } else if (hasCJK(s)) {
            out = zh2ru(s);                                        // pure Chinese tip → fixed table
            if (out == null && CLOUD_MT) {                         // free-form cloud answer → MyMemory ZH→RU
                String mt = Translate.zhToRu(s);
                if (mt != null && !mt.isEmpty()) out = mt;
            }
            if (out == null) {                                    // unknown response: never leave CJK on screen/TTS
                String f = fixInlineZh(s);
                out = f.isEmpty() ? null : f;
            }
        } else {
            // Stock NLG sometimes confirms with a bare Latin "OK" — unchanged, its cache key stays the
            // same → the stock Chinese prefab ("OK" in the doudou voice) plays, bypassing our engine.
            // Rewrite it to a RU ack so the text changes (cache miss → our engine synthesizes it).
            String core = s.toLowerCase().replaceAll("[^a-zа-яё]", "");
            if (core.equals("ok") || core.equals("okay")) out = "Хорошо";
            else out = s;                                          // other latin / digits only
        }
        Log.i(TAG, "ttsTextToRu: [" + text + "] -> [" + out + "]");
        return out;
    }

    /** System-wide TtsPlayer.start rewrite: return the text with any Chinese turned into Russian
     *  (never null — falls back to the original), so no CJK is ever spoken/shown, whatever engine plays it. */
    public static String ttsRewrite(String text) {
        try {
            // On a no-answer/timeout the stock voices the CURRENT rotating guide word ("you can say X")
            // via TtsPlayer.start — those are our RU_HINTS, meant to be DISPLAYED, not spoken. Suppress
            // by rewriting to empty (both our engine and the stock cache then play nothing).
            if (isHintText(text)) { Log.i(TAG, "ttsRewrite: suppress guide-word voicing: " + text); return ""; }
            // Failed local command — unknown-intent moo (网络异常) OR a "learning" reply for a recognized
            // command the car can't perform: suppress on screen too and hand the original RU to the LLM.
            if (isLocalFailNlg(text) || isNotSupportReply(text)) { Log.i(TAG, "ttsRewrite: fail/not-support NLG suppressed -> LLM: " + text); signalLocalFailed(); return ""; }
            String r = ttsTextToRu(text); return (r != null && !r.isEmpty()) ? r : text;
        } catch (Throwable t) { return text; }
    }

    private static java.util.Set<String> HINT_SET;
    /** True if the TTS text is one of the rotating widget hints (RU_HINTS) — those must never be voiced. */
    private static boolean isHintText(String text) {
        if (text == null) return false;
        String s = text.replaceAll("\\[[^\\]]*\\]", "").trim().toLowerCase();   // drop [se55]/[w0] prosody
        if (s.isEmpty()) return false;
        if (HINT_SET == null) {
            java.util.HashSet<String> set = new java.util.HashSet<String>();
            for (String h : RU_HINTS) set.add(h.trim().toLowerCase());
            HINT_SET = set;
        }
        return HINT_SET.contains(s);
    }

    /** Clean stray Chinese words embedded in otherwise-Russian overlay text: map the common ones,
     *  drop the rest, collapse spaces. */
    private static String fixInlineZh(String s) {
        // common NLG fragments (longer forms first) so unmapped responses still read as Russian
        s = s.replace("好的", "Хорошо").replace("已为您", "").replace("已经", "").replace("为您", "")
             .replace("已调亮", "ярче").replace("已调暗", "темнее").replace("调亮", "ярче").replace("调暗", "темнее")
             .replace("已调到", "установлено ").replace("已调", "").replace("已打开", "включено").replace("已关闭", "выключено")
             .replace("已开启", "включено").replace("中控屏", "экран").replace("屏幕", "экран").replace("亮度", "яркость")
             // volume channel names as the DM inserts them («громкость %s увеличена»)
             .replace("蓝牙电话", "телефона").replace("多媒体", "медиа").replace("导航", "навигации").replace("耳机", "наушников")
             .replace("语音助手", "голоса").replace("语音", "голоса").replace("音量", "громкость").replace("温度", "температура")
             .replace("空调", "климат").replace("座椅", "сиденье").replace("车窗", "окно");
        s = s.replaceAll("[\\u4E00-\\u9FFF]+", "");               // drop any remaining CJK
        return s.replaceAll("\\s{2,}", " ").replaceAll("\\s+([,.:;!?])", "$1").trim();
    }

    /** Intercept every text handed to the stock TTS. The ru.lang.* overlays already RUSSIFY the NLG
     *  text, so most of it is Cyrillic already — we just voice it with our Piper RU engine instead of
     *  the Mandarin iFlytek one (which mangles Russian). Residual Chinese → zh2ru table.
     *  @return true if we handled it (Piper + callbacks); false → let stock TTS play it. */
    public static boolean onTtsText(String text, final Object listener, final Object player) {
        try {
            if (text == null || text.trim().isEmpty()) return false;
            Log.i(TAG, "TTS text in: " + text);
            lastReplyAt = android.os.SystemClock.elapsedRealtime();
            String ru;
            if (hasCJK(text)) {                       // still Chinese (not overlaid) → translate
                ru = zh2ru(text);
                if (ru == null) {                     // never voice Chinese; the log shows what to add to the table
                    Log.w(TAG, "TTS untranslated: " + text);
                    ru = UNTRANSLATED_REPLY;
                }
                Log.i(TAG, "TTS ZH->RU: [" + text + "] -> " + ru);
            } else if (hasCyrillic(text)) {           // already Russian (overlays) → voice with Piper
                ru = text;
            } else {
                return false;                          // latin/digits only → let stock handle
            }
            if (repeatedFailure(text, ru)) {
                Log.i(TAG, "TTS skipped, same failure reply just played: " + ru);
                finishTts(listener, player);
                return true;
            }
            // NB: do NOT push the answer to the widget here — the stock UI already renders it (the
            // russified NLG text). A duplicate showOnScreen leaves the previous answer lingering and
            // it flashes for a frame at the next dictation. We only replace the VOICE, not the UI.
            callListener(listener, "onPlayBegin", String.class, ru);
            com.stand.tts.TeraTts.speak(ru, new Runnable() { public void run() {
                finishTts(listener, player);
            }});
            return true;
        } catch (Throwable t) { Log.e(TAG, "onTtsText", t); return false; }
    }

    private static final String OPENQIYUAN = "org.openqiyuan.app";
    private static final String OPEN_APP = OPENQIYUAN + ".OPEN_APP";
    private static final String UNTRANSLATED_REPLY = "Ответ машины пока не переведён";
    private static final String EXECUTOR = "com.incall.apps.speechadapter";
    private static final long EXECUTOR_RECONNECT_DELAY_MS = 5000L;

    /** The executor (speechadapter) keeps its connection to this process, and after we restart its replies fail with
     *  startSpeak ret: -2: the commands still run, but the real result is never spoken. Restarted, it connects again. */
    private static void reconnectExecutor() {
        new Thread(new Runnable() { public void run() {
            try {
                Thread.sleep(EXECUTOR_RECONNECT_DELAY_MS);
                android.app.ActivityManager am = (android.app.ActivityManager) appCtx.getSystemService(Context.ACTIVITY_SERVICE);
                for (android.app.ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
                    if (p.processName.equals(EXECUTOR)) {
                        Log.i(TAG, "restarting the executor, pid " + p.pid);
                        android.os.Process.killProcess(p.pid);
                    }
                }
            } catch (Throwable t) { Log.e(TAG, "reconnectExecutor", t); }
        }}, "stand-executor").start();
    }

    private static final long EXECUTOR_REPLY_WAIT_MS = 2500L;
    private static volatile long lastReplyAt;

    /** The executor speaks the real result itself, which may be a refusal. Our own words are only for a silent command. */
    private static void confirmIfSilent(final String text) {
        final long sentAt = android.os.SystemClock.elapsedRealtime();
        new Thread(new Runnable() { public void run() {
            try { Thread.sleep(EXECUTOR_REPLY_WAIT_MS); } catch (InterruptedException e) { return; }
            if (lastReplyAt >= sentAt) return;
            Log.i(TAG, "executor stayed silent, confirming: " + text);
            speak(text);
        }}, "stand-confirm").start();
    }

    /** «открой навигатор», «запусти ютуб»: works without the backend, by a known name or the label of an installed app. */
    private static boolean openAppByName(String phrase) {
        String name = AppNames.nameIn(phrase);
        if (name == null) return false;
        try {
            android.content.pm.PackageManager pm = appCtx.getPackageManager();
            java.util.List<String> known = AppNames.knownPackages(name);
            for (String pkg : known) {
                if (pm.getLaunchIntentForPackage(pkg) != null) {
                    boolean navigator = AppNames.isAnyNavigator(name);
                    return launch(pm, pkg, navigator, navigator ? "Открываю навигатор" : "Открываю " + pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
                }
            }
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(q, 0)) {
                String label = String.valueOf(r.loadLabel(pm));
                if (AppNames.sameName(name, label)) {
                    return launch(pm, r.activityInfo.packageName, false, "Открываю " + label);
                }
            }
            if (!known.isEmpty()) {
                speak("Это приложение не установлено");
                return true;
            }
        } catch (Throwable t) { Log.e(TAG, "openAppByName", t); }
        Log.i(TAG, "openAppByName: no app for [" + name + "]");
        return false;
    }

    /** OpenQiyuan knows the app's DPI window, the projection, the split and which navigator is chosen. Without it, a plain launch. */
    private static boolean launch(android.content.pm.PackageManager pm, String pkg, boolean anyNavigator, String reply) {
        Intent open = new Intent(OPEN_APP).setClassName(OPENQIYUAN, OPENQIYUAN + ".gesture.GestureService")
                .putExtra("pkg", pkg).putExtra("navigator", anyNavigator);
        if (pm.resolveService(open, 0) == null || appCtx.startService(open) == null) {
            Intent it = pm.getLaunchIntentForPackage(pkg);
            if (it == null) return false;
            android.os.Bundle main = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle();
            appCtx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), main);
        }
        speak(reply);
        return true;
    }

    // On Q07 the stock chain executes an injected command silently, so the confirmation is ours.
    private static final String CONFIRM_DONE = "Готово";
    private static final java.util.Map<String, String> CONFIRM_VERBS = new java.util.HashMap<>();
    static {
        String[][] verbs = {
            {"открой", "Открываю"}, {"закрой", "Закрываю"}, {"включи", "Включаю"}, {"выключи", "Выключаю"},
            {"отключи", "Отключаю"}, {"запусти", "Запускаю"}, {"останови", "Останавливаю"}, {"поставь", "Ставлю"},
            {"сделай", "Делаю"}, {"подними", "Поднимаю"}, {"опусти", "Опускаю"}, {"прибавь", "Прибавляю"},
            {"убавь", "Убавляю"}, {"увеличь", "Увеличиваю"}, {"уменьши", "Уменьшаю"}, {"настрой", "Настраиваю"},
        };
        for (String[] v : verbs) CONFIRM_VERBS.put(v[0], v[1]);
    }

    /** «открой моё окно» -> «Открываю ваше окно». A form we don't know gets a plain «Готово». */
    static String confirmation(String phrase) {
        String[] w = phrase.trim().split("\\s+");
        int verb = 0;
        while (verb < w.length && (w[verb].equals("и") || w[verb].equals("пожалуйста"))) verb++;
        if (verb == w.length || !CONFIRM_VERBS.containsKey(w[verb])) return CONFIRM_DONE;
        StringBuilder out = new StringBuilder(CONFIRM_VERBS.get(w[verb]));
        for (int i = verb + 1; i < w.length; i++) {
            String word = w[i];
            if (word.equals("пожалуйста") || word.equals("себя")) continue;
            if (word.equals("мое") || word.equals("моё")) word = "ваше";
            else if (word.equals("мой")) word = "ваш";
            else if (word.equals("мою")) word = "вашу";
            else if (word.equals("мне")) word = "вам";
            out.append(' ').append(word);
        }
        return out.toString();
    }

    /** Stock requested audio focus before start(), and releases it only from its own playback, which we replaced.
     *  Unreleased, the focus count only grows and the wheel volume stays on the assistant's channel. */
    private static void finishTts(Object listener, Object player) {
        callListener(listener, "onPlayComplete", null, null);
        try {
            // The real player is a StreamTtsPlayer, the method and the count live in its parent.
            // TtsPlayer2 asks for focus after our hook, so it has nothing to release here.
            Class<?> c = player == null ? null : player.getClass();
            while (c != null && !c.getName().endsWith(".tts.TtsPlayer")) c = c.getSuperclass();
            if (c == null) return;
            java.lang.reflect.Field counts = c.getDeclaredField("mFocusCounts");
            counts.setAccessible(true);
            if (((java.util.concurrent.atomic.AtomicInteger) counts.get(player)).get() <= 0) return;
            java.lang.reflect.Method m = c.getDeclaredMethod("abandonFocus");
            m.setAccessible(true);
            m.invoke(player);
        } catch (Throwable t) { Log.e(TAG, "abandonFocus", t); }
    }

    private static final long FAILURE_REPLY_GAP_MS = 20_000L;
    private static long lastFailureReplyAt = -FAILURE_REPLY_GAP_MS;

    /** While people talk in the listening window each phrase can fail in turn: say «didn't catch that» once, not every time. */
    private static synchronized boolean repeatedFailure(String text, String ru) {
        String s = ru.toLowerCase();
        boolean failure = isLocalFailNlg(text) || isNotSupportReply(text) || s.contains("расслыш") || s.contains("услыш")
                || s.contains("повтор") || s.contains("не понял");
        if (!failure) return false;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastFailureReplyAt < FAILURE_REPLY_GAP_MS) return true;
        lastFailureReplyAt = now;
        return false;
    }

    private static void callListener(Object l, String method, Class<?> sig, Object arg) {
        if (l == null) return;
        try {
            if (sig == null) l.getClass().getMethod(method).invoke(l);
            else l.getClass().getMethod(method, sig).invoke(l, arg);
        } catch (Throwable t) { Log.e(TAG, "callListener " + method, t); }
    }

    /** Wakeup / sleep / reject / easter-egg TIP text (comes from GlobalTtsClient.startPlayAuto, i.e.
     *  the one-shot voiceserver Mandarin synth — NOT the TtsPlayer.start response path). Voice it in
     *  Russian with Piper instead. @return true if we spoke it (stock skips); false → stock plays it. */
    public static boolean onTipText(String zh) {
        // Tip voicing DISABLED. These one-shot tips (reject "try saying…"/easter-egg suggestions from
        // GlobalTtsClient) echo the rotating widget hints and were being spoken on failed/offline
        // queries. Return true to suppress them entirely (our voice AND the stock Mandarin tip) → silence.
        // Wakeup/sleep greetings are unaffected: they go through the TTS engine (TtsPlayer), not here.
        return true;
    }

    /** Random tip array (reject/egg via startPlayAutoRandom) — DISABLED, suppressed like onTipText. */
    public static boolean onTipRandom(String[] arr) { return true; }

    /** ZH→RU translation for TTS. Strips prosody tags ([w0]) and a leading seat-address prefix
     *  (主驾/副驾/…), then delegates to zh2ruCore. null if unknown (grow from "TTS text in:" logs). */
    static String zh2ru(String zh) {
        if (zh == null) return null;
        String s = zh.trim().replaceAll("\\[[a-zA-Z]\\d+\\]", "");   // drop prosody tags like [w0]
        // wakeup tips prepend a seat address; translate it separately and strip it off the core
        String addr = null;
        // Full seat address incl. driver «Водитель» — TeraTTS (ru_f2) voices the soft sign fine
        // (unlike the old irina), so we greet each seat by name, e.g. "Водитель, я слушаю".
        String[][] zones = {{"主驾","Водитель"},{"副驾","Пассажир"},{"左后","Слева сзади"},
                            {"右后","Справа сзади"},{"中间","По центру"},{"后排","Сзади"}};
        for (String[] z : zones) if (s.startsWith(z[0])) { addr = z[1]; s = s.substring(z[0].length()); break; }
        s = s.replaceAll("^[.\\u2026\\u22ef，、,。~\\s]+", "").replaceAll("[，。~\\s]+$", "");   // trim residual punct
        // Stock refusals "X不支持语音控制" ("X is not voice-controllable") — e.g. door lock is blocked by
        // Changan at the DM level (controlDoorLockTask rejects 锁车). Voice a clear RU reason, not silence.
        if (s.contains("不支持语音控制")) {
            String what = s.replace("不支持语音控制", "");
            String ru = (what.contains("车门锁") || what.contains("门锁") || what.contains("车门")) ? "Блокировка дверей"
                      : what.contains("车窗") ? "Управление окнами"
                      : what.contains("后备箱") || what.contains("尾门") ? "Багажник"
                      : "Эта функция";
            return ru + " голосом не поддерживается";
        }
        String core = zh2ruCore(s);
        // The executor's many wordings of "I can't do this yet"
        if (core == null && (s.contains("学习") || s.contains("技能") || s.contains("赶工"))) core = "Этого я пока не умею";
        if (core == null) return null;
        if (addr == null || addr.isEmpty()) return core;
        // Driver: keep the greeting terse — no seat name («Водитель, чем могу помочь» звучит громоздко).
        // Other seats are still greeted by name.
        if ("Водитель".equals(addr)) return core;
        return addr + ", " + Character.toLowerCase(core.charAt(0)) + core.substring(1);
    }

    /** Core ZH→RU table: assistant tips + command confirmations. Numbers kept as-is (RU TTS reads them). */
    static String zh2ruCore(String s) {
        switch (s) {
            // --- assistant tips: wakeup / barge-in / sleep / reject (learning) / easter-egg ---
            // Wake / listen prompts — owner wants a single activation phrase: «Чем могу помочь?»
            case "我在": case "在呢": return "Чем могу помочь";
            case "我来了": case "来了": case "我在这儿呢": return "Чем могу помочь";
            case "有什么可以帮您": return "Чем могу помочь";
            case "请说": return "Чем могу помочь";
            case "你说": case "你先": case "你先说": return "Чем могу помочь";
            case "再见啦": return "До свидания";
            case "有事再喊我": return "Позовите, если что понадобится";
            case "下次再见": return "До встречи";
            case "我退下了": return "Отключаюсь";
            case "我能听见": return "Я вас слышу";
            case "不要再调戏我了": case "你们不要再调戏我了": return "Хватит меня дразнить";
            case "再这样我不理你啦": return "Ещё раз — и я перестану отвечать";
            case "哎呀，这可把我问住了": return "Ох, тут вы меня озадачили";
            case "这题现在不会，马上就学习": return "Пока не знаю, сейчас научусь";
            case "我还不会呢，马上恶补一下": return "Я ещё не умею, сейчас подтяну";
            case "已读，但绞尽脑汁也不知道怎么回": return "Прочитала, но пока не знаю, что ответить";
            case "我记下啦，或许过段时间我就能帮到你了": return "Записала, может позже смогу помочь";
            case "这个知识点我已经记在小本本上啦": return "Записала это себе в блокнотик";
            case "这个问题暂时难住我啦~这就开启学习模式": return "Вопрос меня озадачил, включаю режим обучения";
            // --- command confirmations ---
            case "好的": case "好的。": return "Хорошо";
            case "已为您打开": case "已打开": return "Включаю";
            case "已为您关闭": case "已关闭": return "Выключаю";
            case "已经打开了哦": return "Уже включено";
            case "已经关闭了哦": return "Уже выключено";
            case "没有找到相关资源哦": return "Ничего не нашла";
            case "当前没有播放歌曲哦": return "Сейчас ничего не играет";
            case "请先驻车后再试": case "请先驻车后再试一下": return "Сначала остановите машину";
            case "咱们车没有这个功能哦": case "车辆无此功能呢": return "В этой машине нет такой функции";
        }
        // templated: "…温度…26…度" → temperature confirmation with the number
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,3})").matcher(s);
        String num = m.find() ? m.group(1) : null;
        if (s.contains("温度") && num != null) return "Ставлю температуру " + num;
        if (s.contains("风") && num != null)   return "Ставлю обдув " + num;
        if (s.contains("座椅加热")) return "Включаю подогрев сиденья" + (num != null ? " " + num : "");
        if (s.contains("座椅通风")) return "Включаю вентиляцию сиденья" + (num != null ? " " + num : "");
        if (s.contains("车窗")) return s.contains("关") ? "Закрываю окно" : "Открываю окно";
        if (s.contains("空调")) return s.contains("关") ? "Выключаю климат" : "Включаю климат";
        if (s.contains("好的")) return "Готово";
        return null; // unknown → stock Chinese fallback
    }

    /** Subtitle of what's being spoken now (avatar/TTS channel), like the stock UI. */
    static void showTtsSubtitle(String text) {
        try {
            if (appCtx == null || text == null || text.isEmpty()) return;
            Intent it = new Intent("com.incall.action.TTS_CONTENT");
            it.putExtra("text", text);
            it.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            appCtx.sendBroadcast(it);
        } catch (Throwable t) { Log.e(TAG, "showTtsSubtitle", t); }
    }

    // Our Russian assistant backend (FastAPI; see changan-a06-assistant-backend). Contract: docs/API.md.
    private static final String BACKEND   = "https://sda.tecrow.org";
    private static final String CHAT_URL  = BACKEND + "/assistant/dialog";
    // Mirror Changan's AiBox uplink actions (aibox/AiBoxConst: loadStatus/loadDicts were declared
    // but never wired in stock firmware) — we implement them ourselves so our backend/LLM gets
    // live car context the stock cloud never receives. See memory [[aiassist-llm]].
    private static final String LOAD_STATUS_URL = BACKEND + "/aibox/loadStatus";
    private static final String LOAD_DICTS_URL  = BACKEND + "/aibox/loadDicts";

    /** Non-command Russian -> our backend (POST /assistant/dialog) -> speak the RU answer and
     *  actuate any car commands the backend returns in data.nluResults[]. No RU<->ZH translation:
     *  the query goes as raw Russian, the answer comes back as Russian. Contract: docs/API.md. */
    // Rolling multi-turn history sent to the backend so it resolves follow-ups ("а сколько до неё км?").
    // Capped by total CHARACTER count (not message count), oldest dropped first; the newest exchange is
    // always kept even if it alone exceeds the cap. Kept on-device only, lost on process restart.
    private static final int MAX_HISTORY_CHARS = 2000;
    private static final java.util.ArrayDeque<String[]> HISTORY = new java.util.ArrayDeque<String[]>();

    /** Append one (user, assistant) exchange, then trim oldest until total text ≤ MAX_HISTORY_CHARS. */
    private static void recordTurn(String user, String assistant) {
        if (user == null) user = ""; if (assistant == null) assistant = "";
        if (user.isEmpty() && assistant.isEmpty()) return;
        synchronized (HISTORY) {
            HISTORY.addLast(new String[]{ user, assistant });
            int total = 0;
            for (String[] t : HISTORY) total += t[0].length() + t[1].length();
            while (HISTORY.size() > 1 && total > MAX_HISTORY_CHARS) {
                String[] r = HISTORY.removeFirst();
                total -= r[0].length() + r[1].length();
            }
        }
    }

    /** History as a JSON array of {role,text}, oldest first (empty until the first answered turn). */
    private static org.json.JSONArray historyArr() {
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            synchronized (HISTORY) {
                for (String[] t : HISTORY) {
                    if (t[0] != null && !t[0].isEmpty())
                        a.put(new JSONObject().put("role", "user").put("text", t[0]));
                    if (t[1] != null && !t[1].isEmpty())
                        a.put(new JSONObject().put("role", "assistant").put("text", t[1]));
                }
            }
        } catch (Throwable ignored) {}
        return a;
    }

    /** Clear the conversation buffer (e.g. on a fresh wake / long silence). */
    public static void clearHistory() { synchronized (HISTORY) { HISTORY.clear(); } }

    static void sendToCloud(final String text) { sendToCloud(text, false, null, 0); }
    static void sendToCloud(final String text, final boolean localFailed) { sendToCloud(text, localFailed, null, 0); }

    /** localFailed=true: this query was a command the car couldn't execute (native NLU returned unknown).
     *  The backend COLLECTS it (query + failedCommand) to adapt. attempt = injection tries already made:
     *  at attempt < MAX the backend MAY return ONE corrected zhCommand (the LLM's second guess); at
     *  attempt >= MAX it MUST answer without a command. Bounded — one retry, never a loop. */
    static void sendToCloud(final String text, final boolean localFailed, final String failedCommand, final int attempt) {
        if (offlineOnly()) { showOnScreen("Не поняла команду", TYPE_FEEDBACK); return; }
        if (!isMainProcess()) return;   // one backend call + one TTS, in the process where TtsClient is ready
        new Thread(new Runnable() { public void run() {
            try {
                // reqId starts with "stand" so commands we execute below survive onArbitrationResult's guard.
                final String rid = "standask-" + System.currentTimeMillis();
                // Attach live car status so our LLM has context the stock cloud never gets.
                JSONObject bodyObj = new JSONObject()
                        .put("requestId", rid).put("query", text).put("lang", curLang)
                        .put("carStatus", collectStatus())
                        .put("installedApps", collectApps())   // user-installed pkgs -> backend app-action registry
                        .put("history", historyArr())          // last ~3 exchanges (role+text) for multi-turn context
                        .put("localCommandFailed", localFailed) // true = a command the car couldn't do (collect+adapt)
                        .put("localAttempt", attempt);          // injection tries already made (0 on first call)
                if (failedCommand != null && !failedCommand.isEmpty()) bodyObj.put("failedCommand", failedCommand);
                String body = bodyObj.toString();
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(CHAT_URL).openConnection();
                c.setConnectTimeout(4000); c.setReadTimeout(30000);
                c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.getOutputStream().write(body.getBytes("UTF-8"));
                int code = c.getResponseCode();
                java.io.InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[4096]; int n; while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                String resp = bo.toString("UTF-8"); in.close();
                String answer = "", answerTts = "", audio = "", followup = "";
                org.json.JSONArray nlu = null, appActions = null, zhCmds = null;
                try {
                    JSONObject d = new JSONObject(resp).getJSONObject("data");
                    answer = d.optString("answer", "");
                    answerTts = d.optString("answerTts", "");   // optional: '+'-stressed variant for TTS
                    audio = d.optString("audioPcm16k", "");
                    followup = d.optString("followupTts", "");
                    nlu = d.optJSONArray("nluResults");
                    appActions = d.optJSONArray("appActions");   // launch into 3rd-party apps (navi/music/...)
                    // Raw Chinese command phrases the LLM generated for a command it couldn't map to a known
                    // intent — we inject them and let the FULL native NLU try to recognize+actuate (better
                    // than refusing). Accept a list or a single string.
                    zhCmds = d.optJSONArray("zhCommands");
                    if (zhCmds == null) {
                        String one = d.optString("zhCommand", "");
                        if (!one.isEmpty()) zhCmds = new org.json.JSONArray().put(one);
                    }
                } catch (Throwable ignored) {}
                Log.i(TAG, "backend answer for [" + text + "]: " + answer
                        + (nlu != null ? " (+" + nlu.length() + " nluResults)" : ""));
                // 1) execute any car commands the backend returned (implicit->action / command fallback)
                if (nlu != null) {
                    for (int i = 0; i < nlu.length(); i++) {
                        JSONObject r = nlu.optJSONObject(i); if (r == null) continue;
                        String dom = r.optString("domain", ""), intent = r.optString("intent", "");
                        if (dom.isEmpty() || intent.isEmpty()) continue;
                        java.util.LinkedHashMap<String,String> slots = new java.util.LinkedHashMap<String,String>();
                        org.json.JSONArray sa = r.optJSONArray("slots");
                        if (sa != null) for (int j = 0; j < sa.length(); j++) {
                            JSONObject s = sa.optJSONObject(j); if (s == null) continue;
                            String nm = s.optString("name", ""); if (nm.isEmpty()) continue;
                            // Prefer normalizedValue (capture has bare "22" for temperature); else derive
                            // by stripping a unit from a numeric value ("22度"->"22").
                            String nv = s.optString("normalizedValue", "");
                            slots.put(nm, !nv.isEmpty() ? nv : normSlot(s.optString("value", "")));
                        }
                        // Actuate through the PROVEN pipeline: intent/slots -> Chinese phrase -> injectZh
                        // (onFinalAsrResult). execArbitration/onArbitrationResult does NOT actuate carControl.
                        String zh = zhFromNlu(intent, slots);
                        if (zh != null) {
                            Log.i(TAG, "backend cmd -> injectZh: " + zh + "  [" + dom + "/" + intent + " " + slots + "]");
                            if (i > 0) { try { Thread.sleep(900); } catch (InterruptedException ignored) {} } // serialize multi-intent
                            injectZh(zh);
                        } else {
                            String jj = arbFlat(rid, text, dom, intent, slots);
                            if (jj != null) { Log.i(TAG, "backend cmd -> execArbitration (no zh map; may not actuate): " + dom + "/" + intent + " " + slots); execArbitration(jj); }
                        }
                    }
                }
                // 1a') raw LLM Chinese commands (fallback for anything not mapped to a known intent):
                //      inject straight into the native NLU and let the car recognize+actuate it, instead
                //      of the backend refusing. Native handles an unknown one itself ("нет такой функции",
                //      spoken in RU via the overlays). Runs after structured nluResults, serialized.
                if (zhCmds != null) {
                    for (int i = 0; i < zhCmds.length(); i++) {
                        String zc = zhCmds.optString(i, "").trim();
                        if (zc.isEmpty()) continue;
                        // Track a single-command guess so its failure is caught. This IS noted on a
                        // localFailed retry too (attempt+1), so a second failed guess ends the bounded
                        // retry (device caps in onLocalCommandFailed; backend caps by localAttempt).
                        if (zhCmds.length() == 1) noteInjected(text, zc, attempt + 1);
                        Log.i(TAG, "backend zhCommand -> injectZh: " + zc + " (try " + (attempt + 1) + ")");
                        try { Thread.sleep(900); } catch (InterruptedException ignored) {} // serialize after nlu
                        injectZh(zc);
                    }
                }
                // 1b) launch backend-provided app actions (nav/music/messenger deep-links) — gated to
                //     user-installed, non-system packages only (see fireAppActions).
                if (appActions != null) fireAppActions(appActions);
                // 2) show + speak. The backend marks stress with '+' (RUAccent) for the TTS; that reads
                //    badly on screen, so DISPLAY gets a stripped (clean) string and TTS gets the marked
                //    one. followupTts wins after a command. answerTts (if sent) is the marked variant.
                String spoken  = !followup.isEmpty() ? followup : (!answerTts.isEmpty() ? answerTts : answer);
                String display = stripStress(!followup.isEmpty() ? followup : answer);
                if (!display.isEmpty()) {
                    showOnScreen(display, TYPE_FEEDBACK); // answer message on the assistant widget
                    showTtsSubtitle(display);             // spoken-subtitle channel (avatar/TTS)
                }
                if (!audio.isEmpty()) playPcm(audio);      // RU speech pre-synthesized by our backend
                else if (!spoken.isEmpty()) speak(spoken);  // else our on-device Tera TTS (RU), '+' consumed
                // record this exchange for multi-turn context (clean text, no '+' stress marks)
                recordTurn(text, stripStress(!followup.isEmpty() ? followup : answer));
            } catch (Throwable t) {
                Log.e(TAG, "sendToCloud", t);
            }
        }}).start();
    }

    // ============================== Car status -> our backend ==============================
    // Reachable read APIs inside SpeechAssistant (no extra car permission needed — SA already
    // reads these): com.incall.apps.speechassistant.third.CaCarManager.getInstance()
    //   .getIntProperty(propId, areaId) / isGearP() / isLocationHasPerson(zone);
    //   static fields com.incall.apps.voicebase.consts.common.ComVar.{sVin,sTuid,sCarModelItems}.
    // All via reflection (SA classes aren't on our compile classpath). Every read is best-effort:
    // a property SA lacks permission for just throws and is omitted from the snapshot.

    private static Object caCarMgr() {
        try {
            Class<?> c = Class.forName("com.incall.apps.speechassistant.third.CaCarManager");
            return c.getMethod("getInstance").invoke(null);
        } catch (Throwable t) { return null; }
    }
    /** Best-effort CarProperty int read via SA's CaCarManager. Returns null on error / sentinel. */
    private static Integer carInt(Object mgr, int propId, int areaId) {
        if (mgr == null) return null;
        try {
            Object v = mgr.getClass().getMethod("getIntProperty", int.class, int.class)
                          .invoke(mgr, propId, areaId);
            int i = ((Number) v).intValue();
            return i == -200 ? null : Integer.valueOf(i);   // -200 = CaCarManager.DEFAULT sentinel
        } catch (Throwable t) { return null; }
    }
    /** Best-effort int[] read via CaCarManager.getIntArrayProperty (VENDOR array props, e.g. the
     *  climate-status block 0x21416810). Returns null on error / empty. */
    private static int[] carIntArr(Object mgr, int propId, int areaId) {
        if (mgr == null) return null;
        // (a) CaCarManager.getIntArrayProperty(int,int)
        try {
            Object v = mgr.getClass().getMethod("getIntArrayProperty", int.class, int.class)
                          .invoke(mgr, propId, areaId);
            int[] a = (int[]) v;
            if (a != null && a.length > 0) return a;
        } catch (Throwable ignored) {}
        // (b) fall back to the raw CarPropertyManager.getProperty(Integer[].class, id, area)
        // NOTE: as of 2026-09 both paths return null for the climate block 0x21416810 on this car —
        // the live HVAC state sits on the SoaBridge/DDS bus, not the accessible CarPropertyManager.
        // Kept so climateTemp populates automatically if the prop ever becomes readable (AC active).
        try {
            java.lang.reflect.Field f = mgr.getClass().getDeclaredField("mCarManager");
            f.setAccessible(true);
            Object cpm = f.get(mgr);
            if (cpm != null) {
                Object cpv = cpm.getClass().getMethod("getProperty", Class.class, int.class, int.class)
                                .invoke(cpm, Integer[].class, propId, areaId);
                Object val = cpv == null ? null : cpv.getClass().getMethod("getValue").invoke(cpv);
                if (val instanceof Integer[]) {
                    Integer[] ia = (Integer[]) val; int[] a = new int[ia.length];
                    for (int i = 0; i < ia.length; i++) a[i] = ia[i] == null ? 0 : ia[i].intValue();
                    if (a.length > 0) return a;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
    private static Boolean carBool(Object mgr, String method, Object... args) {
        if (mgr == null) return null;
        try {
            Class<?>[] sig = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) sig[i] = int.class; // all our uses take int
            Object v = mgr.getClass().getMethod(method, sig).invoke(mgr, args);
            return (Boolean) v;
        } catch (Throwable t) { return null; }
    }
    private static Boolean carBool0(Object mgr, String method) {
        if (mgr == null) return null;
        try { return (Boolean) mgr.getClass().getMethod(method).invoke(mgr); }
        catch (Throwable t) { return null; }
    }
    /** Read a static String field of ComVar (VIN / tuid / carModel). */
    private static String comVar(String field) {
        try {
            Class<?> c = Class.forName("com.incall.apps.voicebase.consts.common.ComVar");
            Object v = c.getField(field).get(null);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable t) { return ""; }
    }
    private static void putInt(JSONObject o, String k, Integer v) {
        try { if (v != null) o.put(k, v.intValue()); } catch (Throwable ignored) {}
    }

    /** Collect whatever car status is readable from SA into a compact JSON snapshot. */
    static JSONObject collectStatus() {
        JSONObject s = new JSONObject();
        long t0 = System.currentTimeMillis();
        try {
            String vin  = comVar("sVin");            if (!vin.isEmpty())  s.put("vin", vin);
            String tuid = comVar("sTuid");           if (!tuid.isEmpty()) s.put("tuid", tuid);
            String cm   = comVar("sCarModelItems");  if (!cm.isEmpty())   s.put("carModel", cm);
            s.put("ts", System.currentTimeMillis());
            // Local clock for the backend: it answers "what time is it" / date questions from carStatus.timezone
            // or carStatus.now and falls back to Europe/Moscow — wrong for a car in Kazakhstan/Uzbekistan.
            try {
                java.util.TimeZone tz = java.util.TimeZone.getDefault();
                s.put("timezone", tz.getID());
                java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US);
                iso.setTimeZone(tz);
                s.put("now", iso.format(new java.util.Date()));
            } catch (Throwable ignored) {}
            Object m = caCarMgr();
            // Confirmed-readable in stock CaCarManager (gear / door locks / seat degrees):
            putInt(s, "gear",         carInt(m, 1082131456, 0));  // 1=P 2=R 3=N (>=4 D)
            putInt(s, "doorLockMain", carInt(m, 1100493568, 1));
            putInt(s, "doorLockFL",   carInt(m, 1100493568, 4));
            putInt(s, "doorLockFR",   carInt(m, 1100493568, 16));
            putInt(s, "doorLockRL",   carInt(m, 1100493568, 32));
            putInt(s, "doorLockRR",   carInt(m, 1100493568, 64));
            putInt(s, "seatDegMain",  carInt(m, 356518791, 1));
            putInt(s, "seatDegCo",    carInt(m, 356518791, 4));
            Boolean gp = carBool0(m, "isGearP"); if (gp != null) s.put("parked", gp.booleanValue());
            Boolean p1 = carBool(m, "isLocationHasPerson", Integer.valueOf(1));
            Boolean p2 = carBool(m, "isLocationHasPerson", Integer.valueOf(4));
            if (p1 != null) s.put("occDriver", p1.booleanValue());
            if (p2 != null) s.put("occPassenger", p2.booleanValue());
            // Climate status: DISABLED. The VENDOR array 0x21416810 sits on the SoaBridge/DDS bus and is
            // NOT reachable via CaCarManager — the read BLOCKS ~10-20s waiting for a bus reply that never
            // comes, then returns null (verified: it added ~22s to EVERY backend request). It never
            // populated anything useful, so we don't read it. If live climate is ever needed, read it
            // ASYNCHRONOUSLY off the request path with a hard timeout — never inline in collectStatus.
            // int[] clim = carIntArr(m, 0x21416810, 0);  // <-- do NOT re-add inline: 20s bus timeout
        } catch (Throwable t) { Log.e(TAG, "collectStatus", t); }
        Log.i(TAG, "collectStatus in " + (System.currentTimeMillis() - t0) + "ms");
        return s;
    }

    // ============================== 3rd-party app actions ==============================
    // The backend may return `data.appActions[]`, each a launch descriptor for a 3rd-party app:
    //   { "action":"android.intent.action.VIEW",         // default VIEW
    //     "uri":"yandexnavi://build_route_on_map?lat_to=55.75&lon_to=37.62",
    //     "package":"ru.yandex.yandexnavi",              // optional; else resolved from uri
    //     "component":"pkg/.Cls",                          // optional explicit component
    //     "extras":{"k":"v"} }                             // optional STRING extras
    // SECURITY: we launch ONLY into packages the USER installed (non-system). The allowlist is
    // computed on-device from PackageManager, so even a garbled/hijacked backend reply can never
    // start a system app (Settings, dialer, etc.) under our uid=system. We also lock the intent to
    // the vetted package (setPackage) so it can't be rerouted to a chooser or another app.

    /** Genuine 3rd-party packages the USER installed — the launch allowlist.
     *  Excluded: (a) FLAG_SYSTEM / updated-system apps; (b) PLATFORM-SIGNED packages. (b) is the key
     *  gate on this car: the russification reinstalled the WHOLE system stack (ru.lang.incall.* :
     *  remotecontrol, vehiclesetting, coreservice, aiassist, SoaBridgeTransit …) into /data, so they
     *  look third-party by flags — but they carry the platform signature (test-keys). Real apps
     *  (Yandex Navi, RuStore, Telegram) each have their own signature, so checkSignatures("android",p)
     *  != MATCH lets them through while every car-system app is refused. Our own platform-signed
     *  package is excluded by the same rule. */
    // Cached: enumerating installed apps + a checkSignatures IPC PER package (~250 on this car) costs
    // ~seconds, and it must NOT sit on every request's hot path. Computed once (lazily), then reused;
    // invalidated on package add/remove (see StandNluReceiver PACKAGE_* / appsChanged()).
    private static volatile java.util.Set<String> appsCache;
    private static volatile long appsCacheTs;
    private static volatile boolean appsRefreshing;
    private static final long APPS_TTL = 5 * 60 * 1000L;   // recompute at most every 5 min (async)

    private static java.util.Set<String> userAppPackages() {
        java.util.Set<String> c = appsCache;
        if (c != null) {
            // serve cache instantly; if stale, refresh in the background so the hot path never blocks
            if (System.currentTimeMillis() - appsCacheTs > APPS_TTL && !appsRefreshing) {
                appsRefreshing = true;
                new Thread(new Runnable() { public void run() {
                    try { computeUserApps(); } finally { appsRefreshing = false; }
                }}).start();
            }
            return c;
        }
        return computeUserApps();   // first call: compute synchronously (then cached)
    }

    /** Enumerate + filter installed apps, cache the result (compute-then-swap; never nulls the cache). */
    private static java.util.Set<String> computeUserApps() {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<String>();
        try {
            if (appCtx == null) return set;   // don't cache before init
            long t0 = System.currentTimeMillis();
            android.content.pm.PackageManager pm = appCtx.getPackageManager();
            java.util.List<android.content.pm.ApplicationInfo> all = pm.getInstalledApplications(0);
            for (android.content.pm.ApplicationInfo ai : all) {
                int f = ai.flags;
                boolean sys = (f & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                           || (f & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                if (sys) continue;
                // platform-signed => part of the car system (russified stack) => never launch it
                if (pm.checkSignatures("android", ai.packageName)
                        == android.content.pm.PackageManager.SIGNATURE_MATCH) continue;
                set.add(ai.packageName);
            }
            Log.i(TAG, "userAppPackages computed in " + (System.currentTimeMillis() - t0)
                    + "ms (" + all.size() + " installed -> " + set.size() + " user apps)");
            appsCache = set; appsCacheTs = System.currentTimeMillis();
        } catch (Throwable t) { Log.e(TAG, "userAppPackages", t); }
        return set;
    }

    /** Drop the installed-apps cache (call when a package is added/removed). */
    public static void appsChanged() { appsCache = null; }

    /** User-installed packages as a JSON array, sent to the backend so it only offers real apps. */
    static org.json.JSONArray collectApps() {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (String p : userAppPackages()) arr.put(p);
        return arr;
    }

    /** Launch backend-provided app actions, gated to user-installed (non-system) packages. */
    static void fireAppActions(org.json.JSONArray acts) {
        if (acts == null || acts.length() == 0 || appCtx == null) return;
        java.util.Set<String> allowed = userAppPackages();
        android.content.pm.PackageManager pm = appCtx.getPackageManager();
        for (int i = 0; i < acts.length(); i++) {
            try {
                JSONObject a = acts.optJSONObject(i); if (a == null) continue;
                String action = a.optString("action", Intent.ACTION_VIEW);
                String uri    = a.optString("uri", "");
                String pkg    = a.optString("package", "");
                String comp   = a.optString("component", "");
                Intent it = new Intent(action);
                if (!uri.isEmpty()) it.setData(android.net.Uri.parse(uri));
                JSONObject ex = a.optJSONObject("extras");
                if (ex != null) {
                    java.util.Iterator<String> keys = ex.keys();
                    while (keys.hasNext()) { String k = keys.next(); it.putExtra(k, ex.optString(k)); }
                }
                // Determine the target package: explicit -> from component -> resolve from the intent.
                String target = pkg;
                if (target.isEmpty() && comp.contains("/")) target = comp.substring(0, comp.indexOf('/'));
                if (target.isEmpty()) {
                    android.content.pm.ResolveInfo ri = pm.resolveActivity(it, 0);
                    if (ri != null && ri.activityInfo != null) target = ri.activityInfo.packageName;
                }
                // SECURITY GATE: must be a package the user installed (never a system app).
                if (target.isEmpty() || !allowed.contains(target)) {
                    Log.w(TAG, "appAction REFUSED (not a user app): '" + target + "' uri=" + uri);
                    continue;
                }
                it.setPackage(target);   // lock to the vetted app — no chooser, no rerouting
                if (comp.contains("/")) {
                    String cls = comp.substring(comp.indexOf('/') + 1);
                    if (cls.startsWith(".")) cls = target + cls;
                    it.setComponent(new android.content.ComponentName(target, cls));
                }
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Log.i(TAG, "appAction -> " + target + " " + action + " " + uri);
                appCtx.startActivity(it);
            } catch (Throwable t) { Log.e(TAG, "fireAppActions[" + i + "]", t); }
        }
    }

    /** Generic fire-and-forget JSON POST to our backend (background thread). */
    private static void postJson(final String url, final JSONObject body, final String tag) {
        if (offlineOnly()) return;
        new Thread(new Runnable() { public void run() {
            try {
                Log.i(TAG, tag + " body " + body);   // observable even if backend is unreachable
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(4000); c.setReadTimeout(8000);
                c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.getOutputStream().write(body.toString().getBytes("UTF-8"));
                int code = c.getResponseCode();
                Log.i(TAG, tag + " -> " + url + " (" + code + ") " + body);
                try { c.getInputStream().close(); } catch (Throwable ignored) {}
            } catch (Throwable t) { Log.e(TAG, tag + " post", t); }
        }}).start();
    }

    /** Push current car-status snapshot to our backend (loadStatus endpoint). */
    public static void pushStatus() { postJson(LOAD_STATUS_URL, collectStatus(), "loadStatus"); }

    /** NEW PATH (ru2zh): inject Chinese TEXT straight into the stock NLU entry
     *  (NluManager.onFinalAsrResult) — the FULL native pipeline runs: local NLU → arbitration →
     *  (offline) actuation OR (online) cloud AI, + NLG + TTS + widget. Rides all stock options and
     *  future OTA updates. zone = detected speaking seat (wakeZone). See ru2zh/docs/00-approach.md. */
    public static void injectZh(final String zh) { injectZh(zh, true); }

    /** confident=true: local NLU is authoritative → arbitration resolves at once (known ru2zh command).
     *  confident=false: local is tentative → arbitration WAITS for the cloud result (free-form phrase
     *  that only the cloud can answer — weather/chat). Passing true made arbitration close on a local
     *  UNKNOWN before the ~1.3s-later cloud reply could win. */
    public static void injectZh(final String zh, final boolean confident) {
        if (!isMainProcess()) return;   // onFinalAsrResult only valid in the main SA process
        new Thread(new Runnable() { public void run() {
            try {
                Class<?> nmC = Class.forName("com.incall.apps.speechassistant.nlu.NluManager");
                Object nm = nmC.getMethod("getInstance").invoke(null);
                // reqId MUST start with "stand" so the patched onArbitrationResult guard keeps it
                // (the guard drops non-"stand" results — the native Chinese SR path).
                String reqId = "standzh-" + System.currentTimeMillis();
                forceWakeIfAsleep(wakeZone);  // injected command must execute past the free-wake gate
                try { nmC.getMethod("updateSidTimestamp", String.class).invoke(nm, reqId); } catch (Throwable ignored) {}
                // signature is (requestId, direction, asrText, confidence) — NOT (asrText, dir, reqId, ...)
                nmC.getMethod("onFinalAsrResult", String.class, int.class, String.class, boolean.class)
                   .invoke(nm, reqId, wakeZone, zh, confident);
                Log.i(TAG, "injectZh sent (zone " + wakeZone + ") reqId=" + reqId + ": " + zh);
                // Also consult the REAL Changan cloud (dialog-new) with the SAME reqId (already
                // registered via updateSidTimestamp) so the cloud result correlates and arbitration
                // accepts it: cloud carControl executes on the car, and the Chinese answer
                // (data.dmResults[].tts) flows back through the stock NLG/TTS pipeline. Needs a
                // NATIVE_CLOUD build (endpoints not redirected). See [[dubhe-response-format]].
                if (CLOUD_INJECT) {
                    try {
                        Class<?> cc = Class.forName("com.incall.apps.speechassistant.nlu.CloudNlu");
                        Object cn = cc.getConstructor().newInstance();
                        cc.getMethod("sendPostRequest", int.class, String.class, String.class)
                          .invoke(cn, wakeZone, reqId, zh);
                        Log.i(TAG, "injectZh -> also cloud (reqId=" + reqId + ")");
                    } catch (Throwable t) { Log.e(TAG, "injectZh cloud", t); }
                }
            } catch (Throwable t) { Log.e(TAG, "injectZh", t); }
        }}).start();
    }

    /** DIAGNOSTIC: send a Chinese query straight to the REAL Changan Dubhe cloud (device-signed) and
     *  log the raw response. onFinalAsrResult only runs LOCAL NLU — the cloud is consulted from
     *  SrBaseSession, which our text injection bypasses. Here we call CloudNlu.sendPostRequest
     *  directly (public, same call SrBaseSession makes). The response is logged by CloudNlu at level i
     *  ("get nlu success: <json>"). reqId starts with "stand" so onCloudNluResult survives our guard.
     *  Needs a NATIVE_CLOUD build (endpoints not redirected to 127.0.0.1). */
    public static void dubheTest(final String zh) {
        new Thread(new Runnable() { public void run() {
            try {
                Class<?> c = Class.forName("com.incall.apps.speechassistant.nlu.CloudNlu");
                Object cn = c.getConstructor().newInstance();
                try { c.getMethod("init").invoke(cn); } catch (Throwable ignored) {}  // location listener (optional)
                String reqId = "standzh-" + System.currentTimeMillis();
                Log.i(TAG, "dubheTest -> cloud (zone " + wakeZone + ") reqId=" + reqId + ": " + zh);
                c.getMethod("sendPostRequest", int.class, String.class, String.class)
                 .invoke(cn, wakeZone, reqId, zh);
            } catch (Throwable t) { Log.e(TAG, "dubheTest", t); }
        }}).start();
    }

    /** Self-contained cloud ask: build the SAME signed dialog-new request the stock CloudNlu makes
     *  (via GateWayUtils.buildJsonRequest — device signature), execute it ourselves, and handle the
     *  response directly instead of relying on the stock arbitration/DM (which resolves on the local
     *  UNKNOWN before the ~1.3s cloud reply and never speaks the answer). We pull data.dmResults[].tts,
     *  turn it Russian (fixed zh2ru table → MyMemory fallback), and speak+show it. carControl in
     *  nluResults is logged (known commands already execute via the local ru2zh path). Needs a
     *  NATIVE_CLOUD build. See [[dubhe-response-format]]. */
    public static void cloudAsk(final String zh) {
        if (!isMainProcess()) return;   // one signed call + one TTS, in the process where TtsClient is ready
        new Thread(new Runnable() { public void run() {
            try {
                String vin = refStaticStr("com.incall.apps.voicebase.consts.common.ComVar", "sVin");
                String tuid = refStaticStr("com.incall.apps.voicebase.consts.common.ComVar", "sTuid");
                String carModel = refStaticStr("com.incall.apps.voicebase.consts.common.ComVar", "sCarModelItems");
                org.json.JSONObject j = new org.json.JSONObject();
                if (carModel != null) j.put("carModel", carModel);
                j.put("zoneId", wakeZone);
                if (vin != null) j.put("vin", vin);
                j.put("requestId", "standask-" + System.currentTimeMillis());
                if (tuid != null) j.put("tuid", tuid);
                j.put("query", zh);
                j.put("isWakeFree", false);

                Class<?> cfg = Class.forName("com.incall.apps.voicebase.util.CommonConfig");
                String url = (String) cfg.getMethod("getDialogUrl").invoke(null);

                Class<?> gw = Class.forName("com.incall.apps.voicebase.util.GateWayUtils");
                Class<?> reqCls = Class.forName("okhttp3.Request");
                Object req = gw.getMethod("buildJsonRequest", String.class, String.class, String.class, boolean.class)
                               .invoke(null, url, j.toString(), "POST", true);

                Class<?> clientCls = Class.forName("okhttp3.OkHttpClient");
                Object client = clientCls.getConstructor().newInstance();
                Object call = clientCls.getMethod("newCall", reqCls).invoke(client, req);
                Object resp = call.getClass().getMethod("execute").invoke(call);
                Object rbody = resp.getClass().getMethod("body").invoke(resp);
                String body = rbody == null ? "" : (String) rbody.getClass().getMethod("string").invoke(rbody);
                try { resp.getClass().getMethod("close").invoke(resp); } catch (Throwable ignored) {}
                Log.i(TAG, "cloudAsk raw: " + body);

                org.json.JSONObject data = new org.json.JSONObject(body).optJSONObject("data");
                if (data == null) return;
                // 1) spoken answer from dmResults[].tts
                org.json.JSONArray dm = data.optJSONArray("dmResults");
                String tts = "";
                if (dm != null) for (int i = 0; i < dm.length(); i++) {
                    String t = dm.optJSONObject(i) == null ? "" : dm.optJSONObject(i).optString("tts", "");
                    if (t != null && !t.isEmpty()) { tts = t; break; }
                }
                if (!tts.isEmpty()) {
                    // Free-form cloud answer -> MyMemory directly. Do NOT use zh2ru here: that table is
                    // for SHORT stock command-confirmations and greedily matches substrings (e.g. it turned
                    // the Shanghai weather "…温度25度到30度…" into "Ставлю температуру 25").
                    String ru = Translate.zhToRu(tts);
                    if (ru == null || ru.isEmpty()) ru = tts;
                    Log.i(TAG, "cloudAsk answer: [" + tts + "] -> [" + ru + "]");
                    showTtsSubtitle(ru);
                    showOnScreen(ru, TYPE_FEEDBACK);
                    speak(ru);   // GlobalTtsClient -> :tts (PiperCaTts/Tera); keeps Tera out of main
                } else {
                    // 2) no synchronous answer (e.g. general_qa LLM streams natively) — log the routing
                    org.json.JSONArray nr = data.optJSONArray("nluResults");
                    String dom = (nr != null && nr.length() > 0) ? nr.optJSONObject(0).optString("domain", "") : "";
                    String intent = (nr != null && nr.length() > 0) ? nr.optJSONObject(0).optString("intent", "") : "";
                    Log.i(TAG, "cloudAsk no tts; domain=" + dom + " intent=" + intent + " agent=" + data.optString("agentName", ""));
                }
            } catch (Throwable t) { Log.e(TAG, "cloudAsk", t); }
        }}).start();
    }

    /** Best-effort read of a static String field via reflection (null on any error). */
    private static String refStaticStr(String cls, String field) {
        try {
            java.lang.reflect.Field f = Class.forName(cls).getDeclaredField(field);
            f.setAccessible(true);
            Object v = f.get(null);
            return v == null ? null : v.toString();
        } catch (Throwable t) { return null; }
    }

    /** Ensure the assistant counts as "awake" (VoiceStateCache.sVwState != 0) so FreeWakeManager
     *  lets a non-whitelisted command through. Only forces when currently asleep (0) — a real knob
     *  wake already set it, so we don't disturb that. state = wake direction (1..5).
     *  The class moved between platforms — Q07/C798 keeps it in com.incall.apps.speechassistant.common,
     *  A06/C390 in com.incall.apps.voicebase.consts.common — so try both (field sVwState: AtomicInteger). */
    private static final String[] VW_CLASSES = {
        "com.incall.apps.speechassistant.common.VoiceStateCache",     // Q07 (C798)
        "com.incall.apps.voicebase.consts.common.VoiceStateCache",    // A06 (C390)
    };

    private static void forceWakeIfAsleep(int zone) {
        for (String cn : VW_CLASSES) {
            try {
                Class<?> c = Class.forName(cn);
                java.lang.reflect.Field f = c.getDeclaredField("sVwState");
                f.setAccessible(true);
                java.util.concurrent.atomic.AtomicInteger ai = (java.util.concurrent.atomic.AtomicInteger) f.get(null);
                if (ai != null && ai.get() == 0) {
                    ai.set(zone >= 1 ? zone : 1);
                    Log.i(TAG, "forceWake: sVwState set to " + ai.get() + " via " + cn);
                }
                return;   // class found → done (never touch a second copy)
            } catch (ClassNotFoundException e) {
                // not this platform's path — try the next one
            } catch (Throwable t) { Log.e(TAG, "forceWake", t); return; }
        }
        Log.w(TAG, "forceWake: VoiceStateCache not found (neither Q07 nor A06 path)");
    }

    /** True only in the app's main process (secondary processes lack a ready NLU pipeline). */
    private static boolean isMainProcess() {
        try {
            String pn = android.app.Application.getProcessName();  // API 28+
            return pn == null || !pn.contains(":");
        } catch (Throwable t) { return true; }
    }

    /** True in the dedicated TTS process (com.incall.apps.speechassistant:tts) where PiperCaTts runs. */
    private static boolean isTtsProcess() {
        try {
            String pn = android.app.Application.getProcessName();
            return pn != null && pn.endsWith(":tts");
        } catch (Throwable t) { return false; }
    }

    /** DIAGNOSTIC: read candidate propIds (csv, hex "0x.." or decimal) across common areaIds via SA's
     *  CaCarManager.getIntProperty and log every valid value. Lets us correlate SoaBridge (group 0x4)
     *  props — windows/locks/seat-heat — that the AOSP VHAL dump can't see. Before/after a manual
     *  control change, diff the "DIAG ..." log lines to find the propId that moved. */
    public static void diagDump(final String propsCsv) {
        new Thread(new Runnable() { public void run() {
            Object m = caCarMgr();
            if (m == null) { Log.e(TAG, "DIAG: no CaCarManager"); return; }
            int[] areas = {0, 1, 4, 16, 32, 64};
            int count = 0;
            for (String tok : propsCsv.split(",")) {
                tok = tok.trim(); if (tok.isEmpty()) continue;
                int pid;
                try {
                    boolean hex = tok.startsWith("0x") || tok.startsWith("0X");
                    pid = (int) Long.parseLong(hex ? tok.substring(2) : tok, hex ? 16 : 10);
                } catch (Throwable t) { continue; }
                for (int a : areas) {
                    Integer v = carInt(m, pid, a);
                    if (v != null) { Log.i(TAG, String.format("DIAG 0x%08x a=%d v=%d", pid, a, v)); count++; }
                }
            }
            Log.i(TAG, "DIAG done: " + count + " values");
        }}).start();
    }

    /** Push personalized dictionaries (contacts / POIs / nicknames) so our backend LLM can match
     *  user vocabulary. Scaffold: id fields + empty lists for now; fill from phonebook / navi
     *  favorites / aimemory profiles when those read paths are wired (mirrors Changan loadDicts). */
    public static void pushDicts() {
        try {
            JSONObject d = new JSONObject();
            String vin = comVar("sVin");   if (!vin.isEmpty())  d.put("vin", vin);
            String tuid = comVar("sTuid"); if (!tuid.isEmpty()) d.put("tuid", tuid);
            d.put("contacts", new org.json.JSONArray());   // TODO: phone@SYNC_PHONE_CONTACT source
            d.put("pois", new org.json.JSONArray());       // TODO: navi favorites
            d.put("nicknames", new org.json.JSONArray());  // TODO: aimemory profiles
            postJson(LOAD_DICTS_URL, d, "loadDicts");
        } catch (Throwable t) { Log.e(TAG, "pushDicts", t); }
    }

    /** Play 16 kHz mono s16le PCM (base64) via AudioTrack — RU speech synthesized by our backend. */
    private static void playPcm(String b64) {
        try {
            byte[] pcm = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            int sr = 16000;
            int min = android.media.AudioTrack.getMinBufferSize(sr,
                android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT);
            android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .build();
            android.media.AudioFormat fmt = new android.media.AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .build();
            android.media.AudioTrack at = new android.media.AudioTrack(attrs, fmt,
                    Math.max(min, pcm.length), android.media.AudioTrack.MODE_STREAM,
                    android.media.AudioManager.AUDIO_SESSION_ID_GENERATE);
            at.play();
            at.write(pcm, 0, pcm.length);
            long ms = (long) (pcm.length / 2.0 / sr * 1000) + 300;
            Thread.sleep(ms);
            at.stop(); at.release();
            Log.i(TAG, "playPcm done (" + pcm.length + " bytes)");
        } catch (Throwable t) { Log.e(TAG, "playPcm", t); }
    }

    // ---- Localized rotating UI hints (replaces Chinese guide words) ----
    // Broad sample across the offline-capable families so the driver discovers the range.
    // Rotating on-screen suggestions ("вы можете сказать…"). Cover ALL function domains, mirroring
    // the stock guide_words.txt (40 phrases across climate/media/nav/system/modes/info). Every control
    // hint below was verified to resolve to a real ru2zh command (see ru2zh/translate-task tests); the
    // few info/chat ones (weather/what-to-wear) intentionally fall through to the cloud assistant.
    // атрибуция (@alvoronoff / @sda_ai) намеренно разнесена в середину списка и НЕ подряд — см. ниже.
    // Не озвучивается (isHintText). Экрана с поворотом на C390 нет — такой подсказки нет.
    // Every phrase here is verified to map in ru2zh by the JVM hints harness (scratchpad/hints/HintsTest:
    // real RuBridge classes from build/dex7 + a no-op android.util.Log shim). Zone variants are given
    // ONLY for seats / windows / lights — those intents carry a zone and map to distinct catalog phrases
    // (打开后排右座椅加热, 打开后排左车窗, 打开左后阅读灯). Cabin TEMPERATURE is NOT zone-addressable in the
    // stock NLU (SET_AIR_CONDITIONER_TEMPERATURE has only a `temperature` slot; no zoned sample in the
    // catalog or the 566-query capture), so no "температура сзади" hint. Nav hints intentionally miss
    // ru2zh (null) and go to the backend → Yandex Navi appActions.
    private static final String[] RU_HINTS = {
        // климат
        "включи климат", "сделай теплее", "температура 22", "быстро охлади салон",
        "обдув в ноги", "сделай обдув сильнее", "включи обогрев руля",
        "включи обогрев заднего стекла", "включи обогрев лобового", "включи рециркуляцию",
        // сиденья (+ зоны)
        "подогрей сиденье водителя", "включи обогрев сиденья пассажира", "подогрей заднее правое сиденье",
        "включи обогрев всех сидений", "включи вентиляцию сиденья", "включи массаж сиденья",
        // окна / люк (+ зоны)
        "открой окно водителя", "открой окно пассажира", "открой заднее левое окно", "закрой окна сзади",
        "приоткрой окна", "закрой все окна", "открой люк", "закрой шторку люка", "проветри салон",
        "Автор: @alvoronoff",                          // credit #1 — в середине списка
        // свет (+ зоны)
        "включи свет в салоне сзади", "включи свет сзади слева", "включи подсветку салона",
        "приглуши подсветку", "включи ближний свет", "включи дальний свет", "включи аварийку",
        "включи свет в багажнике",
        // кузов / зеркала / камера
        "открой багажник", "открой лючок зарядки", "сложи зеркала", "разложи зеркала",
        "включи обогрев зеркал", "включи круговой обзор",   // «помой стекло» убрано: дворники/омыватель заблокированы конфигом авто (0x6003_27=2)
        // холодильник
        "открой холодильник", "поставь холодильник на 5 градусов",
        // медиа
        "включи музыку", "сделай громче", "сделай тише", "следующий трек", "поставь на паузу",
        // навигация (online → Яндекс)
        "поехали домой", "найди заправку", "где ближайшая парковка",
        "Телеграм: @sda_ai",                           // credit #2 — во второй половине, не подряд с #1
        // режим движения / энергия
        "переключи на электричество", "переключи на топливо", "режим экономии",
        // экран / темы / сцены
        "сделай экран ярче", "включи тёмную тему", "режим сна", "режим кинотеатра",
        // инфо (офлайн, отвечает машина)
        "сколько осталось заряда", "какой запас хода", "проверь давление в шинах"
    };

    /** Read SrBaseSession.getCurrentDirect() on the session object (which seat woke SR), or VoiceStateCache. */
    private static int currentDirect(Object session, int fallback) {
        try {
            // Q07: the session is null here and getVwState is a state code, not a seat. Stock keeps the seat of the last
            // wake here (1 driver, 2 passenger, 3/4 rear, 19 outside): the same value it answers «Пассажир, чем могу помочь» by.
            try {
                java.lang.reflect.Field f = Class.forName("com.incall.apps.speechassistant.common.VoiceStateCache")
                        .getDeclaredField("mLastSrDirect");
                f.setAccessible(true);
                int d = ((java.util.concurrent.atomic.AtomicInteger) f.get(null)).get();
                if (d >= 1) return d;
            } catch (ClassNotFoundException | NoSuchFieldException ignored) {}
            if (session != null) {
                Object v = session.getClass().getMethod("getCurrentDirect").invoke(session);
                int d = ((Number) v).intValue();
                if (d >= 1 && d <= 5) return d;
                if (d >= 16) return d;
            }
            for (String cn : VW_CLASSES) {
                try {
                    Class<?> vc = Class.forName(cn);
                    int vw = (Integer) vc.getMethod("getVwState").invoke(null);
                    if (vw >= 1 && vw <= 5) return vw;
                    if (vw >= 16) return vw;
                    break;
                } catch (ClassNotFoundException ignored) {}
            }
            return fallback;
        } catch (Throwable t) { return fallback; }
    }
    public static Object ruGuideSleep() { return ruGuide(); }
    public static Object ruGuideSr()    { return ruGuide(); }
    /** Rotating idle hint — just the example phrase itself (no "Скажи:" prefix). */
    private static Object ruGuide() {
        try {
            String ex = cap(RU_HINTS[(int) ((System.currentTimeMillis() / 3000) % RU_HINTS.length)]);
            java.util.ArrayList<String> texts = new java.util.ArrayList<>();
            texts.add(ex);
            java.util.ArrayList<Integer> colors = new java.util.ArrayList<>();
            colors.add(2);
            Class<?> tc = Class.forName("com.incall.apps.speechassistant.guide.TextColorBean");
            return tc.getConstructor(java.util.ArrayList.class, java.util.ArrayList.class).newInstance(texts, colors);
        } catch (Throwable t) { Log.e(TAG, "ruGuide", t); return null; }
    }

    /** Route a recognized Russian phrase: known car command -> arbitration, else -> chat LLM.
     *  Shared by the live ASR path (feed wp==3) and the test trigger (StandNluReceiver `cmd`). */
    public static void handlePhrase(String query) {
        if (query == null || query.trim().isEmpty()) return;
        String cmd = mapCommand(query);
        if (cmd != null) { Log.i(TAG, "-> car command: " + cmd); execArbitration(cmd); }
        else if (offlineOnly()) {
            Log.i(TAG, "-> offline, unrecognized: " + query);
            showOnScreen("Не поняла команду", TYPE_FEEDBACK); // local reply; online chat not wired yet
        }
        else { Log.i(TAG, "-> chat: " + query); sendToCloud(query); }
    }



    // ---------------------------------------------------------------- zones (REPLACES RuBridge versions)

    // ---------------------------------------------------------------- new slot parsers

    // ---------------------------------------------------------------- main translator

    // ---------------------------------------------------------------- carControl: seats

    // ---------------------------------------------------------------- carControl: climate

    // ---------------------------------------------------------------- carControl: windows / roof

    // ---------------------------------------------------------------- carControl: doors / trunks / locks

    // ---------------------------------------------------------------- carControl: lights

    // ---------------------------------------------------------------- carControl: mirrors / steering / wipers

    // ---------------------------------------------------------------- carControl: HUD / displays

    // ---------------------------------------------------------------- autoPilot (ALL unsafe)

    // ---------------------------------------------------------------- driving / energy / suspension

    // ---------------------------------------------------------------- comfort / scenario / misc modes

    // ---------------------------------------------------------------- connectivity / charging

    // ---------------------------------------------------------------- cameras / DVR

    // ---------------------------------------------------------------- phone

    // ---------------------------------------------------------------- sound / media

    // ---------------------------------------------------------------- vehicleInfo

    // ---------------------------------------------------------------- smartHome (all cloud)

    // ---------------------------------------------------------------- appPageControl

    // ---------------------------------------------------------------- generalControl (bare context words — LAST)

// ---------------------------------------------------------------------------------------------
// NOTE on cloud/chat domains (weather, lifeService, carKnowledge, xiaoAnWorldview,
// sceneArrangement, memorizeUserInfo): these are useCloud — an unmatched phrase falls through to
// null and handlePhrase() routes it to the chat LLM, which is the intended behaviour. Weather
// questions can optionally be pre-translated (今天天气怎么样 etc., see RESULT.md) when the stock
// cloud stack is reachable.
// NOTE: langOf() from the TODO list is intentionally NOT added — the 328-intent catalog contains
// no system-language intent to feed it.

    /** ru2zh voice/test routing: RU phrase → Chinese command → stock pipeline; else offline reply. */
    public static void handlePhraseZh(String ru) {
        if (ru == null || ru.trim().isEmpty()) return;
        // ≤1 letter is never a command or a question — it's ASR tail noise (a stray «а» after the
        // greeting). Sending it to the backend costs a round-trip and a spoken "Извините, я могу только…".
        // Drop silently (owner's request, 2026-09-05).
        int letters = 0;
        for (int i = 0; i < ru.length(); i++) if (Character.isLetter(ru.charAt(i))) letters++;
        if (letters <= 1) { Log.i(TAG, "ru2zh: ignoring ≤1-letter phrase: [" + ru + "]"); return; }
        String low = ru.toLowerCase();
        // Voice on/off for our TTS engine (ASR/commands are independent of tts_config, so BOTH work):
        //   "верни заводскую озвучку" → stock TTS;  "включи русскую озвучку" → our TTS. Effect after restart.
        if (isRestoreVoice(low)) {
            speak("Возвращаю заводскую озвучку. Перезапустите ассистента.");
            restoreTts(); showOnScreen("Заводская озвучка (перезапустите ассистента)", TYPE_FEEDBACK); return;
        }
        if (isEnableVoice(low)) {
            ensureTtsRegistered();
            speak("Русская озвучка включена. Перезапустите ассистента.");
            showOnScreen("Русская озвучка (перезапустите ассистента)", TYPE_FEEDBACK); return;
        }
        // ru2zhAll: one command for a simple phrase, one per clause for a compound («закрой все окна и
        // выключи климат»); null if any clause is unknown → the whole phrase goes to the backend.
        final String[] zhs = ru2zhAll(ru, wakeZone);
        if (zhs != null && zhs.length == 1) {
            // Remember it (try #1) in case the native NLU can't handle the injected command: we then
            // re-send the RU to the LLM for one corrected try. See onLocalCommandFailed.
            noteInjected(ru, zhs[0], 1);
            Log.i(TAG, "ru2zh: [" + ru + "] -> " + zhs[0]); injectZh(zhs[0]); confirmIfSilent(confirmation(low)); return;
        }
        if (zhs != null) {
            // Compound: inject clause by clause, serialized like the backend's multi-intent path. Not noted
            // for the failure retry — a retry would re-run the clauses that already worked.
            Log.i(TAG, "ru2zh compound: [" + ru + "] -> " + java.util.Arrays.toString(zhs));
            new Thread(new Runnable() { public void run() {
                for (int i = 0; i < zhs.length; i++) {
                    if (i > 0) { try { Thread.sleep(900); } catch (InterruptedException ignored) {} }
                    injectZh(zhs[i]);
                }
                confirmIfSilent(CONFIRM_DONE);
            }}).start();
            return;
        }
        if (openAppByName(low)) return;
        if (low.equals("привет") || low.equals("здравствуй") || low.equals("здравствуйте")) {
            speak("Здравствуйте");
            return;
        }
        if (low.contains("что ты умеешь") || low.contains("что умеешь")) {
            speak("Управляю окнами, люком, климатом, сиденьями и светом, открываю приложения.");
            return;
        }
        // Free-form (weather / knowledge / chat): the command map missed → send RAW Russian to our
        // backend. It answers in Russian and may return car commands (executed inside sendToCloud).
        // No RU->ZH translation here anymore.
        if (offlineOnly()) { Log.i(TAG, "ru2zh: unrecognized (offline): " + ru); showOnScreen("Не поняла команду", TYPE_FEEDBACK); }
        else { Log.i(TAG, "ru2zh: -> backend chat: " + ru); sendToCloud(ru); }
    }

    /*
     * ============================================================================================
     *  ЕЩЁ НЕ РЕАЛИЗОВАНО — карта оставшихся интентов для будущего «настоящего» bridge/архитектуры.
     * ============================================================================================
     *  Ниже — весь оставшийся оффлайн-поддерживаемый каталог (useLocalRule, см.
     *  stand/knowledge/09-offline-coverage.md и nlu_catalog/intent_label.txt). Имена даны как
     *  canonical `domain@INTENT`, чтобы их можно было напрямую подставлять в arbFlat(...).
     *
     *  Текущий mapCommand — плоский if-else PoC. Логичная замена (идея на будущее):
     *    - декларативная таблица правил {regex/keywords -> domain@intent + slot-extractors},
     *    - отдельные «слот-парсеры» (zone/grade/number/color/percent/mode/target),
     *    - реестр интентов с ожидаемыми слотами (валидация до отправки),
     *    - раздельные бэкенды актуации: (A) arbitration-inject (текущий), (B) прямой CarProperty
     *      (нужны car-permissions в манифесте, см. doc 08 Option B), (C) CaSdkManager in-process.
     *  ⚠️ Значения зон/режимов (DRIVER/HEATING/…) пока не подтверждены на железе — возможно
     *     обработчик ждёт китайские литералы. Проверять на авто (echo распознан, но не исполняется).
     *
     *  ---- КЛИМАТ (доп.) ----
     *   carControl@SET_AIR_CONDITIONER_AIRFLOW / SET_AIR_CONDITIONER_AIR_BLOW /
     *     SWITCH_AIR_CONDITIONER_AIRFLOW  — направление обдува (лицо/ноги/стекло/комбо)
     *   carControl@SWITCH_AIR_CIRCULATION  — переключить рециркуляцию/забор
     *   carControl@TEMPERATURE_SYNC        — синхронизировать климат-зоны  («синхронизируй климат»)
     *   carControl@SET_FRAGRANCE           — выбрать аромат/интенсивность (SWITCH_FRAGRANCE уже есть)
     *
     *  ---- СИДЕНЬЯ (позиция/эргономика) ----  (SET_SEAT_HEAT/VENTILATE/MASSAGE уже реализованы)
     *   carControl@ADJUST_SEAT_POSITION          — двигать сиденье вперёд/назад/выше/ниже
     *   carControl@ADJUST_SEAT_BACKREST_POSITION / OP_SEAT_BACKREST_POSITION — спинка (наклон)
     *   carControl@ADJUST_SEAT_CUSHION_POSITION  — подушка
     *   carControl@ADJUST_LUMBAR_POSITION        — поясничный подпор
     *   carControl@ADJUST_LEG_SUPPORT_POSITION / ADJUST_LEG_SUPPORT_LENGTH — подколенный упор
     *   carControl@SET_SEAT_FOOTREST             — подставка для ног (реклайнер)
     *   carControl@SWITCH_SEAT_MASSAGE_MODE      — режим массажа
     *   carControl@SWITCH_SAVE_SEAT_POSITION     — сохранить/вызвать позицию памяти
     *   carControl@EXCLUSIVE_FRONT_PASSENGER     — режим «королевы» переднего пассажира
     *
     *  ---- СВЕТ (доп.) ----  (OP_DIPPED_BEAM/OP_HIGH_BEAM/OP_WARNING_LIGHT/OP_READ_LIGHTS есть)
     *   carControl@OP_OUTLINE_LIGHT / OP_SIDE_LIGHTS — габариты/боковые
     *   carControl@OP_REAR_FOG_LIGHT   — задний противотуманный
     *   carControl@OP_TAILLIGHT        — задние фонари
     *   carControl@OP_TRUNK_LIGHT      — свет багажника
     *   carControl@SET_DIPPED_BEAM_HEIGHT — корректор фар
     *   carControl@CONTROL_LIGHT_SHOW  — световое шоу
     *
     *  ---- ПОДСВЕТКА САЛОНА (доп.) ----  (OP_MOOD_LIGHTS/BRIGHTNESS/COLOR есть)
     *   carControl@SET_MOOD_LIGHTS_MODE / SET_MOOD_LIGHTS_THEME / SET_MOOD_LIGHTS_GRADIENT
     *   carControl@SWITCH_M_L_EFFECT / SWITCH_M_L_COLOR — переключить эффект/цвет
     *
     *  ---- ЗЕРКАЛА / РУЛЬ ----  (OP_STEER_WARM/REAR_MIRROR_WARM есть)
     *   carControl@ADJ_REARVIEW_MIRROR    — регулировка зеркал (наклон)
     *   carControl@OP_REAR_MIRROR_CONTROL / OP_REAR_MIRROR_AUTO — сложить/авто-затемнение
     *   carControl@ADJ_STEER_DIRECTION    — вылет/наклон руля
     *   carControl@SET_STEER_WARM         — уровень обогрева руля (0..N)
     *   carControl@SET_STEERING_STYLE     — тип усилителя (комфорт/спорт)
     *
     *  ---- ДВОРНИКИ ----  (SET_WIPER_SPEED/WASH_WIPER есть)
     *   carControl@SET_WIPER_SENSITIVITY  — чувствительность авто-режима
     *   carControl@REPAIR_WIPER           — сервисное положение
     *
     *  ---- ОКНА / КУЗОВ (доп.) ----  (SET_CAR_WINDOW/SUNROOF/SUN_SHADE/WINDOW_LOCK/TRUNK/FRUNK есть)
     *   carControl@SET_WINDOW_SUN_SHADE   — шторки окон
     *   carControl@OP_LOCK_CLOSE_WINDOW   — дозакрытие окон при блокировке
     *   carControl@OP_RAIN_CLOSE_WINDOW   — автозакрытие при дожде
     *   carControl@OP_UPPER_TAILGATE / OP_LOWER_TAILGATE / OP_SPLIT_TAILGATE — секции двери багажника
     *   carControl@OP_REAR_COMPARTMENT    — задний отсек/шторка
     *   carControl@OP_FRUNK_LOCK          — замок переднего багажника
     *   carControl@OP_CHARGING_PORT_COVER — лючок зарядки
     *   carControl@OP_FUEL_TANK_CAP / OP_FUEL_TANK_LOCK — лючок/замок бака
     *   carControl@OP_CHILD_SAFETY_LOCK   — детский замок
     *   carControl@OPEN_ALL_ONE_KEY / CLOSE_ALL_ONE_KEY — всё открыть/закрыть одной командой
     *   carControl@OP_APPROACH_UNLOCK / OP_LEAVE_LOCK — авто-замок по подходу/отходу
     *   carControl@OP_COMFORTABLE_ENTRY / OP_EASY_ACCESS — комфортный вход/выход
     *
     *  ---- КЛИМАТ-ХОЛОДИЛЬНИК ----
     *   carControl@OP_REFRIGERATOR / SET_REFRIGERATOR / SET_REFRIGERATOR_MODE / SET_REFRIGERATOR_DOOR
     *
     *  ---- РЕЖИМЫ ЕЗДЫ / ПОДВЕСКА / ЭНЕРГИЯ ----
     *   carControl@SET_DRIVING_MODE       — режим движения (эко/спорт/снег…)
     *   carControl@SET_SCENARIO_MODE      — сценарный режим
     *   carControl@SET_ENERGY_MODE / SET_ENERGY_RECOVERY / SET_POWER_TYPE — рекуперация/тип тяги
     *   carControl@SET_SUSP_HEIGHT / SET_SUSP_DAMPING — высота/жёсткость подвески
     *   carControl@ONE_CLICK_LEVELING     — выравнивание кузова
     *   carControl@CARRY_GOODS_EASILY     — режим погрузки (опустить подвеску)
     *
     *  ---- ЭКРАН / HUD (доп.) ----  (SET_DISPLAY_BRIGHTNESS/OP_HUD/SET_HUD_BRIGHTNESS/HEIGHT есть)
     *   carControl@SET_DISPLAY_MODE / SET_DISPLAY_ORIENTATION / SET_DISPLAY_COLOR_TEMPERATURE
     *   carControl@SET_DISPLAY_EYE_PROTECTION / SET_FONT_SIZE / OP_AUTO_DISPLAY_BRIGHTNESS
     *   carControl@OP_DISPLAY_POWER / OP_DISPLAY_CLEAN / DISPLAY_SLEEP / DISPLAY_UNSLEEP
     *   carControl@SET_PDISPLAY_ANGLE     — угол пассажирского экрана
     *   carControl@SET_HUD_ANGLE / SET_HUD_MODE / SWITCH_HUD_DISPLAY_MODE / SWITCH_HUD_COLOR_MODE
     *
     *  ---- ЗВУК (доп.) ----  (SET_VOLUME/MUTE/UNMUTE есть)
     *   carControl@SWITCH_SOUND_FIELD     — звуковое поле (водитель/весь салон)
     *   carControl@OP_QUALITY_ENHANCE     — улучшение качества звука
     *   carControl@SET_ALARM_TONE         — тон сигнала
     *   carControl@STOP_BROADCAST         — прекратить озвучку (barge-in)
     *
     *  ---- КАМЕРЫ / DVR / ОБЗОР ----
     *   carControl@TAKE_PHOTO / TAKE_VIDEO / TAKE_DVR_REC / CAPTUR_DVR — фото/видео/регистратор
     *   carControl@SET_SVM                — круговой обзор (360)
     *   carControl@OP_STREAM_MEDIA_REAR_VIEW / OP_REAR_VIEW_ASSIST — стрим-зеркало/помощь
     *   appPageControl@SWITCH_CAMERA_VIEW — переключить ракурс камеры
     *
     *  ---- СВЯЗЬ / ПИТАНИЕ / РЕЖИМЫ ----
     *   carControl@OP_BT / OP_WIFI / OP_HOTSPOT / OP_WIRELESS_CHARGING / OP_DISCHARGE_POWER (V2L)
     *   carControl@OP_SENTINEL_MODE       — режим часового
     *   carControl@OP_PRIVACY_MODE / OP_MOBILE_DND — приватность / не беспокоить
     *   carControl@OP_CAR_WASH / OP_PICKUP_MODE / OP_EXTENSION_MODE — мойка / подача / кемпинг
     *   carControl@OP_NO_MIC_KARAOKE      — караоке без микрофона
     *   carControl@SET_NAP_MODE_TIME / SET_NAP_MODE_CLOCK / EXTENDED_NAP_MODE_TIME — режим отдыха
     *
     *  ---- ОПОВЕЩЕНИЯ / БЕЗОПАСНОСТЬ ----
     *   carControl@OP_SEATBELT_UNFASTENED_ALERT / OP_FORGET_PHONE_ALERT / OP_LOW_SPEED_ALERT
     *   carControl@CONTROL_SCHEDULE_ALERTS — напоминания/расписание
     *
     *  ---- ПЕРСОНАЛИЗАЦИЯ АССИСТЕНТА / ТЕМЫ ----
     *   carControl@CHANGE_SPRITE / SET_NICKNAME / SET_WAKE_WORD — аватар/имя/слово пробуждения
     *   carControl@SET_VOICE_TONE / SWITCH_VOICE_TONE — голос/тон TTS
     *   carControl@SET_THEME / SWITCH_THEME / SET_WALLPAPER / SWITCH_WALLPAPER — темы/обои
     *   carControl@REGISTER_VOICEPRINT    — регистрация голосового отпечатка
     *   carControl@OP_VOICE_SUMMON        — призыв авто (парковка по запросу)
     *   carControl@OP_NON_WUW             — режим без слова пробуждения (free-talk)
     *   carControl@GENERATE_THEMED_PODCAST — генерация подкаста (вероятно облако/LLM)
     *   carControl@VEHICLE_UNDEFINED      — заглушка, не реализовывать
     *
     *  ---- МЕДИА (расширенное управление, mediaControl@) ----  (NEXT/PREVIOUS_MEDIA есть)
     *   PAUSE_PLAYBACK / RESUME_PLAYBACK / RESTART_PLAYBACK / END_PLAYBACK — пауза/продолжить/стоп
     *   ADJUST_PLAYBACK_PROGRESS / SET_PLAYBACK_TIME_POINT / SPEED_PLAY — перемотка/скорость
     *   SET_PLAYBACK_MODE (повтор/шафл) / SWITCH_MEDIA_AUDIO / SET_SOUND_QUALITY / SET_DEFINITION
     *   COLLECT_MEDIA / CANCEL_COLLECT_MEDIA / OP_MEDIA_FAVORITES_LIST — избранное
     *   CONTROL_PLAYLIST / LIST_SELECTION_MEDIA / ASK_CURRENT_MEDIA / OP_LYRICS / DOWNLOAD_SONG
     *   media@CONTROL_MEDIA_SOURCE / PLAY_AUDIO_PROGRAM / OP_RADIO_HARDWARE — источник/радио
     *
     *  ---- ТЕЛЕФОН (phone@, требует интеграции с диалером) ----
     *   CALL_REQUEST / ANSWER_CALL / HANG_UP / IGNORE_CALL / REDIAL / CALL_BACK / CANCEL_DIAL
     *   SEARCH_PHONEBOOK / SYNC_PHONE_CONTACT / RENDER_PHONE_CONTACT / RENDER_CALL_HISTORY / LIST_SELECTION_PHONE
     *
     *  ---- НАВИГАЦИЯ (navi@, нужна интеграция с картой; в РФ — Яндекс/OsmAnd, см. changan-navigation) ----
     *   OP_NAVIGATION / LBS_ROUTE / SEARCH_POI / SET_HOME_POI / SET_COMPANY_POI / COLLECT_ADDRESS
     *   ASK_LOCATION / ASK_DISTANCE_LEFT / ASK_TIME_LEFT / ASK_TRAFFIC_CONDITION — запросы (нужен ответ TTS)
     *   ZOOM_MAP_SIZE / SWITCH_VIEW_ORIENTATION / SWITCH_MAP_LAYER / SET_BROADCAST / VIEW_FULL_ROUTE …
     *
     *  ---- ОБЩЕЕ УПРАВЛЕНИЕ ДИАЛОГОМ (generalControl@, контекстное — нужен стейт диалога) ----
     *   CONFIRM / CONFIRM_NO / CANCEL / CONTINUE / BACK / NEXT / PREVIOUS / PAUSE / OPEN / CLOSE / CLOSE_ALL
     *   LIST_SELECTION / PAGE_SELECTION / CATEGORY_SELECT / DELETE_INDEX / TURN_PAGE / COLLECT — выбор из списка/карточки
     *   appPageControl@BACK_DESKTOP / GO_BACK_PAGE / CONTROL_APP / CONTROL_PAGE / EXIT_VOICE_ASSIST — навигация по UI
     *
     *  ---- ЗАПРОСЫ О МАШИНЕ (vehicleInfo@, read-back -> ответ голосом/карточкой) ----
     *   CURRENT_MILEAGE / REMAINING_POWER / TIRE_PRESSURE / IN_CAR_AIR_QUALITY / RESPONSE_TIME_INQUIRY
     *
     *  ---- АВТОПИЛОТ / ADAS (autoPilot@, БЕЗОПАСНОСТЬ — вероятно заблокировано/требует подтверждений) ----
     *   OP_ACC / OP_IACC / OP_NCA / OP_AUTO_DRIVE / OP_AUTO_PARKING / PARKING / OP_FOLLOW_CAR
     *   SET_CRUISE_CAR_SPEED / SET_CRUISE_FOLLOW_GAP / KEEP_LANE_DRIVE / SPEED_LIMIT_CONTROL …
     *
     *  ---- РАЗГОВОРНОЕ / ЗНАНИЯ (useCloud -> наш LLM-редирект, НЕ arbitration; см. doc 02/09) ----
     *   weather@* / carKnowledge@* / lifeService@* / xiaoAnWorldview@* / sceneArrangement@* /
     *   memorizeUserInfo@*  — сейчас всё, что вернуло mapCommand==null, уходит в sendToCloud().
     * ============================================================================================
     */

    /**
     * Russian phrase -> native arbitration JSON (carControl@INTENT + flat slots), or null for chat.
     * Slots are emitted FLAT on nluResults[0] (the contract NluManager.onArbitrationResult reads:
     * nlpBean.semantic.slots = nluResults[0]; adapters read slots.optString(<name>)). Zones/grades use
     * the normalized codes the stock handlers key on. SA only DISPATCHES; the perm-holding SDA apps
     * (airconditioner/chaircontrol/...) actuate — so no car permission is needed in SA's manifest.
     * Covers the everyday offline families (all useLocalRule). See stand/knowledge/09-offline-coverage.md.
     */
    static String mapCommand(String t) {
        String s = t.toLowerCase().replace('ё', 'е');
        String rid = "stand-" + System.currentTimeMillis();
        boolean on = isOn(s), off = isOff(s);
        // zone: use the one spoken in the phrase; if none, fall back to the detected speaking seat.
        String zone = zoneOf(s);
        if (zone.isEmpty()) zone = zoneCode(wakeZone);
        String grade = gradeOf(s);
        java.util.HashMap<String,String> sl = new java.util.HashMap<>();

        // ---------- CLIMATE ----------
        // temperature (absolute number wins; else relative grade)
        if (s.contains("температур") || s.contains("градус")
            || ((s.contains("теплее") || s.contains("холодн")) && !s.contains("сиден") && !s.contains("руль") && !s.contains("зеркал"))) {
            int n = numIn(s);
            sl.clear();
            if (n >= 16 && n <= 33) { sl.put("temperature", String.valueOf(n)); }
            else if (!grade.isEmpty()) { sl.put("direction", grade); }
            else return null;
            if (!zone.isEmpty()) sl.put("target", zone);
            return arbFlat(rid, t, "carControl", "SET_AIR_CONDITIONER_TEMPERATURE", sl);
        }
        // fan speed / air volume (exclude windshield-defrost and seat-vent, handled elsewhere)
        boolean fanCtx = !s.contains("лобов") && !s.contains("стекл") && !s.contains("сиден") && !s.contains("кресл");
        if (fanCtx && (s.contains("обдув") || s.contains("вентилятор")
            || (s.contains("воздух") && (s.contains("больш")||s.contains("мень")||numIn(s)>0))
            || ((s.contains("скорост")||s.contains("сил")) && s.contains("вент")))) {
            sl.clear();
            int n = numIn(s);
            if (n >= 1 && n <= 7) sl.put("gear", String.valueOf(n));
            else if (!grade.isEmpty()) sl.put("direction", grade);
            else if (on) sl.put("action", "OPEN"); else if (off) sl.put("action", "CLOSE");
            if (!sl.isEmpty()) return arbFlat(rid, t, "carControl", "SET_AIR_CONDITIONER_FAN_SPEED", sl);
        }
        // defrost (windshield / rear glass)
        if (s.contains("обдув лоб") || s.contains("обдув стек") || s.contains("обогрев стек")
            || s.contains("обогрев лоб") || s.contains("разморозк") || s.contains("отпот")) {
            sl.clear(); sl.put("action", off ? "CLOSE" : "OPEN");
            if (s.contains("задн") || s.contains("заднего")) sl.put("target", "REAR");
            return arbFlat(rid, t, "carControl", "SET_DEFROST", sl);
        }
        // recirculation
        if (s.contains("рециркул") || (s.contains("забор") && s.contains("воздух")) || s.contains("циркуляц")) {
            sl.clear(); sl.put("action", off ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "SET_AIR_CIRCULATION", sl);
        }
        // AC mode: heating / cooling / auto
        if (s.contains("режим") && (s.contains("охлажд")||s.contains("обогрев")||s.contains("авто")||s.contains("эко"))) {
            String mode = s.contains("охлажд")||s.contains("холод") ? "COOLING"
                        : s.contains("обогрев")||s.contains("тепл") ? "HEATING"
                        : s.contains("эко") ? "ECO" : "AUTO";
            sl.clear(); sl.put("mode", mode);
            return arbFlat(rid, t, "carControl", "SET_AIR_CONDITIONER_MODE", sl);
        }
        // climate on/off
        if (s.contains("кондиционер") || s.contains("климат") || (s.contains("обдув") && (on||off))) {
            sl.clear(); sl.put("action", off ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "OP_AIR_CONDITIONER", sl);
        }

        // ---------- WINDOWS / ROOF / SHADE ----------
        if (s.contains("окн") || (s.contains("стекл") && (s.contains("опусти")||s.contains("подним")||s.contains("откр")||s.contains("закр")))) {
            sl.clear();
            int pct = numIn(s);
            boolean pctPhrase = s.contains("процент") || s.contains("%");
            if (s.contains("наполовину")) sl.put("value", "50");
            else if (pctPhrase && pct >= 0 && pct <= 100) sl.put("value", String.valueOf(pct));
            else {
                // "опусти/открой окно" => OPEN (lower glass); "подними/закрой" => CLOSE.
                // NB: don't use isOff() here — it treats "опусти" as off, but lowering a window is OPEN.
                boolean close = s.contains("подним") || s.contains("закр") || s.contains("выключ") || s.contains("отключ");
                sl.put("action", close ? "CLOSE" : "OPEN");
            }
            if (!zone.isEmpty()) sl.put("name", zone);
            return arbFlat(rid, t, "carControl", "SET_CAR_WINDOW", sl);
        }
        if (s.contains("люк") || s.contains("панорам") && s.contains("крыш")) {
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "SET_SUNROOF_WINDOW", sl);
        }
        if (s.contains("шторк") || s.contains("солнцезащит")) {
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "SET_SUN_SHADE", sl);
        }
        if (s.contains("блокир") && s.contains("окон") || s.contains("замок окон")) {
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "OP_WINDOW_LOCK", sl);
        }

        // ---------- DOORS / TRUNK ----------
        if (s.contains("багажник") || s.contains("дверь багаж")) {
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "OP_TRUNK", sl);
        }
        if (s.contains("капот") || s.contains("передний багаж") || s.contains("фрунк")) {
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "OP_FRUNK", sl);
        }
        if (s.contains("двер") && (s.contains("запр")||s.contains("отопр")||s.contains("заблок")||s.contains("разблок")||s.contains("замок")||s.contains("замки"))) {
            sl.clear(); sl.put("action", (s.contains("отопр")||s.contains("разблок")||s.contains("открой")) ? "UNLOCK" : "LOCK");
            return arbFlat(rid, t, "carControl", "OP_DOOR_LOCK", sl);
        }
        if (s.contains("двер") && (on||off)) {
            sl.clear(); sl.put("action", off ? "CLOSE" : "OPEN");
            if (!zone.isEmpty()) sl.put("name", zone);
            return arbFlat(rid, t, "carControl", "OP_CAR_DOOR", sl);
        }

        // ---------- SEATS ---------- ("массаж"/"вентиляция" imply the seat even without the word "сиденье")
        if (s.contains("сиден") || s.contains("кресл") || s.contains("массаж")) {
            if (s.contains("подогрев") || s.contains("обогрев") || s.contains("греть") || s.contains("тепл")) {
                sl.clear(); int n = numIn(s);
                if (n >= 0 && n <= 3) sl.put("gear", String.valueOf(n));
                else if (!grade.isEmpty()) sl.put("direction", grade);
                else sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
                if (!zone.isEmpty()) sl.put("target", zone);
                return arbFlat(rid, t, "carControl", "SET_SEAT_HEAT", sl);
            }
            if (s.contains("вентил") || s.contains("обдув") || s.contains("продув")) {
                sl.clear(); int n = numIn(s);
                if (n >= 0 && n <= 3) sl.put("gear", String.valueOf(n));
                else if (!grade.isEmpty()) sl.put("direction", grade);
                else sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
                if (!zone.isEmpty()) sl.put("target", zone);
                return arbFlat(rid, t, "carControl", "SET_SEAT_VENTILATE", sl);
            }
            if (s.contains("массаж")) {
                sl.clear(); int n = numIn(s);
                if (n >= 1 && n <= 3) sl.put("gear", String.valueOf(n));
                else sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
                if (!zone.isEmpty()) sl.put("target", zone);
                return arbFlat(rid, t, "carControl", "SET_SEAT_MASSAGE", sl);
            }
        }

        // ---------- LIGHTS ----------
        if (s.contains("подсветк") || s.contains("атмосферн") || (s.contains("салон") && s.contains("свет"))) {
            if (s.contains("ярче") || s.contains("темнее") || (s.contains("яркост"))) {
                sl.clear(); if (!grade.isEmpty()) sl.put("direction", grade);
                int n = numIn(s); if (n>0 && n<=100) sl.put("value", String.valueOf(n));
                if (!sl.isEmpty()) return arbFlat(rid, t, "carControl", "SET_MOOD_LIGHTS_BRIGHTNESS", sl);
            }
            String c = colorOf(s);
            if (s.contains("цвет") || !c.isEmpty()) {
                sl.clear(); if (!c.isEmpty()) sl.put("color", c);
                return arbFlat(rid, t, "carControl", "SET_MOOD_LIGHTS_COLOR", sl);
            }
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "OP_MOOD_LIGHTS", sl);
        }
        if (s.contains("ближн") && s.contains("свет") || s.contains("фары") && !s.contains("дальн")) {
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "OP_DIPPED_BEAM", sl);
        }
        if (s.contains("дальн") && s.contains("свет")) {
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "OP_HIGH_BEAM", sl);
        }
        if (s.contains("аварийк") || s.contains("аварийн")) {
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "OP_WARNING_LIGHT", sl);
        }
        if ((s.contains("плафон")||s.contains("свет салона")||s.contains("свет в салоне")||s.contains("лампоч")) ) {
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "OP_READ_LIGHTS", sl);
        }

        // ---------- STEERING / MIRRORS / WIPERS ----------
        if (s.contains("руль") || s.contains("руля")) {
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "OP_STEER_WARM", sl);
        }
        if (s.contains("зеркал") && (s.contains("обогрев")||s.contains("подогрев")||s.contains("греть"))) {
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "REAR_MIRROR_WARM", sl);
        }
        if (s.contains("помой") && (s.contains("лобов")||s.contains("стекл")) || s.contains("омыват") || s.contains("брызни")) {
            return arbFlat(rid, t, "carControl", "WASH_WIPER", new java.util.HashMap<String,String>());
        }
        if (s.contains("дворник") || s.contains("щетк")) {
            sl.clear(); int n = numIn(s);
            if (n>=1 && n<=4) sl.put("gear", String.valueOf(n));
            else sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "SET_WIPER_SPEED", sl);
        }

        // ---------- SOUND / MEDIA ----------
        if ((s.contains("звук")||s.contains("громкост")||s.contains("громче")||s.contains("тише")) && !s.contains("сигнал")) {
            if (s.contains("выключи звук")||s.contains("без звука")||s.contains("отключи звук")||s.contains("замолчи")) {
                return arbFlat(rid, t, "carControl", "MUTE", new java.util.HashMap<String,String>());
            }
            if (s.contains("включи звук")||s.contains("со звуком")) {
                return arbFlat(rid, t, "carControl", "UNMUTE", new java.util.HashMap<String,String>());
            }
            sl.clear(); int n = numIn(s);
            if (n>=0 && n<=40 && (s.contains("громкост")||s.contains("на "))) sl.put("volume", String.valueOf(n));
            else if (!grade.isEmpty()) sl.put("direction", grade);
            else return null;
            return arbFlat(rid, t, "carControl", "SET_VOLUME", sl);
        }
        if (s.contains("следующ") && (s.contains("трек")||s.contains("песн")||s.contains("композиц"))) {
            return arbFlat(rid, t, "mediaControl", "NEXT_MEDIA", new java.util.HashMap<String,String>());
        }
        if ((s.contains("предыдущ")||s.contains("прошл")) && (s.contains("трек")||s.contains("песн")||s.contains("композиц"))) {
            return arbFlat(rid, t, "mediaControl", "PREVIOUS_MEDIA", new java.util.HashMap<String,String>());
        }

        // ---------- HUD / DISPLAY / FRAGRANCE ----------
        if (s.contains("hud") || s.contains("проекц")) {
            if (s.contains("яркост")||s.contains("ярче")||s.contains("темнее")) {
                sl.clear(); if (!grade.isEmpty()) sl.put("direction", grade);
                return arbFlat(rid, t, "carControl", "SET_HUD_BRIGHTNESS", sl);
            }
            if (s.contains("выше")||s.contains("ниже")||s.contains("подним")||s.contains("опусти")||s.contains("высот")) {
                sl.clear(); sl.put("direction", (s.contains("ниже")||s.contains("опусти")||isOff(s)) ? "MINUS" : "PLUS");
                return arbFlat(rid, t, "carControl", "SET_HUD_HEIGHT", sl);
            }
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "OP_HUD", sl);
        }
        if ((s.contains("экран")||s.contains("дисплей")) && (s.contains("яркост")||s.contains("ярче")||s.contains("темнее"))) {
            sl.clear(); if (!grade.isEmpty()) sl.put("direction", grade);
            int n = numIn(s); if (n>0 && n<=100) sl.put("value", String.valueOf(n));
            if (!sl.isEmpty()) return arbFlat(rid, t, "carControl", "SET_DISPLAY_BRIGHTNESS", sl);
        }
        if (s.contains("ароматизат") || s.contains("аромат") || s.contains("парфюм")) {
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "SWITCH_FRAGRANCE", sl);
        }

        return null; // not a known command -> chat
    }

    /** Parse a Russian spoken number in the 16..33 range (tens + units). */
    static int ruNum(String s) {
        // «ноль/нуль» = 0 — ниже 0 считался бы «нет числа» (v > 0), и «холодильник на ноль градусов» включал холодильник
        if (s.contains("ноль") || s.contains("нуль") || s.contains("нулю")) return 0;
        // 10..19 first (a teen must not be split into tens+units)
        String[] teen = {"десят","одиннадцат","двенадцат","тринадцат","четырнадцат","пятнадцат","шестнадцат","семнадцат","восемнадцат","девятнадцат"};
        for (int i = teen.length - 1; i >= 1; i--) if (s.contains(teen[i])) return 10 + i; // longer/teen forms before "десят"
        // tens: strip the tens word so its letters ("двА" in "двАдцать", "трИ" in "трИдцать") don't leak into units
        int tens = 0;
        if (s.contains("тридцат"))      { tens = 30; s = s.replace("тридцат", " "); }
        else if (s.contains("двадцат")) { tens = 20; s = s.replace("двадцат", " "); }
        else if (s.contains("десят"))   return 10; // plain "десять"
        // units: scan high->low so "восемь"(8) is matched before "семь"(7) inside it
        int units = 0;
        String[] u = {"","один","два","три","четыр","пят","шест","сем","восем","девят"};
        for (int i = u.length - 1; i >= 1; i--) if (s.contains(u[i])) { units = i; break; }
        int v = tens + units;
        return v > 0 ? v : -1;
    }

    /**
     * Build the arbitration-result JSON that NluManager.onArbitrationResult consumes.
     * Slots are written FLAT onto nluResults[0] (key = slot name, value = normalized value) — the
     * shape the stock adapters read (nlpBean.semantic.slots = nluResults[0]; slots.optString(name)).
     * A redundant "slots" array is added too, so any GSON/DM consumer that expects the list form
     * still resolves. requestId starts with "stand" so the patched onArbitrationResult keeps it.
     */
    /** Convert a backend nluResult (domain/intent/slots) into a Chinese command phrase that the
     *  PROVEN actuation pipeline (injectZh -> onFinalAsrResult) executes. Needed because
     *  execArbitration/onArbitrationResult does NOT actuate carControl on this firmware (verified:
     *  the result is delivered but no SoaBridge.setProperty follows). Returns null for intents we
     *  don't map (caller falls back to execArbitration). Relative temp/volume work without any
     *  telemetry — the native DM applies the step against the live setpoint. */
    static String zhFromNlu(String intent, java.util.Map<String,String> s) {
        if (intent == null) return null;
        String action = s.get("action"), adj = s.get("adjustment"), mode = s.get("mode");
        String temp = s.get("temperature"), fg = s.get("fuzzy_grade"), value = s.get("value");
        boolean open = "OPEN".equals(action), close = "CLOSE".equals(action);
        switch (intent) {
            case "SET_AIR_CONDITIONER_TEMPERATURE":
                if ("MIN".equals(fg)) return "温度调到最低";
                if ("MAX".equals(fg)) return "温度调到最高";
                if ("SUBTRACT".equals(adj)) return "温度调低";
                if ("ADD".equals(adj))      return "温度调高";
                if (temp != null && temp.matches("\\d+")) return "把温度调到" + temp + "度";
                return null;
            case "OP_AIR_CONDITIONER":        return close ? "关闭空调" : "打开空调";
            case "SET_AIR_CONDITIONER_MODE": {
                if ("AIR_PURIFICATION".equals(mode)) return close ? "关闭空气净化" : "打开空气净化";
                String v = null;
                if ("COOLING".equals(mode))            v = "制冷";
                else if ("HEATING".equals(mode))       v = "制热";
                else if ("RAPID_COOLING".equals(mode)) v = "强力制冷";
                else if ("RAPID_HEATING".equals(mode)) v = "强力制热";
                else if ("AUTO".equals(mode))          v = "空调自动";
                else if ("ENERGY_SAVING".equals(mode)) v = "空调节能";
                if (v == null) return null;
                return (close ? "关闭" : "打开") + v + "模式";
            }
            case "SET_AIR_CIRCULATION": {
                String z = s.get("zone");
                if ("IN".equals(z))  return close ? "关闭内循环" : "打开内循环";
                if ("OUT".equals(z)) return close ? "关闭外循环" : "打开外循环";
                return null;
            }
            case "SET_DEFROST":               return close ? "关闭除雾" : "打开除雾";
            case "OP_STEER_WARM":             return close ? "关闭方向盘加热" : "打开方向盘加热";
            case "REAR_MIRROR_WARM":          return close ? "关闭后视镜加热" : "打开后视镜加热";
            case "SET_SEAT_HEAT":             return close ? "关闭座椅加热" : "打开座椅加热";
            case "SET_SEAT_VENTILATE":        return close ? "关闭座椅通风" : "打开座椅通风";
            case "SET_CAR_WINDOW":
                if ("OPEN_HALF".equals(action)) return "车窗开一半";
                return close ? "关闭车窗" : "打开车窗";
            case "SET_SUNROOF_WINDOW":        return close ? "关闭天窗" : "打开天窗";
            case "OP_TRUNK":                  return close ? "关闭后备箱" : "打开后备箱";
            case "OP_FRUNK":                  return close ? "关闭前备箱" : "打开前备箱";
            case "OP_DOOR_LOCK":              return close ? "解锁" : "锁车";
            case "CLOSE_ALL_ONE_KEY":         return "一键关闭";
            case "OP_MOOD_LIGHTS":            return close ? "关闭氛围灯" : "打开氛围灯";
            case "OP_DIPPED_BEAM":            return close ? "关闭大灯" : "打开大灯";
            case "OP_HIGH_BEAM":              return close ? "关闭远光灯" : "打开远光灯";
            case "OP_READ_LIGHTS":            return close ? "关闭阅读灯" : "打开阅读灯";
            case "WASH_WIPER":                return "喷水洗玻璃";
            case "SET_VOLUME":
                // NOTE (verified on car 2026-09-05): 音量调到最大/最小 is NOT recognized as max/min — the
                // stock NLU parses "音量调到…" and then ASKS for a number, so fuzzy_grade is dropped here.
                // Also: 声音/音量 control the ASSISTANT/TTS volume, NOT media. A media-scoped phrase is still
                // TODO (媒体音量/音乐音量…) so "сделай музыку громче" hits the music stream.
                if ("SUBTRACT".equals(adj)) return "声音小一点";
                if ("ADD".equals(adj))      return "声音大一点";
                if (value != null && value.matches("\\d+")) return "音量调到" + value;
                return null;
            case "MUTE":                      return "静音";
            case "UNMUTE":                    return "取消静音";
            case "NEXT_MEDIA":                return "下一首";
            case "PREVIOUS_MEDIA": case "LAST_MEDIA": return "上一首";
            case "CONTROL_MEDIA_SOURCE":
                if ("PAUSE".equals(action)) return "暂停";
                if ("PLAY".equals(action))  return "播放音乐";
                return null;
            case "SET_ENERGY_MODE":
                if (mode != null && !mode.isEmpty()) return "切换到" + mode + "模式";
                return null;
            default: return null;
        }
    }

    /** Remove RUAccent stress marks for on-screen display: a '+' is a stress marker only when it sits
     *  right before a Russian vowel, so we strip exactly those and leave any literal '+' (math, "C++"). */
    static String stripStress(String s) {
        if (s == null || s.indexOf('+') < 0) return s;
        return s.replaceAll("\\+(?=[аеёиоуыэюяАЕЁИОУЫЭЮЯ])", "");
    }

    /** Normalize a backend slot value for the stock adapters: a pure number with a trailing unit
     *  ("22度", "22°", "22℃", "22°C") becomes the bare number "22" (what SET_*_TEMPERATURE etc.
     *  expect — the local mapper emits String.valueOf(n)). Non-numeric values pass through. */
    private static String normSlot(String v) {
        if (v == null) return "";
        String t = v.trim();
        if (t.matches("-?\\d+(?:度|°C|℃|°)")) return t.replaceAll("(?:度|°C|℃|°)$", "");
        return t;
    }

    private static String arbFlat(String rid, String query, String domain, String intent,
                                  java.util.Map<String,String> slots) {
        try {
            JSONObject nlu = new JSONObject();
            nlu.put("query", query).put("domain", domain).put("intent", intent);
            org.json.JSONArray arr = new org.json.JSONArray();
            for (java.util.Map.Entry<String,String> e : slots.entrySet()) {
                nlu.put(e.getKey(), e.getValue());                    // flat: adapters read this
                arr.put(new JSONObject().put("name", e.getKey())
                        .put("value", e.getValue()).put("normalizedValue", e.getValue()));
            }
            String dirStr = (wakeZone >= 16) ? "outside" : "front_left";
            return new JSONObject().put("query", query).put("requestId", rid).put("nluType", 0).put("zoneId", wakeZone)
                    .put("direction", dirStr)
                    .put("nluResults", new org.json.JSONArray().put(nlu)).toString();
        } catch (Throwable e) { return null; }
    }

    /** Execute a semantic result via the native pipeline (NluManager.onArbitrationResult).
     *  json must contain nluResults[{domain,intent,slots}] + requestId starting "stand". */
    public static void execArbitration(final String json) {
        new Thread(new Runnable() { public void run() {
            try {
                Class<?> nm = Class.forName("com.incall.apps.speechassistant.nlu.NluManager");
                Object mgr = nm.getMethod("getInstance").invoke(null);
                java.lang.reflect.Method mth = nm.getDeclaredMethod("onArbitrationResult", String.class);
                mth.setAccessible(true);
                mth.invoke(mgr, json);
                Log.i(TAG, "execArbitration sent: " + json);
            } catch (Throwable t) { Log.e(TAG, "execArbitration", t); }
        }}).start();
    }

    /** True if this arbitration JSON is one of ours (so the patched onArbitrationResult
     *  can drop native Chinese results and keep only ours). */
    public static boolean isOurs(String json) {
        if (json == null) return false;
        try { return new JSONObject(json).optString("requestId", "").startsWith("stand"); }
        catch (Throwable t) { return json.contains("\"requestId\":\"stand"); }
    }

    /** Called from patched SrPgsManager.appendPgs: replace native (Mandarin) text with our RU text. */
    public static String swap(String original) {
        String v = lastText;
        if (v != null && !v.isEmpty()) return v;   // our RU text (ASR partial, or the final once decoded)
        // lastText empty = mid-utterance with GigaAM (no streaming partials). Suppress the stock partial
        // ENTIRELY — whether Chinese (would flash CJK) or a Russian prefix from the RU-patched iFlytek SR
        // (it leaks e.g. "За" that then doubles with the final → "Зазапусти музыку"). Show only the final.
        return "";
    }

    /** Capitalize the first letter (for on-screen hints and the dictated-phrase echo). */
    static String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** True if the string contains any CJK (Chinese) character. */
    static boolean hasCjk(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '一' && c <= '鿿') return true;   // CJK Unified Ideographs
        }
        return false;
    }

    private RuBridge() {}
}