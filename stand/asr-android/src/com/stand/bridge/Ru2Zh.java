/*
 * Russian Voice Assistant modification for Changan A06 (C390).
 * Copyright (c) 2026 Tecrow.
 * Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only. See LICENSE.
 * Independent modification — not affiliated with or endorsed by Changan Automobile.
 */
package com.stand.bridge;

/**
 * ru2zh command mapper. RU -> ZH: turns a recognized Russian phrase into ONE canonical Chinese
 * command the stock NLU understands, or null (= not a car command -> chat / offline stub).
 * Pure string rules, no Android dependencies — the very same file is compiled by the offline unit
 * tests: {@code sh ru2zh/translate-task/tests/run_tests.sh}. Used by RuBridge (live ASR path +
 * test trigger) and by its legacy mapCommand() helpers.
 *
 * Order matters: specific contexts (seats, defrost) are checked before generic ones (temperature,
 * volume); bare context words ("да", "дальше", "назад") are matched last so they never shadow
 * real commands. UNSAFE intents (trunk/frunk/doors/tailgates/autopilot/parking/summon) are gated
 * behind ALLOW_UNSAFE — see unsafeGate().
 */
final class Ru2Zh {
    private Ru2Zh() {}

    /** Physical-actuation commands (doors, trunk, autopilot, parking, summon) are only injected
     *  when this is true. Release: enabled. Not final so the unit tests can exercise both paths. */
    static boolean ALLOW_UNSAFE = true;

    // Field data 14–17.09.2026 (backend failure log, ~180 cars): these stock intents come back UNKNOWN on
    // every car that tried them, in every Chinese wording the backend retried — the A06 offline NLU simply
    // does not have them. Mapping them only costs the driver two failed attempts and a "не умею"; with the
    // switch off the phrase goes straight to the backend (radio → opens the radio/music app, the rest → an
    // honest one-line answer). Flip a switch back on if a firmware ever gains the intent.
    static boolean FM_RADIO_OFFLINE   = false;   // 打开收音机: 19 failures / 15 cars, retries 开启收音机, 播放广播 failed
    static boolean WIPER_ONOFF_OFFLINE = false;  // 打开雨刮 15/6 cars (+打开雨刮器/雨刷器/雨刷); 打开雨刮维修模式 5/4 cars
    static boolean DRIVE_MODE_OFFLINE = false;   // 切换到运动模式 12/8 cars (+运动驾驶模式 in 5 wordings) — SET_DRIVING_MODE
    static boolean SIDE_LIGHTS_OFFLINE = false;  // 打开示廓灯 7/5 cars, retry 打开位置灯 failed 4×

    /** Third-party brands: «открой яндекс музыку», «включи яндекс радио», «вк музыка» name an APP the backend
     *  launches (appActions) — never the stock player / FM app the generic words would map to. */
    private static final java.util.regex.Pattern BRAND = java.util.regex.Pattern.compile(
        "(?<![а-яa-z])(яндекс|yandex|вконтакте|вк музык|спотифай|spotify|ютуб|youtube|рутуб|rutube|кинопоиск|телеграм|telegram"
        + "|ватсап|whatsapp|2гис|дубльгис|сбер|литрес|окко|иви|wink|винк|звук ?ком)");

    /** Action verbs — a phrase with two verb clauses joined by «и/затем/потом» is a COMPOUND command. */
    private static final java.util.regex.Pattern CMD_VERB = java.util.regex.Pattern.compile(
        "(?<![а-я])(включи|включай|выключи|выключай|отключи|открой|открывай|закрой|закрывай|поставь|сделай|подними|опусти"
        + "|запусти|останови|убавь|прибавь|увеличь|уменьши|переключи|смени|вруби|выруби|погаси|зажги|сложи|разложи"
        + "|подогрей|охлади|прогрей|установи|приоткрой)(?![а-я])");
    private static final String CONJ = "\\s+(?:и|а также|а потом|и потом|затем|потом|после этого)\\s+";

    /** Clauses of a compound command («закрой все окна и выключи климат»), or null if the phrase is simple. */
    static String[] clausesOf(String s) {
        String[] parts = s.trim().split(CONJ);
        if (parts.length < 2) return null;
        for (String p : parts) if (!CMD_VERB.matcher(p).find()) return null;   // «дуй в лицо и в ноги» stays simple
        return parts;
    }

    /** All commands of a phrase, in order: one element for a simple phrase, one per clause for a compound.
     *  null if ANY clause is not a known command — a half-executed request is worse than a backend round-trip. */
    static String[] ru2zhAll(String t, int speakerDir) {
        String s = t.toLowerCase().replace('ё', 'е');
        String[] parts = clausesOf(s);
        if (parts == null) { String one = ru2zh(t, speakerDir); return one == null ? null : new String[]{one}; }
        // More than three clauses is the backend's job. It must NOT fall back to the single mapper: that one
        // mixes the clauses' slots («открой окно и … и выключи свет» came out as «close the window»).
        if (parts.length > 3) return null;
        String[] out = new String[parts.length];
        for (int i = 0; i < parts.length; i++) { out[i] = ru2zh(parts[i], speakerDir); if (out[i] == null) return null; }
        return out;
    }

    /** Told about every command unsafeGate() blocked (RuBridge logs it + shows a hint). */
    interface UnsafeListener { void blocked(String zh); }
    static volatile UnsafeListener onUnsafeBlocked;

    /** Sentinel returned by unsafeGate() when blocked: ru2zh() turns it into null at the very end, so a
     *  blocked «пауза парковки» cannot fall through to the generic «пауза» (暂停) of a later domain. */
    private static final String BLOCKED = "\u0000blocked";

    private static String unsafeGate(String zh) {
        if (ALLOW_UNSAFE) return zh;
        UnsafeListener l = onUnsafeBlocked;
        if (l != null) l.blocked(zh);
        return BLOCKED;
    }

    // ---------------------------------------------------------------- zones

    /** Zone named in the phrase ("" if none). Knows rear-left / rear-right / front-row.
     *  The stock NLU distinguishes 主驾/副驾/前排/后排/后排左/后排右/所有 (see dm_cmd.json name lists).
     *  Order matters: rear+side combos BEFORE the bare "пассажир" check — «заднему правому
     *  пассажиру» must resolve to REAR_RIGHT, not front PASSENGER. "сзади" is matched explicitly
     *  ("задн" does not cover it), while "назад" must NOT trigger rear (seat-move command). */
    static String zoneOf(String s) {
        boolean rear = s.contains("задн") || s.contains("сзади");
        boolean right = hasWord(s, RIGHT_WORD), left = hasWord(s, LEFT_WORD);
        if (rear && right) return "REAR_RIGHT";
        if (rear && left) return "REAR_LEFT";
        if (s.contains("за водителем")) return "REAR_LEFT";
        if (s.contains("за пассажиром")) return "REAR_RIGHT";
        if (rear) return "REAR";
        // «все окна», «всем», «везде», «оба сиденья» — but NOT «массаж ВСЕго тела / ВСЕй спины»:
        // that is one seat (the speaker's), not every seat in the car
        if (hasWord(s, ALL_WORD) && !s.contains("все тело") && !s.contains("всю спину")) return "ALL";
        if (s.contains("пассажир")) return "PASSENGER";   // front-right seat (副驾)
        boolean front = s.contains("передн") || s.contains("спереди");
        if (front && right) return "PASSENGER";  // "переднее правое"
        if (front && left) return "DRIVER";
        if (front) return "FRONT";
        if (s.contains("водит")) return "DRIVER";
        // bare side words, lowest priority (LHD: левое = водительское, правое = пассажирское)
        if (right) return "PASSENGER";
        if (left) return "DRIVER";
        return "";
    }

    // Whole-word side / "all" detectors. contains("прав") also hit «поПРАВь», «наПРАВь», «ПРАВда»,
    // «исПРАВь» and turned the driver's command into a passenger one; contains("все") hit «ВСЕго
    // тела», «ВСЕгда». Cyrillic-aware boundaries: Java's \b is not reliable for Cyrillic across
    // JDK/ART versions, so use explicit look-arounds.
    private static final String ADJ = "(ый|ая|ое|ые|ую|ого|ой|ом|ому|ым|ыми|ых)";
    private static final java.util.regex.Pattern RIGHT_WORD = word("справа|прав" + ADJ);
    private static final java.util.regex.Pattern LEFT_WORD  = word("слева|лев" + ADJ);
    private static final java.util.regex.Pattern DRL_WORD   = word("дхо|ходов" + ADJ + "|ходовые");
    private static final java.util.regex.Pattern ALL_WORD   = word("все|всем|всех|всеми|весь|везде|оба|обе|обоим|каждое|каждому");
    private static java.util.regex.Pattern word(String alt) {
        return java.util.regex.Pattern.compile("(?<![а-я])(?:" + alt + ")(?![а-я])");
    }
    private static boolean hasWord(String s, java.util.regex.Pattern p) { return p.matcher(s).find(); }

    /** Zone code -> Chinese zone word for command templates. */
    private static String zhZone(String code) {
        if ("DRIVER".equals(code)) return "主驾";
        if ("PASSENGER".equals(code)) return "副驾";
        if ("FRONT".equals(code)) return "前排";
        if ("REAR".equals(code)) return "后排";
        if ("REAR_LEFT".equals(code)) return "后排左";
        if ("REAR_RIGHT".equals(code)) return "后排右";
        if ("ALL".equals(code)) return "所有";
        return "";
    }

    // ---------------------------------------------------------------- new slot parsers

    /** OFF-verbs isOff() does not know: "погаси/потуши свет", "деактивируй", "отруби", "заглуши".
     *  ("гаси"/"туши" cover "гасите"/"тушите"; "притуши" = dim, handled earlier in zhLights.) */
    private static boolean offWordOf(String s) {
        return s.contains("погаси") || s.contains("потуши") || s.contains("деактив") || s.contains("отруби")
            || s.contains("заглуши") || s.contains("вырубай") || s.contains("убери запах")
            || ((s.contains("гаси") || s.contains("туши")) && !s.contains("притуши") && !s.contains("пригаси"));
    }

    /** "повысь/увеличь/прибавь" — relative-plus verbs gradeOf() does not know. */
    private static boolean plusWordOf(String s) {
        return s.contains("повы") || s.contains("увелич") || s.contains("подбав") || s.contains("прибав");
    }
    /** "понизь/уменьши/убавь" — relative-minus verbs gradeOf() does not know. */
    private static boolean minusWordOf(String s) {
        return s.contains("пониз") || s.contains("уменьш") || s.contains("убав");
    }

    /** Percent of window/roof opening: "на 30 процентов", "наполовину" -> 50. -1 if absent. */
    private static int percentOf(String s) {
        if (s.contains("наполовину") || s.contains("на половину") || s.contains("половин")) return 50;
        if (s.contains("процент") || s.contains("%")) {
            int n = numIn(s);
            if (n >= 1 && n <= 100) return n;
        }
        if (s.contains("на треть")) return 33;
        if (s.contains("на четверть")) return 25;
        return -1;
    }

    /** Ordinal / index: "первый".."десятый", "номер 3". -1 if absent. */
    private static int ordinalIn(String s) {
        if (s.contains("перв")) return 1;
        if (s.contains("втор")) return 2;
        if (s.contains("трет")) return 3;
        if (s.contains("четверт")) return 4;
        if (s.contains("пят") && !s.contains("пятнадцат")) return 5;
        if (s.contains("шест") && !s.contains("шестнадцат")) return 6;
        if (s.contains("седьм")) return 7;
        if (s.contains("восьм")) return 8;
        if (s.contains("девят") && !s.contains("девятнадцат")) return 9;
        if (s.contains("десят") && !s.contains("надцат")) return 10;
        if (s.contains("номер")) { int n = numIn(s); if (n > 0) return n; }
        return -1;
    }

    /** RU app name -> Chinese app name for 打开{app}. Empty if not a known app. */
    private static String zhAppOf(String s) {
        if (s.contains("музык")) return "音乐";
        if (s.contains("настройк")) return "设置";
        if (s.contains("камер")) return "相机";
        if (s.contains("телефон")) return "电话";
        if (s.contains("радио")) return FM_RADIO_OFFLINE ? "收音机" : "";   // no FM app intent on the A06 (field data)
        if (s.contains("браузер") || s.contains("интернет")) return "浏览器";
        if (s.contains("видео") && !s.contains("сними") && !s.contains("запиши")) return "视频";
        if (s.contains("карт") && !s.contains("карточк")) return "地图";
        if (s.contains("галере") || s.contains("альбом") || s.contains("фотограф")) return "相册";
        if (s.contains("магазин")) return "应用商店";
        if (s.contains("календар")) return "日历";
        return "";
    }

    /** RU POI category -> Chinese POI word for nearby search. Empty if none. */
    private static String zhPoiOf(String s) {
        if (s.contains("заправ") || s.contains("азс") || s.contains("бензин")) return "加油站";
        if (s.contains("зарядк") || s.contains("зарядн")) return "充电站";
        if (s.contains("парковк") || s.contains("стоянк")) return "停车场";
        if (s.contains("кафе") || s.contains("кофе")) return "咖啡店";
        if (s.contains("ресторан") || s.contains("поесть") || s.contains("еда")) return "餐厅";
        if (s.contains("туалет")) return "卫生间";
        if (s.contains("аптек")) return "药店";
        if (s.contains("больниц") || s.contains("госпитал")) return "医院";
        if (s.contains("супермаркет") || s.contains("магазин продукт")) return "超市";
        if (s.contains("банкомат")) return "ATM";
        if (s.contains("гостиниц") || s.contains("отел")) return "酒店";
        if (s.contains("мойк")) return "洗车店";
        return "";
    }

    /** Driving mode -> Chinese mode name. Empty if none. */
    private static String zhDriveModeOf(String s) {
        if (s.contains("спорт")) return "运动模式";
        if (s.contains("эко") || s.contains("экономичн")) return "经济模式";
        if (s.contains("комфорт")) return "舒适模式";
        if (s.contains("стандарт") || s.contains("обычн")) return "标准模式";
        if (s.contains("снег") || s.contains("снежн") || s.contains("зимн")) return "雪地模式";
        if (s.contains("бездорож") || s.contains("оффроуд") || s.contains("внедорож")) return "越野模式";
        return "";
    }

    /** Scenario / space mode (SET_SCENARIO_MODE) -> Chinese name from the dm catalog. */
    private static String zhScenarioOf(String s) {
        // names verbatim from the dm catalog mode list (dm_cmd.json): 影院模式|观影空间|大床模式|休息空间|
        // 前舱露营|后舱露营|露营嗨唱模式|营地守护|儿童模式|K歌房模式|提神模式|晕车缓解|化妆空间|共赏星空|
        // 女王驾到|仙女副驾|仙女下凡|浪漫接驾|零重力休息 (there is NO plain 露营模式)
        if (s.contains("кино") || s.contains("фильм")) return "影院模式";
        if (s.contains("кроват") || s.contains("спальн") || s.contains("для сна")) return "大床模式";
        if ((s.contains("отдых") || s.contains("рассла")) && !s.contains("отдыхай")     // «всё, отдыхай» dismisses the assistant
            && (s.contains("режим") || s.contains("простран") || s.contains("зон") || s.contains("включи") || s.contains("хочу")))
            return "休息空间";
        if (s.contains("кемпинг") || s.contains("палатк") || s.contains("лагер")) {
            if (s.contains("охран") || s.contains("сторож") || s.contains("защит")) return "营地守护";
            if (s.contains("караоке") || s.contains("пой") || s.contains("петь") || s.contains("песн")) return "露营嗨唱模式";
            if (s.contains("передн") || s.contains("спереди") || s.contains("в салоне")) return "前舱露营";
            return "后舱露营";                                    // camping = rear cabin by default
        }
        if (s.contains("детск")) return "儿童模式";
        if (s.contains("караоке")) return "K歌房模式";
        if (s.contains("взбодр") || s.contains("бодрост")) return "提神模式";
        if (s.contains("укачив") || s.contains("тошн")) return "晕车缓解";
        if (s.contains("макияж") || s.contains("косметик")) return "化妆空间";
        if (s.contains("звезд") || s.contains("звёзд")) return "共赏星空";                     // «посмотреть на звёзды»
        if (s.contains("королев")) return "女王驾到";                                          // «режим королевы» (副驾)
        if (s.contains("фея") || s.contains("феи") || s.contains("принцесс")) return "仙女副驾";
        if (s.contains("романти")) return "浪漫接驾";
        return "";
    }

    /** Free text after a trigger word ("позвони маме" -> "маме"). Empty if nothing follows. */
    private static String tailAfter(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) return "";
        String t = s.substring(i + key.length()).trim();
        // strip leading fillers (repeat until stable: "меня в аэропорт" -> "аэропорт")
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String f : new String[]{"на ", "в ", "к ", "до ", "мне ", "меня ", "нас ", "пожалуйста ", "быстрее ", "давай "}) {
                if (t.startsWith(f)) { t = t.substring(f.length()).trim(); changed = true; }
            }
        }
        return t;
    }

    // ---------------------------------------------------------------- main translator

    /**
     * RU→ZH: translate a recognized Russian command into ONE natural Chinese command that the stock
     * NLU understands, or null (→ offline stub / chat). Order matters: specific contexts (seats,
     * defrost) are checked before generic ones (temperature, volume); bare context words
     * ("да", "дальше", "назад") are matched last so they never shadow real commands.
     */
    /** Negation + question/narrative guard: true = the utterance must NOT actuate anything.
     *  Shared by ru2zh() and the N-best arbitration (ru2zhBest applies it to the TOP hypothesis
     *  so a lower alternative missing the "не" cannot bypass it). */
    /** «на единичку/двойку/троечку/четвёрку/пятёрку…» — числительные-существительные, которых numIn не знает.
     *  Используется как fallback для n в ru2zh(): обдув, подогрев/вентиляция сиденья, дворники и т.п. -1 если нет. */
    static int levelWordOf(String s) {
        if (s.contains("единичк") || s.contains("единиц")) return 1;
        if (s.contains("двойк") || s.contains("двоечк")) return 2;
        if (s.contains("тройк") || s.contains("троечк")) return 3;
        if (s.contains("четверк") || s.contains("четверочк")) return 4;
        if (s.contains("пятерк") || s.contains("пятерочк")) return 5;
        if (s.contains("шестерк") || s.contains("шестерочк")) return 6;
        if (s.contains("семерк") || s.contains("семерочк")) return 7;
        return -1;
    }

    static boolean isGuarded(String s) {
        // negation: "не закрывай окно" must not actuate anything -> chat/stub
        if (s.contains(" или ")) return true;   // «мужской голос или женский», «последовательный гибрид или параллельный» — a question
        if (s.contains("не включ") || s.contains("не выключ") || s.contains("не откр") || s.contains("не закр")
            || s.contains("не вруб") || s.contains("не выруб") || s.contains("не отключ") || s.contains("не убир")
            || s.contains("не едь") || s.contains("не езжай") || s.contains("не гони"))
            return true;
        // question/narrative: "как пользоваться круизом", "почему окна потеют", "у меня дома…"
        // are chat, not commands — they must never actuate hardware (the native NLU would moo "ммм"
        // on a mis-mapped one). Anything here → ru2zh returns null → sendToCloud (the LLM).
        if (s.contains("как ") || s.contains("кака") || s.contains("како") || s.contains("каки")   // как/какая/какой/какое/какие/каком
            || s.contains("почему") || s.contains("зачем") || s.contains("что такое")
            || s.contains("что значит") || s.contains("что лучше") || s.contains("что за ")
            || s.contains("расскажи") || s.contains("объясни") || s.contains("подскажи")
            || s.contains("можно ли") || s.contains("стоит ли") || s.contains("сколько")
            || s.contains("где ") || s.contains("куда ") || s.contains("кто ") || s.contains("чей ")
            || s.contains("который") || s.contains("которая") || s.contains("правда ли")
            || s.contains("у меня") || s.contains("у нас") || s.contains("у соседа")
            || s.contains("вчера") || s.contains("опасн") || s.contains("опасен")
            || s.contains("оставил") || s.contains("забыл") || s.contains("потерял")
            || s.contains("не работает") || s.contains("сломал") || s.contains("перегорел") || s.contains("разбил")
            || (s.contains("когда ") && !s.contains("приедем") && !s.contains("доедем")))
            return true;
        // conversational / opinion / recommendation phrasings → LLM, never a command mapper
        return s.contains("пообщаемся") || s.contains("пообщаться") || s.contains("поговорим")
            || s.contains("поговорить") || s.contains("побеседу") || s.contains("обсудим")
            || s.contains("обсудить") || s.contains("на тему") || s.contains("что ты думаешь")
            || s.contains("как ты счита") || s.contains("твоё мнение") || s.contains("твое мнение")
            || s.contains("как думаешь") || s.contains("посоветуй") || s.contains("порекоменд")
            || s.contains("что ты знаешь") || s.contains("что умеешь") || s.contains("что можешь");
    }

    /** Same as {@link #ru2zh(String, int)} with the speaker assumed in the driver seat (tests / no seat info). */
    static String ru2zh(String t) { return ru2zh(t, 1); }

    /** @param speakerDir SrBaseSession direction of the seat that spoke (1=driver, 2=passenger, 3/4/5=rear);
     *                    used as the default zone when the phrase names none ("подогрей сиденье"). */
    static String ru2zh(String t, int speakerDir) {
        String s = t.toLowerCase().replace('ё', 'е');
        s = s.replaceAll("(?<![а-я])клима(?![а-я])", "климат");   // ASR drops the final «т» («включи клима двадцать шесть»)
        // A compound («закрой все окна и выключи климат») must not be mapped as ONE command: the single mapper
        // picked one clause and bled the other's slots into it (关闭所有空调). ru2zhAll() maps it clause by clause.
        if (clausesOf(s) != null) return null;
        if (BRAND.matcher(s).find()) return null;                  // a named third-party app → backend launches it
        // Vehicle-status questions ("сколько осталось заряда", "какой запас хода", "давление в шинах") are
        // answered by the CAR with live data — they must win over the question-word guard below, which
        // would otherwise ship them to the LLM (which cannot read car state). Checked first on purpose.
        { String vi = zhVehicleInfo(s); if (vi != null) return vi; }
        // «что за песня играет» / «что сейчас играет» is ASK_CURRENT_MEDIA (the car answers), not chat —
        // it must also beat the question-word guard.
        if (s.contains("что") && (s.contains("играет") || s.contains("песн") || s.contains("трек"))) {
            String sm = zhSoundMedia(s, false, "", -1); if (sm != null) return sm;
        }
        if (isGuarded(s)) return null;
        boolean off = isOff(s) || offWordOf(s);
        String zone = zoneOf(s); if (zone.isEmpty()) zone = zoneCode(speakerDir);
        String z = zhZone(zone);            // zone with speaker-seat default (seats, windows)
        String zx = zhZone(zoneOf(s));      // EXPLICIT zone only, "" if not named (climate: canonical 把温度调到24度)
        String grade = gradeOf(s);
        int n = numIn(s);
        if (n < 0) n = levelWordOf(s);   // «обдув на пятёрку»
        String r = translate(s, off, z, zx, grade, n, speakerDir);
        return BLOCKED.equals(r) ? null : r;
    }

    private static String translate(String s, boolean off, String z, String zx, String grade, int n, int speakerDir) {
        String r;
        if ((r = zhSeats(s, off, z, grade, n)) != null) return r;
        if ((r = zhClimate(s, off, zx, grade, n)) != null) return r;
        if ((r = zhWindowsRoof(s, off, z, n)) != null) return r;
        if ((r = zhDoorsLocks(s, off, z)) != null) return r;
        if ((r = zhLights(s, off, grade, speakerDir)) != null) return r;
        if ((r = zhMirrorsSteerWipers(s, off, grade, n)) != null) return r;
        if ((r = zhHudDisplays(s, off, grade)) != null) return r;
        if ((r = zhAutoPilot(s, off, n)) != null) return r;      // unsafe-gated inside
        if ((r = zhDriveEnergy(s, off, grade)) != null) return r;
        if ((r = zhComfortModes(s, off, n)) != null) return r;
        if ((r = zhConnectivity(s, off)) != null) return r;
        if ((r = zhCameraDvr(s, off)) != null) return r;
        // Navigation is handled ONLINE now: the backend returns appActions that launch Yandex Navi
        // (yandexnavi://). We do NOT map navigation to Chinese offline any more — that only triggered
        // the stock China-only navi app, useless in Russia. Nav phrases fall through to sendToCloud.
        // if ((r = zhNavi(s, off, grade)) != null) return r;   // disabled — see zhNavi javadoc
        if ((r = zhPhone(s)) != null) return r;
        if ((r = zhSoundMedia(s, off, grade, n)) != null) return r;
        if ((r = zhVehicleInfo(s)) != null) return r;
        if ((r = zhSmartHome(s, off, n)) != null) return r;
        if ((r = zhAppUi(s, off)) != null) return r;
        if ((r = zhGeneralUi(s, n)) != null) return r;           // bare context words — last
        return null; // not a known command
    }

    // ---------------------------------------------------------------- carControl: seats

    private static String zhSeats(String s, boolean off, String z, String grade, int n) {
        // 零重力模式 (+ 主驾/副驾/前排/后排/后排左/后排右/所有 prefixes are all catalog names).
        // Checked first: «разложи сиденье в невесомость» must not fall into the backrest rule.
        if (s.contains("невесом") || s.contains("гравит") || s.contains("зеро грав"))
            return (off ? "关闭" : "打开") + z + "零重力模式";
        boolean seat = s.contains("сиден") || s.contains("кресл") || s.contains("кресел") // "кресел" - fleeting vowel
            // народные имена сиденья: «продув пердака», «подогрей жопу», «помассируй булки»
            || s.contains("пердак") || s.contains("жоп") || s.contains("булк")
            || s.contains("пятую точку") || s.contains("пятой точк") || s.contains("пятая точка");
        // bare "включи подогрев" / "попогрейку" (без объекта) = подогрев сиденья говорящего;
        // руль/зеркала/стёкла/холодильник имеют свои домены и исключаются
        boolean bareHeat = (s.contains("подогрев") || s.contains("подогрей") || s.contains("попогрей") || s.contains("жопогрей"))
            && !s.contains("рул") && !s.contains("зеркал") && !s.contains("стек")
            && !s.contains("лоб") && !s.contains("холодильник") && !s.contains("морозилк") && !s.contains("двигател")
            && !s.contains("батаре") && !s.contains("аккум");
        // heating / ventilation / massage
        if (seat || s.contains("массаж") || s.contains("помассир") || s.contains("массир")
            || s.contains("поясниц") || s.contains("спинк") || bareHeat) {
            if (s.contains("подогре") || s.contains("обогре") || s.contains("грей") || s.contains("греть") || s.contains("тепл")) {
                if (n >= 1 && n <= 3) return z + "座椅加热" + n + "档";
                if (grade.equals("PLUS")) return z + "座椅加热调高一点";
                if (grade.equals("MINUS")) return z + "座椅加热调低一点";
                if (grade.equals("MAX")) return z + "座椅加热调到最高";
                return (off ? "关闭" : "打开") + z + "座椅加热";
            }
            if (s.contains("вентил") || s.contains("продув") || s.contains("обдув")) {
                if (n >= 1 && n <= 3) return z + "座椅通风" + n + "档";
                if (grade.equals("PLUS")) return z + "座椅通风调高一点";
                if (grade.equals("MINUS")) return z + "座椅通风调低一点";
                return (off ? "关闭" : "打开") + z + "座椅通风";
            }
            if (s.contains("массаж") || s.contains("массир")) {
                // The stock offline NLU has NO massage-type slot (SET_SEAT_MASSAGE = on/off + gear 1..3 +
                // stronger/weaker; SWITCH_SEAT_MASSAGE_MODE = cycle to the next mode). «массаж спины/плеч/
                // волновой» therefore just switches massage on; «другой/следующий/смени режим» cycles modes.
                if (s.contains("режим") || s.contains("друго") || s.contains("следующ") || s.contains("смени")
                    || s.contains("переключи") || s.contains("другой") || s.contains("другую") || s.contains("тип")
                    || s.contains("вид массажа")) return "换个按摩模式";                        // SWITCH_SEAT_MASSAGE_MODE
                if (n >= 1 && n <= 3) return z + "座椅按摩" + n + "档";                         // SET_SEAT_MASSAGE gear
                if (grade.equals("PLUS")) return "按摩强度调大一点";
                if (grade.equals("MINUS")) return "按摩强度调小一点";
                if (grade.equals("MAX")) return z + "座椅按摩3档";
                return (off ? "关闭" : "打开") + z + "座椅按摩";
            }
            if (s.contains("поясниц")) {                                                    // ADJUST_LUMBAR_POSITION
                if (grade.equals("MINUS") || s.contains("слабее") || s.contains("ниже")) return "腰部支撑调低一点";
                return "腰部支撑调高一点";
            }
            if (s.contains("спинк") || s.contains("наклон") || s.contains("разложи") || s.contains("откинь")) { // BACKREST
                if (s.contains("разложи") || s.contains("положи")) return "放倒" + z + "座椅靠背";
                if (s.contains("подними") && !s.contains("немного")) return "竖起" + z + "座椅靠背";
                if (s.contains("назад") || s.contains("откинь") || s.contains("опусти") || grade.equals("PLUS"))
                    return z + "座椅靠背角度调大一点";
                return z + "座椅靠背角度调小一点";
            }
            if (s.contains("подушк")) {                                                     // CUSHION
                return grade.equals("MINUS") || s.contains("ниже")
                    ? z + "座椅坐垫调低一点" : z + "座椅坐垫调高一点";
            }
            if (s.contains("сохрани") || s.contains("запомни")) return "保存当前座椅位置";   // SWITCH_SAVE_SEAT_POSITION
            if (s.contains("вперед") || s.contains("придвинь") || s.contains("пододвинь")) return z + "座椅往前调一点"; // ADJUST_SEAT_POSITION
            if (s.contains("назад") || s.contains("отодвинь")) return z + "座椅往后调一点";
            if (s.contains("выше") || s.contains("подним") || grade.equals("PLUS")) return z + "座椅位置调高一点";
            if (s.contains("ниже") || s.contains("опусти") || grade.equals("MINUS")) return z + "座椅位置调低一点";
        }
        if (s.contains("подставк") || s.contains("подножк") || (s.contains("ног") && s.contains("опор"))) {
            if (s.contains("длинн")) return "腿托调长一点";                                  // ADJUST_LEG_SUPPORT_LENGTH
            if (s.contains("короче")) return "腿托调短一点";
            if (off || s.contains("убери") || s.contains("сложи")) return "收起" + z + "脚托"; // SET_SEAT_FOOTREST
            if (s.contains("ниже") || grade.equals("MINUS")) return "腿部支撑调低一点";       // ADJUST_LEG_SUPPORT_POSITION
            if (s.contains("выше") || grade.equals("PLUS")) return "腿部支撑调高一点";
            return "打开" + z + "脚托";
        }
        if (s.contains("вип") && s.contains("пассажир")) return (off ? "关闭" : "打开") + "尊享副驾"; // EXCLUSIVE_FRONT_PASSENGER
        if (s.contains("комфортн") && (s.contains("посадк") || s.contains("выход")))
            return (off ? "关闭" : "打开") + "舒适进出";                                     // OP_COMFORTABLE_ENTRY
        return null;
    }

    // ---------------------------------------------------------------- carControl: climate

    private static String zhClimate(String s, boolean off, String z, String grade, int n) {
        // home devices ("кондиционер дома") belong to smartHome, not car climate
        if (s.contains("дома") || s.contains("домашн") || s.contains("квартир")) return null;
        // defrost first — "обогрев стекла" must not fall into temperature
        if (s.contains("обдув лоб") || s.contains("обдув стек") || s.contains("обогрев стек")
            || s.contains("обогрев лоб") || s.contains("обогрев задн") || s.contains("разморозк")
            || s.contains("отпот") || s.contains("запотел") || s.contains("потеют")) {
            boolean rear = s.contains("задн");
            if (s.contains("авто")) return (off ? "关闭" : "打开") + "自动除霜";              // catalog mode: 自动除霜
            return (off ? "关闭" : "打开") + (rear ? "后挡除霜" : "前挡除霜");
        }
        if ((s.contains("просушк") || s.contains("сушк") || s.contains("просуши") || s.contains("высуши"))
            && (s.contains("кондиц") || s.contains("испарител") || s.contains("климат") || s.contains("кондей")))
            return (off ? "关闭" : "打开") + "自干燥";                                        // catalog mode: 自干燥 (evaporator self-dry)
        if (s.contains("проветр") && (s.contains("разблок") || s.contains("открыт") || s.contains("отпир")))
            return (off ? "关闭" : "打开") + "主动解锁换气";                                  // catalog mode: 主动解锁换气
        if ((s.contains("батаре") || s.contains("аккум")) && (s.contains("прогре") || s.contains("подогре") || s.contains("нагре")))
            return (off ? "关闭" : "打开") + "电驱堵转加热";                                  // catalog: 电驱堵转加热 (e-drive battery heating)
        // complaints -> temperature ("мне холодно" = WARMER, "жарко/душно" = cooler)
        // "холодно( в машине)" — жалоба = ТЕПЛЕЕ; "холоднее/похолоднее" — просьба = ХОЛОДНЕЕ
        if (s.contains("замерз") || s.contains("продрог") || s.contains("тепла хочу") || s.contains("хочу тепла")
            || s.contains("дубак") || s.contains("колотун") || s.contains("холодрыг")
            || (s.contains("поддай") && (s.contains("жар") || s.contains("тепл")))
            || ((s.contains("холодно ") || s.endsWith("холодно")) && !s.contains("холоднее")))
            return "温度调高一点";
        if (s.contains("жарко") || s.contains("душно") || s.contains("мне жарко") || s.contains("парилка")
            || s.contains("запарил") || s.contains("вспотел") || s.contains("сауна") || s.contains("как в бане")
            || s.contains("пекло") || s.contains("духота")) return "温度调低一点";
        if (s.contains("прохладн")) return "温度调低一点";
        // «быстро/срочно/максимально охлади» -> 强力制冷 (мощное охлаждение), иначе обычное 制冷
        boolean strong = s.contains("быстр") || s.contains("скорее") || s.contains("срочно") || s.contains("сильн")
            || s.contains("максимал") || s.contains("на всю") || s.contains("мощн") || s.contains("резко");                                    // "сделай прохладнее"
        if ((s.contains("прогрей") || s.contains("согрей") || s.contains("натопи"))
            && (s.contains("салон") || s.contains("машин") || s.contains("тачк")))
            return strong ? "打开强力制热模式" : "打开制热模式";                            // «быстро прогрей» -> 强力制热
        if (s.contains("охлади") || s.contains("остуди")) return strong ? "打开强力制冷模式" : "打开制冷模式";
        if (s.contains("проветри") || (s.contains("свеж") && s.contains("воздух"))
            || ((s.contains("наружн") || s.contains("внешн") || s.contains("уличн") || s.contains("с улицы")) && s.contains("воздух")
                && !s.contains("авто") && !s.contains("забор"))) return "打开外循环";            // "свежий/наружный воздух"
        if ((s.contains("очист") && s.contains("воздух")) || (s.contains("очистител") && !s.contains("стекл"))
            || s.contains("ионизац"))                                                        // не «стеклоОЧИСТИТЕЛи»
            return (off ? "关闭" : "打开") + (s.contains("авто") ? "自动空气净化器" : "空气净化"); // catalog modes: 空气净化 | 自动空气净化器
        // "дует слишком сильно / слабо" -> fan complaints (но не про ветер на улице)
        if (s.contains("дует") && !s.contains("улиц") && !s.contains("ветер")) {
            if (s.contains("сильн") || s.contains("слишком")) return "风量调小一点";
            if (s.contains("слаб") || s.contains("еле")) return "风量调大一点";
        }
        // "печка" = heater ("отопление", "обогреватель")
        if (s.contains("печк") || s.contains("отоплен") || s.contains("обогреватель")) {
            if (off) return "关闭空调";
            if (grade.equals("PLUS")) return "温度调高一点";
            if (grade.equals("MINUS")) return "温度调低一点";
            return "打开制热模式";
        }
        // bare "включи обогрев" (без объекта — зеркала/руль/стёкла/сиденья ловятся в других доменах)
        // stem "рул" — "обогрев РУЛЯ" did not match "руль"/"рулев" and fell into cabin heating (bug found
        // by the hints harness: «включи обогрев руля» → 打开制热模式 instead of steering-wheel heat)
        if (s.contains("обогрев") && !s.contains("зеркал") && !s.contains("рул")
            && !s.contains("сиден") && !s.contains("кресл"))
            return off ? "关闭空调" : "打开制热模式";
        // temperature (excludes seat/steering/mirror/fridge heat — handled elsewhere)
        if ((s.contains("температур") || s.contains("градус") || s.contains("теплее") || s.contains("холодн"))
            && !s.contains("сиден") && !s.contains("кресл") && !s.contains("рул") && !s.contains("зеркал")
            && !s.contains("холодильник") && !s.contains("морозилк") && !s.contains("холодос") && !s.contains("цветов")) {
            if (s.contains("синхрон") || s.contains("одинаков")) return off ? "关闭温度同步" : "温度同步"; // TEMPERATURE_SYNC
            // absolute, half degrees allowed: «22.5», «22,5», «двадцать два с половиной», «23 и пять»
            // (hardware step is 0.5°C; the NLU num slot is [\d.]+, so 22.5 passes through as-is)
            String tv = tempValueOf(s);
            double td = tv == null ? -1 : Double.parseDouble(tv);
            if (td >= 16 && td <= 33 && !s.contains("на ")) return "把" + z + "温度调到" + tv + "度";
            if (grade.equals("MAX")) return "温度调到最高";
            if (grade.equals("MIN")) return "温度调到最低";
            boolean halfStep = s.contains("полградус") || s.contains("пол градус") || s.contains("половину градус")
                || s.contains("половина градус") || s.contains("половины градус");             // «на полградуса» = one 0.5 step
            String step = halfStep ? null : s.contains("полтора") ? "1.5" : (n >= 1 && n <= 8 && s.contains("на ")) ? String.valueOf(n) : null;
            // "теплее на 2 градуса" -> relative step with value
            if (grade.equals("PLUS") || plusWordOf(s)) return step != null ? "温度调高" + step + "度" : "温度调高一点";
            if (grade.equals("MINUS") || minusWordOf(s)) return step != null ? "温度调低" + step + "度" : "温度调低一点";
            if (td >= 16 && td <= 33) return "把" + z + "温度调到" + tv + "度";                  // «температуру на 22» (no direction word)
            return null;
        }
        // airflow direction: "дуй в лицо/в ноги/на стекло"
        if (s.contains("дуй") || s.contains("обдув в") || s.contains("обдув на") || s.contains("поток в")
            || s.contains("направь воздух") || ((s.contains("обдув") || s.contains("поток")) && (s.contains("лицо") || s.contains("ноги")))) {
            // «стекло/лобовое» — не направление обдува, а режим разморозки (единственный обдув стекла в NLU);
            // «обдув на стекло и ноги» = одна команда за фразу -> разморозка
            if (s.contains("стекл") || s.contains("лобов")) return (off ? "关闭" : "打开") + "前挡除霜";
            // «тело/грудь/корпус» — не значение каталога (面|脚|吹面吹脚), считаем верхом = лицо
            boolean face = s.contains("лицо") || s.contains("лиц") || s.contains("на меня") || s.contains("грудь")
                || s.contains("тело") || s.contains("корпус") || s.contains("торс")
                || s.contains("в рот") || s.contains("морд") || s.contains("в харю") || s.contains("в щи");
            boolean feet = s.contains("ноги") || s.contains("ног");
            if (face && feet) return "空调吹面吹脚";
            if (face) return "空调吹面";
            if (feet) return "空调吹脚";
        }
        if (s.contains("качани") || s.contains("шторк обдув") || s.contains("扫风"))
            return off ? "关闭扫风" : (s.contains("друго") || s.contains("смени") ? "换个扫风模式" : "打开扫风");
        // fan speed (exclude defrost + seat contexts, as in reference)
        boolean fanCtx = !s.contains("лобов") && !s.contains("стекл") && !s.contains("сиден") && !s.contains("кресл");
        if (fanCtx && (s.contains("обдув") || s.contains("вентилятор") || s.contains("дуй")
            || (s.contains("поток") && (s.contains("воздух") || s.contains("сильн") || s.contains("слаб")))
            || ((s.contains("скорост") || s.contains("сил")) && s.contains("вент")))) {   // «дуй сильнее», «поток воздуха слабее»
            if (n >= 1 && n <= 7) return "风量调到" + n + "档";
            if (grade.equals("MAX") || s.contains("на полную")) return "风量调到最大";
            if (grade.equals("MIN")) return "风量调到最小";
            if (grade.equals("PLUS") || plusWordOf(s) || s.contains("усил") || s.contains("быстр")) return "风量调大一点";
            if (grade.equals("MINUS") || minusWordOf(s) || s.contains("ослаб") || s.contains("медленн")) return "风量调小一点";
            return off ? "关闭空调风量" : "打开空调风量";
        }
        if (s.contains("рециркул") || s.contains("циркуляц") || (s.contains("забор") && s.contains("воздух"))) {
            if (s.contains("авто")) {                                                          // catalog modes: 自动内循环|自动外循环|自动内外循环
                String m = s.contains("внутр") || s.contains("рециркул") ? "自动内循环" : s.contains("внешн") || s.contains("наружн") ? "自动外循环" : "自动内外循环";
                return (off ? "关闭" : "打开") + m;
            }
            if (s.contains("переключ") || s.contains("смени")) return "切换内外循环";        // SWITCH_AIR_CIRCULATION
            return off ? "打开外循环" : "打开内循环";                                        // SET_AIR_CIRCULATION
        }
        // AC mode
        if (s.contains("климат") || s.contains("кондиц") || s.contains("кондер") || s.contains("кондей")
            || s.contains("кондишн") || s.contains("кондишк")) {
            if (s.contains("авто")) return "打开空调自动模式";                                // SET_AIR_CONDITIONER_MODE
            if (s.contains("охлажд")) return strong ? "打开强力制冷模式" : "打开制冷模式";
            if (s.contains("обогрев") || s.contains("нагрев")) return strong ? "打开强力制热模式" : "打开制热模式";
            if (s.contains("эконом") || s.contains("энергосбер")) return "打开空调节能模式"; // mode 节能
            if (s.contains("осушен") || s.contains("влажн")) return "打开除湿模式";
            String tv = tempValueOf(s);
            if (tv != null && Double.parseDouble(tv) >= 16 && Double.parseDouble(tv) <= 33) return "把温度调到" + tv + "度"; // "кондиционер на 22"
            // "климат/климат-контроль" = автоматический климат-контроль; "кондиционер" = просто AC.
            // Выключение у обоих одно: 关闭空调. Явная зона важнее авторежима ("климат пассажиру").
            if (s.contains("климат") && !off && z.isEmpty()) return "打开空调自动模式";        // SET_AIR_CONDITIONER_MODE
            // «кондиционер/кондей» = КОМПРЕССОР A/C, не вся установка: mappingRule нормализует 制冷 -> AC.
            // «выключи кондиционер» гасит только охлаждение, вентилятор/печка остаются; «климат» = 空调 целиком.
            if (!s.contains("климат") && z.isEmpty()) return off ? "关闭制冷模式" : "打开制冷模式";
            return (off ? "关闭" : "打开") + z + "空调";                                     // OP_AIR_CONDITIONER (z = explicit zone or "")
        }
        // fragrance
        // "запах" только с глаголом действия — "странный запах в машине" это болтовня
        if (s.contains("ароматизат") || s.contains("аромат") || s.contains("парфюм")
            || (s.contains("запах") && (isOn(s) || off || s.contains("смени") || s.contains("убери") || s.contains("сделай")))) {
            if (s.contains("смени") || s.contains("друго")) return "换个香氛";               // SWITCH_FRAGRANCE
            return off ? "关闭香氛" : "打开香氛";                                            // SET_FRAGRANCE
        }
        return null;
    }

    // ---------------------------------------------------------------- carControl: windows / roof

    private static String zhWindowsRoof(String s, boolean off, String z, int n) {
        // "окон" (gen.pl.) does NOT contain "окн" — fleeting vowel, match both forms
        // "стекло" is the everyday word for a car window ("опусти стекло") — but only with
        // open/close verbs and outside washer/defrost contexts
        boolean glass = s.contains("стекл") && !s.contains("стеклоочист") && !s.contains("помой") && !s.contains("омыват")
            && !s.contains("обогрев") && !s.contains("обдув") && !s.contains("лобов")
            && !s.contains("протри") && !s.contains("вытри") && !s.contains("запотел")
            && (s.contains("подним") || s.contains("опусти") || s.contains("приоткр")
                || s.contains("закр") || s.contains("откр") || s.contains("вверх") || s.contains("вниз")
                || s.contains("заблок") || s.contains("разблок") || s.contains("блокир"));
        boolean win = ((s.contains("окн") && !s.contains("аудиокн"))  // "аудиОКНига" - не окно!
            || s.contains("окон") || s.contains("окош") || s.contains("форточ")
            || s.contains("стеклоподъемн") || glass) && !s.contains("багажник");   // «стекло багажника» -> 后备窗
        if (win && !s.contains("шторк") && !s.contains("солнцезащит")) {
            if (s.contains("блокир") || s.contains("замок")) {                                 // OP_WINDOW_LOCK ("заблокируй/разблокируй стеклоподъемники")
                boolean disable = off || s.contains("разблок") || s.contains("сними");
                return (disable ? "关闭" : "打开") + "车窗锁";
            }
            if (s.contains("в дождь") || s.contains("дожд")) {                                 // OP_RAIN_CLOSE_WINDOW
                // "закрывай окна в дождь" = ENABLE the feature; only explicit off disables
                boolean disable = s.contains("выключ") || s.contains("отключ") || s.contains("не закрывай");
                return (disable ? "关闭" : "打开") + "雨天自动关窗";
            }
            if (s.contains("при закрыт") || s.contains("на охран")) {                          // OP_LOCK_CLOSE_WINDOW
                boolean disable = s.contains("выключ") || s.contains("отключ");                // "закрой окна на охране" = ENABLE
                return (disable ? "关闭" : "打开") + "锁车关窗";
            }
            if (s.contains("приоткр") || s.contains("щелочк") || s.contains("щель")) return z + "车窗留缝";
            int p = percentOf(s);
            // «закрой окно на 90 процентов» = leave it 10% open (the ratio slot is the OPENING)
            if (p > 0 && p < 100 && (s.contains("закр") || s.contains("подним"))) p = 100 - p;
            if (p == 50) return z + "车窗开一半";
            if (p > 0) return z + "车窗开到百分之" + p;
            // "опусти" = OPEN (window goes down), "подними/вверх" = CLOSE — inverse of isOff!
            boolean close = s.contains("подним") || s.contains("закр") || s.contains("закро")
                || s.contains("прикро") || s.contains("задвинь")
                || ((s.contains("крой") || s.contains("кройте")) && !s.contains("откр"))
                || s.contains("выключ") || s.contains("вверх");
            return (close ? "关闭" : "打开") + z + "车窗";
        }
        // "крыша/панорама" = люк, но НЕ "крышка багажника/бензобака/зарядки", не шторка и НЕ
        // «панорамный ОБЗОР / панорамная КАМЕРА» (это круговой обзор 360, ловится в zhCameraDvr —
        // harness caught «включи панорамный обзор» opening the sunroof)
        if ((s.contains("люк") || s.contains("панорам")
             || (s.contains("крыш") && !s.contains("багажник") && !s.contains("заряд")
                 && !s.contains("бак") && !s.contains("капот")))
            && !s.contains("шторк") && !s.contains("солнцезащит")
            && !s.contains("обзор") && !s.contains("камер")) {
            int p = percentOf(s);
            if (p == 50) return "天窗开一半";                                                  // SET_SUNROOF_WINDOW ratio=50% (captured)
            if (p > 0) return "天窗开到百分之" + p;                                           // same ratio slot as 车窗开到百分之N
            boolean close = off || s.contains("закр") || s.contains("закро") || s.contains("прикро")
                || ((s.contains("крой") || s.contains("кройте")) && !s.contains("откр"));
            return close ? "关闭天窗" : "打开天窗";
        }
        if (s.contains("шторк") || s.contains("солнцезащит")) {
            boolean close = off || s.contains("закр") || s.contains("закро") || s.contains("прикро")
                || s.contains("задвинь") || ((s.contains("крой") || s.contains("кройте")) && !s.contains("откр"));
            boolean winShade = win || s.contains("боков") || s.contains("задн") || s.contains("сзади");
            // Partial opening (user report 17.09: «шторка панорамы наполовину» only ever went fully open/closed —
            // the percent was dropped here). Same `ratio` slot and wording as the sunroof (天窗开一半 was captured
            // on the car); the 遮阳帘 forms themselves are NOT yet confirmed on a car. «закрой на 30%» = 70% open.
            int sp = winShade ? -1 : percentOf(s);
            if (sp > 0 && sp < 100 && close) sp = 100 - sp;
            if (sp == 50) return "遮阳帘开一半";
            if (sp > 0 && sp < 100) return "遮阳帘开到百分之" + sp;
            if (winShade)
                return (close ? "关闭" : "打开") + "车窗遮阳帘";                                // SET_WINDOW_SUN_SHADE
            return close ? "关闭遮阳帘" : "打开遮阳帘";                                        // SET_SUN_SHADE
        }
        if (s.contains("все") && (s.contains("открой") || s.contains("закрой"))
            && !s.contains("приложен") && !win && s.trim().split("\\s+").length <= 3)
            return off ? "一键全关" : "一键全开";                                            // OPEN/CLOSE_ALL_ONE_KEY
        return null;
    }

    // ---------------------------------------------------------------- carControl: doors / trunks / locks

    private static String zhDoorsLocks(String s, boolean off, String z) {
        // Split tailgate (E07: the bed roof = upper part, the drop-down board = lower part). «крыша/верх
        // багажника» -> 上尾门, «борт» -> 下尾门 («опусти/откинь борт» = OPEN, «подними борт» = CLOSE).
        // «крышКа багажника» stays the plain trunk. Wording 上尾门/下尾门 comes from the intent names
        // (OP_UPPER_TAILGATE / OP_LOWER_TAILGATE) — not yet confirmed on an E07.
        boolean board = s.contains("борт") && !s.contains("бортов") && !s.contains("на борту");
        if ((s.contains("багажник") || s.contains("кузов") || board) && !s.contains("свет") && !s.contains("лампа")) {
            boolean close = off || s.contains("закр") || s.contains("закро") || s.contains("опусти")
                || ((s.contains("крой") || s.contains("кройте")) && !s.contains("откр"));
            if (s.contains("стекл") || s.contains("окн") || s.contains("окош"))
                return unsafeGate((close ? "关闭" : "打开") + "后备窗");                        // catalog: 后备窗 (tailgate glass)
            if (s.contains("передн") || s.contains("фрунк")) return unsafeGate(close ? "关闭前备箱" : "打开前备箱"); // OP_FRUNK
            boolean roof = (s.contains("крыша") || s.contains("крышу") || s.contains("крыши")) && !s.contains("крышк");
            if (s.contains("верхн") || s.contains("верх ") || roof)
                return unsafeGate(close ? "关闭上尾门" : "打开上尾门");                           // OP_UPPER_TAILGATE
            if (s.contains("нижн") || board) {
                boolean boardClose = s.contains("подним") || s.contains("подыми") || s.contains("закр") || s.contains("верни");
                return unsafeGate(boardClose ? "关闭下尾门" : "打开下尾门");                        // OP_LOWER_TAILGATE
            }
            return unsafeGate(close ? "关闭后备箱" : "打开后备箱");                            // OP_TRUNK
        }
        if (s.contains("капот")) return unsafeGate(off ? "关闭前备箱" : "打开前备箱");       // OP_FRUNK
        if (s.contains("детск") && (s.contains("замок") || s.contains("блокировк")))
            return (off ? "关闭" : "打开") + (s.contains("авто") ? "自动儿童锁" : "儿童锁");   // OP_CHILD_SAFETY_LOCK (catalog: 儿童锁|自动儿童锁)
        if (s.contains("перегородк")) return (off ? "关闭" : "打开") + "后隔断玻璃";          // OP_REAR_COMPARTMENT? catalog: 后隔断玻璃
        if (s.contains("двер")) {
            if (s.contains("запр") || s.contains("отопр") || s.contains("заблок") || s.contains("разблок")
                || s.contains("замкни") || s.contains("замок")) {
                boolean unlock = s.contains("отопр") || s.contains("разблок") || s.contains("открой");
                return unsafeGate(unlock ? "解锁" : "锁车");                                 // OP_DOOR_LOCK
            }
            if (s.contains("холодильник")) return (off ? "关闭" : "打开") + "冰箱门";        // SET_REFRIGERATOR_DOOR
            return unsafeGate((off ? "关闭" : "打开") + z + "车门");                         // OP_CAR_DOOR
        }
        if ((s.contains("запр") || s.contains("отопр") || s.contains("заблокируй") || s.contains("разблокируй")
             || s.contains("закрой") || s.contains("открой"))
            && (s.contains("машин") || s.contains("автомобил") || s.contains("тачк")
                || s.contains("замок"))) {  // "закрой (на) замок" — детский/оконный замок ловятся раньше
            boolean unlock = s.contains("отопр") || s.contains("разблок") || s.contains("открой");
            return unsafeGate(unlock ? "解锁" : "锁车");                                     // OP_DOOR_LOCK ("закрой машину")
        }
        // "открой/закрой зарядку, бак" без слова "лючок" — тоже крышки портов
        // (только с откр/закр: "включи зарядку" — НЕ крышка, уходит дальше/в чат)
        if (s.contains("лючок") || s.contains("порт зарядк") || (s.contains("зарядн") && s.contains("порт"))
            || ((s.contains("откр") || s.contains("закр"))
                && (s.contains("заряд") || s.contains("бак") || s.contains("заправ")))) {
            // «бак для зарядки», «зарядное отверстие» — порт зарядки: слово «заряд» важнее «бака»
            // 充电口盖 (full form) — verified on the car («лючок порта открыт/закрыт»); bare 充电口 did NOT actuate
            if (s.contains("заряд")) return (off ? "关闭" : "打开") + "充电口盖";           // OP_CHARGING_PORT_COVER
            if (s.contains("бензо") || s.contains("бак") || s.contains("топлив") || s.contains("заправ"))
                return (off ? "关闭" : "打开") + "油箱盖";                                   // OP_FUEL_TANK_CAP ("заправочный лючок")
            return (off ? "关闭" : "打开") + "充电口盖";                                     // голый «лючок» = зарядка (full form, see above)
        }
        if (s.contains("подход") && s.contains("разблок")) return (off ? "关闭" : "打开") + "近车自动解锁"; // OP_APPROACH_UNLOCK
        if ((s.contains("уход") || s.contains("отход")) && (s.contains("закрыв") || s.contains("блокир"))) {
            boolean disable = s.contains("выключ") || s.contains("отключ") || s.contains("не закрыв") || s.contains("не блокир");
            return (disable ? "关闭" : "打开") + "离车自动闭锁";                             // OP_LEAVE_LOCK ("закрывай при уходе" = ENABLE)
        }
        return null;
    }

    // ---------------------------------------------------------------- carControl: lights

    private static String zhLights(String s, boolean off, String grade, int speakerDir) {
        // welcome / farewell light shows (catalog: 迎宾灯效 / 欢送灯效) — before the ambient branch
        if ((s.contains("приветств") || s.contains("встреча")) && (s.contains("свет") || s.contains("подсветк")))
            return (off ? "关闭" : "打开") + "迎宾灯效";
        if ((s.contains("прощальн") || s.contains("прощани") || s.contains("провожа")) && (s.contains("свет") || s.contains("подсветк")))
            return (off ? "关闭" : "打开") + "欢送灯效";
        if (s.contains("подсветк") || s.contains("атмосферн") || s.contains("амбиент") || s.contains("эмбиент")
            || s.contains("неонов") || s.contains("неоновую")) {
            if (s.contains("настройк")) return "打开氛围灯设置";                              // CONTROL_AMBIENT_LIGHTING_PAGE
            String c = colorOf(s);
            if (!c.isEmpty()) return "氛围灯调成" + zhColor(c);                              // SET_MOOD_LIGHTS_COLOR
            if (s.contains("друго") && s.contains("цвет")) return "氛围灯换个颜色";           // SWITCH_M_L_COLOR
            if (s.contains("эффект")) return "氛围灯换个灯效";                                // SWITCH_M_L_EFFECT
            if (s.contains("тем") && s.contains("подсветк") && (s.contains("друг") || s.contains("смени")))
                return "氛围灯换个主题";                                                     // SET_MOOD_LIGHTS_THEME
            if (s.contains("такт") || s.contains("ритм") || s.contains("музык")) return "打开氛围灯律动模式"; // SET_MOOD_LIGHTS_MODE
            if (s.contains("градиент") || s.contains("перелив")) return "打开氛围灯渐变效果"; // SET_MOOD_LIGHTS_GRADIENT
            if (s.contains("ярче") || grade.equals("PLUS") || plusWordOf(s)) return "氛围灯调亮一点"; // SET_MOOD_LIGHTS_BRIGHTNESS
            if (s.contains("темнее") || s.contains("притуш") || s.contains("приглуш")
                || grade.equals("MINUS") || minusWordOf(s)) return "氛围灯调暗一点";
            return off ? "关闭氛围灯" : "打开氛围灯";                                        // OP_MOOD_LIGHTS
        }
        if (s.contains("световое шоу") || s.contains("светомузык") || s.contains("шоу свет"))
            return (off ? "关闭" : "打开") + "音乐灯光秀";                                    // CONTROL_LIGHT_SHOW
        boolean beamVerb = s.contains("свет") || s.contains("включ") || s.contains("выключ") || s.contains("вруб") || s.contains("выруб");
        // авто-режим фар: в dm-каталоге есть устройства 自动近光灯/自动远光灯 (OPEN/CLOSE).
        // «автомат»/«авто режим» — не «авто» голое, чтобы «свет в автомобиле» сюда не попал
        if ((s.contains("фар") || s.contains("ближн") || s.contains("дальн") || s.contains("свет"))
            && (s.contains("автомат") || s.contains("авто режим") || s.contains("авторежим"))
            && !s.contains("подсветк")) {
            boolean high = s.contains("дальн");
            return (off ? "关闭" : "打开") + (high ? "自动远光灯" : "自动近光灯");
        }
        if (s.contains("дальн") && beamVerb) return off ? "关闭远光灯" : "打开远光灯";        // OP_HIGH_BEAM ("вруби дальний")
        // fallback: «фары в авто» с голым «авто» (не «автомат») — лучше в чат, чем включить ближний
        if ((s.contains("фар") || s.contains("ближн")) && s.contains("авто")) return null;
        if ((s.contains("ближн") && beamVerb) || (s.contains("фары") && !s.contains("дальн"))) {
            if (s.contains("выше") || s.contains("ниже") || s.contains("высот"))              // SET_DIPPED_BEAM_HEIGHT
                return s.contains("ниже") || grade.equals("MINUS") ? "近光灯高度调低一点" : "近光灯高度调高一点";
            return off ? "关闭近光灯" : "打开近光灯";                                        // OP_DIPPED_BEAM
        }
        if (s.contains("аварийк") || s.contains("аварийн")) return off ? "关闭双闪" : "打开双闪"; // OP_WARNING_LIGHT
        if (s.contains("противотуман") || s.contains("туманк")) {                            // catalog: 前雾灯|雾灯|后雾灯
            if (s.contains("передн") || s.contains("спереди")) return (off ? "关闭" : "打开") + "前雾灯";
            if (s.contains("задн") || s.contains("сзади")) return (off ? "关闭" : "打开") + "后雾灯"; // OP_REAR_FOG_LIGHT
            return (off ? "关闭" : "打开") + "雾灯";
        }
        if ((s.contains("потолоч") || s.contains("потолк") || s.contains("верхний свет")) && !s.contains("подсветк"))
            return (off ? "关闭" : "打开") + "车顶灯";                                         // catalog: 车顶灯
        if (s.contains("габарит")) return SIDE_LIGHTS_OFFLINE ? (off ? "关闭" : "打开") + "示廓灯" : null; // OP_OUTLINE_LIGHT
        if (s.contains("стояночн") || s.contains("позиционн") || hasWord(s, DRL_WORD))
            return SIDE_LIGHTS_OFFLINE ? (off ? "关闭" : "打开") + "位置灯" : null;            // OP_SIDE_LIGHTS (+ДХО: ближайший интент)
        if (s.contains("задн") && (s.contains("фонар") || s.contains("фонарь"))) return (off ? "关闭" : "打开") + "尾灯"; // OP_TAILLIGHT
        if (s.contains("багажник") && (s.contains("свет") || s.contains("ламп"))) return (off ? "关闭" : "打开") + "后备箱灯"; // OP_TRUNK_LIGHT
        if ((s.contains("притуш") || s.contains("приглуш")) && s.contains("свет")) return "氛围灯调暗一点";
        // Салонный свет (OP_READ_LIGHTS). Проверено на Q07 01.11.2026:
        //   打开所有阅读灯 — весь салон; 车内所有灯光 здесь это OP_MOOD_LIGHTS, то есть подсветка, а не плафоны
        //   打开主驾阅读灯 и т.п. — с позицией (主驾/副驾/左后/右后/后排); 左前阅读灯 движок не понимает
        // (нужен глагол действия: "я люблю свет луны" — болтовня; "зажги/погаси свет" тоже сюда)
        if ((s.contains("свет") || s.contains("освещен") || s.contains("ламп") || s.contains("плафон"))
            && !s.contains("светл") && !s.contains("свето") && !s.contains("светк") && !s.contains("дома") // «(пад)светку» — не сюда
            && (isOn(s) || off || s.contains("зажги") || s.contains("вруб") || s.trim().equals("свет"))) {
            String on = off ? "关闭" : "打开";
            String zc = zoneOf(s);
            boolean all = zc.equals("ALL") || s.contains("весь") || s.contains("везде");
            boolean lamp = s.contains("чтени") || s.contains("ламп") || s.contains("плафон");
            if (all) return on + "所有阅读灯";
            if (lamp || !zc.isEmpty()) {                       // лампа чтения / свет с зоной -> позиция
                if (zc.isEmpty()) zc = zoneCode(speakerDir);     // «включи лампу» = лампа над говорящим
                return on + zhReadZone(zc) + "阅读灯";
            }
            return on + "所有阅读灯";                            // голое «включи свет» = весь салон
        }
        return null;
    }

    /** Zone code -> позиция лампы чтения (штатный NLU: 左前/右前/左后/右后/前排/后排). */
    private static String zhReadZone(String code) {
        if ("DRIVER".equals(code)) return "主驾";
        if ("PASSENGER".equals(code)) return "副驾";
        if ("REAR_LEFT".equals(code)) return "左后";
        if ("REAR_RIGHT".equals(code)) return "右后";
        if ("REAR".equals(code)) return "后排";
        if ("FRONT".equals(code)) return "前排";
        return "";
    }

    // ---------------------------------------------------------------- carControl: mirrors / steering / wipers

    private static String zhMirrorsSteerWipers(String s, boolean off, String grade, int n) {
        if (s.contains("зеркал")) {
            if (s.contains("обогре") || s.contains("подогре") || s.contains("грей") || s.contains("греть"))
                return (off ? "关闭" : "打开") + "后视镜加热";                                // REAR_MIRROR_WARM
            if (s.contains("сложи") || s.contains("сверни")) return "折叠后视镜";             // OP_REAR_MIRROR_CONTROL
            if (s.contains("разложи") || s.contains("разверни")) return "展开后视镜";
            if (s.contains("автоскладыв") || s.contains("автомат")) return (off ? "关闭" : "打开") + "后视镜自动折叠"; // OP_REAR_MIRROR_AUTO
            if (s.contains("выше") || s.contains("вверх")) return "后视镜向上调一点";          // ADJ_REARVIEW_MIRROR
            if (s.contains("ниже") || s.contains("вниз")) return "后视镜向下调一点";
            if (s.contains("влево") || s.contains("левее")) return "后视镜向左调一点";
            if (s.contains("вправо") || s.contains("правее")) return "后视镜向右调一点";
            if (s.contains("стриминг") || s.contains("камер")) return (off ? "关闭" : "打开") + "流媒体后视镜"; // OP_STREAM_MEDIA_REAR_VIEW
        }
        if (s.contains("руль") || s.contains("руля") || s.contains("рулев")) {
            if (s.contains("выше") || s.contains("подним")) return "方向盘调高一点";           // ADJ_STEER_DIRECTION
            if (s.contains("ниже") || s.contains("опусти")) return "方向盘调低一点";
            if (s.contains("легче") || s.contains("тяжелее") || s.contains("усили"))          // SET_STEERING_STYLE
                return s.contains("легче") ? "转向调到轻便模式" : "转向调到运动模式";
            // руль-подогрев только по явным словам тепла — "зафиксируй руль" не должен греть
            if (s.contains("подогре") || s.contains("обогре") || s.contains("грей") || s.contains("греть") || s.contains("тепл")) {
                if (s.contains("авто")) return (off ? "关闭" : "打开") + "方向盘自动加热";     // catalog: 方向盘自动加热
                if (n >= 1 && n <= 3) return "方向盘加热调到" + n + "档";                      // SET_STEER_WARM
                if (grade.equals("PLUS")) return "方向盘加热调高一点";
                return off ? "关闭方向盘加热" : "打开方向盘加热";                             // OP_STEER_WARM
            }
            return null;
        }
        if ((s.contains("помой") && (s.contains("лобов") || s.contains("стекл"))) || s.contains("омыват") || s.contains("брызни"))
            return "喷水洗玻璃";                                                             // WASH_WIPER
        if (s.contains("датчик") && s.contains("дожд"))                                       // SET_WIPER_SENSITIVITY (без слова "дворники")
            return grade.equals("MINUS") ? "雨刮灵敏度调低一点" : "雨刮灵敏度调高一点";
        if ((s.contains("вытри") || s.contains("протри") || s.contains("смахни")) && s.contains("стекл"))
            return WIPER_ONOFF_OFFLINE ? "打开雨刮" : null;                                   // "протри/смахни капли со стекла"
        if (s.contains("дворник") || s.contains("щетк") || s.contains("стеклоочистит")) {
            if (s.contains("задн") || s.contains("сзади")) return (off ? "关闭" : "打开") + "后雨刮器"; // catalog: 后雨刮器
            // «подними щётки/дворники», «сервисное положение» = the service position, NOT «switch the wipers on»
            if (s.contains("сервис") || s.contains("ремонт") || s.contains("замен") || s.contains("подним")
                || s.contains("подыми") || s.contains("обслуживан"))
                return WIPER_ONOFF_OFFLINE ? ((off && !s.contains("подним")) ? "关闭" : "打开") + "雨刮维修模式" : null; // REPAIR_WIPER
            if (s.contains("чувствительн") || s.contains("датчик"))                           // SET_WIPER_SENSITIVITY
                return grade.equals("MINUS") ? "雨刮灵敏度调低一点" : "雨刮灵敏度调高一点";
            if (n >= 1 && n <= 4) return "雨刮调到" + n + "档";                                // SET_WIPER_SPEED
            if (s.contains("быстрее") || grade.equals("PLUS") || grade.equals("MAX")) return "雨刮快一点";
            if (s.contains("медленн") || grade.equals("MINUS")) return "雨刮慢一点";
            return WIPER_ONOFF_OFFLINE ? (off ? "关闭雨刮" : "打开雨刮") : null;               // incl. «дежурный режим»
        }
        return null;
    }

    // ---------------------------------------------------------------- carControl: HUD / displays

    private static String zhHudDisplays(String s, boolean off, String grade) {
        // "главный экран"/"предыдущий экран" are UI navigation, not display control
        if (s.contains("главн") || s.contains("предыдущ") || s.contains("рабочий стол") || s.contains("приветств")) return null;
        if ((s.contains("экран") || s.contains("дисплей")) && (s.contains("ко мне") || s.contains("к водителю") || s.contains("на меня")))
            return null;                                                                      // наклона экрана к водителю нет — не выдавать 横屏
        if (s.contains("hud") || s.contains("проекц") || s.contains("хад") || s.contains("худ")) {
            if (s.contains("ярк") || s.contains("ярч")) return grade.equals("MINUS") || s.contains("темнее") // SET_HUD_BRIGHTNESS
                ? "HUD亮度调低一点" : "HUD亮度调高一点";
            if (s.contains("угол") || s.contains("наклон"))                                   // SET_HUD_ANGLE
                return s.contains("ниже") || grade.equals("MINUS") ? "HUD角度调低一点" : "HUD角度调高一点";
            if (s.contains("выше") || s.contains("подним")) return "HUD高度调高一点";          // SET_HUD_HEIGHT
            if (s.contains("ниже") || s.contains("опусти")) return "HUD高度调低一点";
            if (s.contains("цвет")) return "切换HUD颜色模式";                                  // SWITCH_HUD_COLOR_MODE
            if (s.contains("режим") || s.contains("вид")) return "切换HUD显示模式";            // SET_HUD_MODE / SWITCH_HUD_DISPLAY_MODE
            if (s.contains("настройк")) return "打开抬头显示设置";                             // CONTROL_HUD_PAGE
            return off ? "关闭抬头显示" : "打开抬头显示";                                     // OP_HUD
        }
        if (s.contains("экран") || s.contains("дисплей")) {
            if (s.contains("пассажир") && (s.contains("угол") || s.contains("наклон") || s.contains("выше") || s.contains("ниже")))
                return grade.equals("MINUS") || s.contains("ниже")                             // SET_PDISPLAY_ANGLE
                    ? "副驾屏幕角度调低一点" : "副驾屏幕角度调高一点";
            if (s.contains("автояркост") || (s.contains("авто") && s.contains("ярк")))
                return (off ? "关闭" : "打开") + "自动亮度";                                  // OP_AUTO_DISPLAY_BRIGHTNESS
            if (s.contains("яркост") || s.contains("ярче") || s.contains("темнее")) {          // SET_DISPLAY_BRIGHTNESS
                if (grade.equals("MAX")) return "屏幕亮度调到最大";
                if (grade.equals("MIN")) return "屏幕亮度调到最小";
                return s.contains("темнее") || s.contains("притуш") || s.contains("приглуш")
                    || grade.equals("MINUS") || minusWordOf(s) ? "屏幕调暗一点" : "屏幕调亮一点";
            }
            if (s.contains("цветов") && s.contains("температур"))                              // SET_DISPLAY_COLOR_TEMPERATURE
                return s.contains("холодн") ? "屏幕色温调冷一点" : "屏幕色温调暖一点";
            if (s.contains("защит") && s.contains("глаз")) return (off ? "关闭" : "打开") + "护眼模式"; // SET_DISPLAY_EYE_PROTECTION
            if (s.contains("ночн")) return "切换到夜间模式";                                   // SET_DISPLAY_MODE
            if (s.contains("дневн")) return "切换到白天模式";
            if (s.contains("горизонт") || s.contains("поверни") || s.contains("переверни")) return "切换到横屏"; // SET_DISPLAY_ORIENTATION
            if (s.contains("вертикал")) return "切换到竖屏";
            if (s.contains("очист") || s.contains("протер") || s.contains("протр"))
                return (off ? "关闭" : "打开") + "屏幕清洁模式";                              // OP_DISPLAY_CLEAN
            if (s.contains("усыпи") || s.contains("погаси")) return "息屏";                    // DISPLAY_SLEEP
            if (s.contains("разбуди") || s.contains("проснись")) return "亮屏";                // DISPLAY_UNSLEEP
            return off ? "关闭屏幕" : "打开屏幕";                                             // OP_DISPLAY_POWER
        }
        if (s.contains("шрифт")) return grade.equals("MINUS") || s.contains("мельче")          // SET_FONT_SIZE
            ? "字体调小一点" : "字体调大一点";
        // bare "яркость" without an object -> screen brightness (подсветка/HUD ловятся раньше)
        if (s.contains("яркост")) {
            if (s.contains("темнее") || grade.equals("MINUS") || minusWordOf(s)) return "屏幕调暗一点";
            if (s.contains("ярче") || grade.equals("PLUS") || plusWordOf(s)) return "屏幕调亮一点";
            return null;   // без направления не действуем: raw-гипотеза с искажённым глаголом не должна дать ЯРЧЕ
        }
        // bare "цветовая температура" без слова "экран"
        if (s.contains("цветов") && s.contains("температур"))
            return s.contains("холодн") ? "屏幕色温调冷一点" : "屏幕色温调暖一点";
        return null;
    }

    // ---------------------------------------------------------------- autoPilot (ALL unsafe)

    private static String zhAutoPilot(String s, boolean off, int n) {
        if (s.contains("круиз")) {
            if (n >= 30 && n <= 150) return unsafeGate("巡航速度调到" + n);                   // SET_CRUISE_CAR_SPEED
            if (s.contains("адаптивн")) return unsafeGate((off ? "关闭" : "打开") + "自适应巡航"); // OP_ACC
            return unsafeGate((off ? "关闭" : "打开") + "自适应巡航");
        }
        if (s.contains("автопилот") || (s.contains("автоматическ") && s.contains("вожден")))
            return unsafeGate((off ? "关闭" : "打开") + "自动驾驶");                          // OP_AUTO_DRIVE
        if (s.contains("iacc") || s.contains("иакк")) return unsafeGate((off ? "关闭" : "打开") + "IACC"); // OP_IACC
        if (s.contains("nca") || s.contains("навигационн") && s.contains("пилот"))
            return unsafeGate((off ? "关闭" : "打开") + "领航辅助");                          // OP_NCA
        if (s.contains("дистанц")) {                                                          // "сократи дистанцию", "дистанция побольше"
            if (n >= 1 && n <= 4) return unsafeGate("跟车距离调到" + n + "档");                // SET_CRUISE_FOLLOW_GAP
            return unsafeGate(s.contains("мень") || s.contains("ближе") || s.contains("сократи")
                || s.contains("помень") ? "跟车距离调小一点" : "跟车距离调大一点");
        }
        if (s.contains("следуй") || s.contains("следован")) {
            if (off || s.contains("отмени") || s.contains("не следуй")) return unsafeGate("取消跟车"); // CANCEL_FOLLOW_CAR
            return unsafeGate("跟着前车走");                                                 // CONTROL_FOLLOW_CAR
        }
        if (s.contains("перестро") || s.contains("полос")) {                                  // CONTROL_LANE_CHANGE ("полоса левее")
            if (s.contains("лев")) return unsafeGate("向左变道");
            if (s.contains("прав")) return unsafeGate("向右变道");
            int i = ordinalIn(s);
            if (i > 0) return unsafeGate("走第" + i + "车道");                                // INDEX_LANE_DRIVE
        }
        if (s.contains("держи полос") || s.contains("держись полос")) return unsafeGate("保持车道行驶"); // KEEP_LANE_DRIVE
        if (s.contains("обгони")) return unsafeGate("超过前车");                              // OVERTAKE_SPECIFIC_CAR
        if (s.contains("ограничен") && s.contains("скорост")
            && (s.contains("напомин") || s.contains("предупрежд") || s.contains("оповещ") || s.contains("сигнал")))
            return unsafeGate((off ? "关闭" : "打开") + "限速提醒");                          // SPEED_LIMIT_CONTROL — «ограничение на 60» is a limiter value → backend
        if (s.contains("парк")) {
            if (s.contains("запомни")) return unsafeGate("开始记忆泊车");                      // MEMORIZE_DRIVING_CONTROL
            if (s.contains("удали") || s.contains("забудь")) return unsafeGate("删除记忆泊车路线"); // DELETE_DRIVE_MEMORY
            if (s.contains("пауз") || s.contains("подожди")) return unsafeGate("暂停泊车");    // PARKING_CONTROL
            if (s.contains("продолж")) return unsafeGate("继续泊车");
            if (s.contains("выезжай") || s.contains("выехать") || s.contains("выйди") || s.contains("выгони")
                || s.contains("выеду")) return unsafeGate("泊出");                            // DRIVING_OUT
            if (s.contains("заезжай") || s.contains("это место")) return unsafeGate("泊入车位"); // PARK_IN
            if (s.contains("автопарк") || s.contains("сама") || s.contains("паркуйся"))
                return unsafeGate("帮我泊车");                                                // PARKING / OP_AUTO_PARKING ("припаркуйся" тоже)
        }
        if ((s.contains("едь за") || s.contains("езжай за") || s.contains("поезжай за")) && s.contains("машин"))
            return unsafeGate("跟着前车走");                                                  // CONTROL_FOLLOW_CAR ("едь за той машиной")
        if (s.contains("подъезжай") || s.contains("подзови") || (s.contains("призыв") && s.contains("машин")))
            return unsafeGate("语音召唤");                                                    // carControl@OP_VOICE_SUMMON
        return null;
    }

    // ---------------------------------------------------------------- driving / energy / suspension

    private static String zhDriveEnergy(String s, boolean off, String grade) {
        // catalog devices: 越野辅助 (off-road assist), 脱困辅助 (get-unstuck assist), 圆心掉头 (tank turn — moves the car)
        if ((s.contains("бездорож") || s.contains("внедорож") || s.contains("оффроуд"))
            && (s.contains("ассистент") || s.contains("помощ") || s.contains("помог")))
            return (off ? "关闭" : "打开") + "越野辅助";
        if (s.contains("застрял") || s.contains("выбраться") || s.contains("вытащи") || s.contains("буксу")
            || (s.contains("выезд") && (s.contains("гряз") || s.contains("снег") || s.contains("песк"))))
            return (off ? "关闭" : "打开") + "脱困辅助";
        if ((s.contains("разворот") || s.contains("разверни") || s.contains("развернись")) && (s.contains("на месте") || s.contains("танков")))
            return unsafeGate((off ? "关闭" : "打开") + "圆心掉头");
        String dm = zhDriveModeOf(s);
        if (!dm.isEmpty() && !s.contains("музы") && !s.contains("звук") && !s.contains("подсветк") && !s.contains("карт")
            && !s.contains("сиден") && !s.contains("кресл") && !s.contains("спинк") && !s.contains("массаж")
            && !s.contains("экран") && !s.contains("холодильник") && !s.contains("климат") && !s.contains("кондиц")
            && (s.contains("режим") || s.contains("вожден") || s.contains("переключ")
                || s.contains("включи") || s.trim().equals("эко") || s.trim().equals("спорт")))
            return DRIVE_MODE_OFFLINE ? "切换到" + dm : null;                                 // SET_DRIVING_MODE
        // hybrid/EREV energy modes (carControl@SET_ENERGY_MODE) — LOCAL NLU vocabulary (verified
        // on-device: the cloud words 增程/燃油 come back UNKNOWN locally; these actuate):
        //   纯电 (electric) / 燃油优先 (fuel) / 智能增程 (auto/smart) / 混动 (hybrid).
        // energy modes need a command shape: «у него последовательный гибрид…» is talk, not a mode switch
        boolean modeCmd = s.contains("режим") || s.contains("включи") || s.contains("переключ") || s.contains("поставь")
            || s.contains("перейди") || s.contains("едь на") || s.contains("езжай на") || s.trim().split("\\s+").length <= 2;
        if (!modeCmd) { /* fall through to suspension / loading below */ }
        else if (s.contains("электро") || s.contains("электрич"))
            return "切换到纯电模式";                                                          // electric (纯电)
        else if (s.contains("топлив") || s.contains("на бензин") || s.contains("бензинов") || s.contains("на двигател"))
            return "切换到燃油优先模式";                                                       // fuel (燃油优先)
        else if (s.contains("авто режим") || s.contains("авторежим") || s.contains("на авто")
            || s.contains("автоматическ") || s.contains("умный режим") || s.contains("интеллект") || s.contains("智能"))
            return "切换到智能增程模式";                                                       // auto / smart (智能增程)
        else if (s.contains("гибрид") || s.contains("увеличен запас") || s.contains("комбинир") || s.contains("混动"))
            return "切换到混动模式";                                                          // hybrid (混动)
        if (s.contains("сохран") && s.contains("заряд")) return "切换到保电模式";
        if (s.contains("рекупер") && (grade.length() > 0 || s.contains("слабее") || s.contains("сильнее")))
            return grade.equals("MINUS") || s.contains("слабее") ? "能量回收调弱一点" : "能量回收调强一点"; // SET_ENERGY_RECOVERY; вопросы «что такое рекуперация» -> чат
        if ((s.contains("подвеск") || s.contains("клиренс")) && !s.contains("приветств")) {
            if (s.contains("мягч") || s.contains("мягк")) return "悬架调软一点";               // SET_SUSP_DAMPING
            if (s.contains("жестч") || s.contains("жестк") || s.contains("жесч")) return "悬架调硬一点";
            if (s.contains("выровн")) return "一键调平";                                      // ONE_CLICK_LEVELING
            if (s.contains("ниже") || grade.equals("MINUS") || minusWordOf(s)) return "悬架降低一点"; // SET_SUSP_HEIGHT
            return "悬架升高一点";
        }
        if (s.contains("погрузк") && (s.contains("режим") || s.contains("включи") || s.contains("выключи")))
            return (off ? "关闭" : "打开") + "轻松搬运模式";                                  // CARRY_GOODS_EASILY («надо карту погрузить» is talk)
        return null;
    }

    // ---------------------------------------------------------------- comfort / scenario / misc modes

    private static String zhComfortModes(String s, boolean off, int n) {
        String sc = zhScenarioOf(s);
        if (!sc.isEmpty()) return (off ? "关闭" : "打开") + sc;                               // SET_SCENARIO_MODE
        if ((s.contains("охран") && !s.contains("сохран"))  // "сОХРАНи песню" — не режим охраны!
            || s.contains("часовой") || s.contains("часового") || s.contains("сторож")
            || (s.contains("сигнализаци") && !s.contains("аварийн")))
            return (off ? "关闭" : "打开") + "哨塔模式";                                      // OP_SENTINEL_MODE — catalog mode name is 哨塔模式 (not 哨兵)
        if (s.contains("приватн")) return (off ? "关闭" : "打开") + "隐私模式";               // OP_PRIVACY_MODE
        // welcome / greeting features (catalog: 悬架迎宾|精灵迎宾|中控屏迎宾|日常问候|节日问候; light effects handled in zhLights)
        if (s.contains("приветств") || s.contains("здоров") && s.contains("при посадке")) {
            String on = off ? "关闭" : "打开";
            if (s.contains("подвеск")) return on + "悬架迎宾";
            if (s.contains("экран") || s.contains("дисплей")) return on + "中控屏迎宾";
            if (s.contains("ассистент") || s.contains("персонаж") || s.contains("аватар") || s.contains("помощник")) return on + "精灵迎宾";
            if (s.contains("праздн")) return on + "节日问候";
            return on + "日常问候";
        }
        if ((s.contains("звук") || s.contains("сигнал") || s.contains("мелоди") || s.contains("тон"))
            && (s.contains("уведомлен") || s.contains("оповещен")) && (s.contains("смени") || s.contains("друго") || s.contains("поменяй")))
            return "换一个提示音";                                                            // SET_ALARM_TONE
        if (s.contains("мойк") && s.contains("режим")) return (off ? "关闭" : "打开") + "洗车模式"; // OP_CAR_WASH
        if (s.contains("встреч") && s.contains("режим")) return (off ? "关闭" : "打开") + "接驾模式"; // OP_PICKUP_MODE
        if (s.contains("подремать") || s.contains("поспать") || s.contains("вздремн") || s.contains("режим сна")
            || (s.contains("разбуди") && (s.contains("минут") || s.contains("час")))) {
            if (s.contains("полчаса")) return "我要睡30分钟";
            if (s.contains("еще") || s.contains("продли")) return "再睡" + (n > 0 ? n : 10) + "分钟"; // EXTENDED_NAP_MODE_TIME
            if (n > 0 && (s.contains("час") && n <= 12)) return "睡到" + n + "点叫我";         // SET_NAP_MODE_CLOCK
            if (n > 0) return "我要睡" + n + "分钟";                                          // SET_NAP_MODE_TIME
            return "我要睡一会儿";
        }
        if (s.contains("холодильник") || s.contains("морозилк") || s.contains("холодос")) {
            // «температура холодильника, подходящая для белого вина» / «какую поставить» — a question, not a
            // setting: the backend answers (and may return the concrete temperature as a command)
            if (s.contains("подходящ") || s.contains("какая") || s.contains("какую") || s.contains("сколько")) return null;
            // проверено на авто: 把车载冰箱温度调到5度 / 零下5度 («минус пять» = 零下, ниже нуля)
            if (n >= 0 && n <= 20 && (s.contains("градус") || s.contains("на ") || s.contains("минус"))) {
                boolean neg = s.contains("минус") || s.contains("ниже нуля") || s.contains("-");
                return "把车载冰箱温度调到" + (neg ? "零下" : "") + n + "度";              // SET_REFRIGERATOR
            }
            // НЕ gradeOf(): «холодИЛЬНИК» содержит «холод» и даёт MINUS на любую фразу
            if (s.contains("холоднее") || s.contains("похолодн")) return "车载冰箱温度调低一点";
            if (s.contains("теплее") || s.contains("потепл")) return "车载冰箱温度调高一点";
            if (s.contains("подогрев") || s.contains("нагрев")) return "车载冰箱调到加热模式"; // SET_REFRIGERATOR_MODE
            // открой/закрой = ДВЕРЬ (冰箱门, SET_REFRIGERATOR_DOOR); включи/выключи = ПИТАНИЕ (车载冰箱)
            if (s.contains("откр")) return "打开冰箱门";                                     // open the fridge DOOR
            if (s.contains("закр")) return "关闭冰箱门";                                     // close the fridge DOOR
            // 车载冰箱, not bare 冰箱: bare fridge may classify as a smartHome device (cloud) and fail offline
            return off ? "关闭车载冰箱" : "打开车载冰箱";                                     // OP_REFRIGERATOR (power)
        }
        if (s.contains("напоминан")) {
            if (s.contains("телефон")) return (off ? "关闭" : "打开") + "手机遗忘提醒";        // OP_FORGET_PHONE_ALERT
            if (s.contains("рем")) return (off ? "关闭" : "打开") + "安全带未系提醒";          // OP_SEATBELT_UNFASTENED_ALERT
            return (off ? "关闭" : "打开") + "日程提醒";                                      // CONTROL_SCHEDULE_ALERTS
        }
        if (s.contains("звук") && s.contains("скорост")) return (off ? "关闭" : "打开") + "低速提示音"; // OP_LOW_SPEED_ALERT
        if (s.contains("виджет") || s.contains("минус один") || s.contains("минус-один"))
            return (off ? "关闭" : "打开") + "负一屏";                                        // OP_NEGATIVE_ONE_SCREEN
        if (s.contains("расширен") && s.contains("режим")) return (off ? "关闭" : "打开") + "拓展模式"; // OP_EXTENSION_MODE
        if (s.contains("персонаж") || s.contains("аватар")) return "换个精灵形象";            // CHANGE_SPRITE
        if (s.contains("тем") && (s.contains("темн") || s.contains("ночн"))) return "切换到夜间模式"; // «тёмная тема» = ночной режим
        if (s.contains("тем") && (s.contains("светл") || s.contains("дневн"))) return "切换到白天模式";
        // SWITCH_THEME — but "тему" alone is too broad: "поговорим на ТЕМУ X" is a chat topic, not a
        // UI theme. Require an explicit appearance context or a change-verb, and never fire on "на тему".
        if (s.contains("тему оформлен") || s.contains("тему интерфейс")
            || (s.contains("тему") && !s.contains("на тему")
                && (s.contains("смени") || s.contains("поменяй") || s.contains("измени") || s.contains("друг"))))
            return "换个主题";
        if (s.contains("обои") || s.contains("заставк")) return "换个壁纸";                   // SWITCH_WALLPAPER
        if (s.contains("голос") && (s.contains("смени") || s.contains("друго"))) return "换个声音"; // SWITCH_VOICE_TONE
        boolean voiceCmd = s.trim().split("\\s+").length <= 3 || s.contains("смени") || s.contains("поставь")
            || s.contains("включи") || s.contains("сделай") || s.contains("переключи") || s.contains("выбери");
        if (voiceCmd && !s.contains("если") && !s.contains("значит")) {                       // «если голос женский, значит это Светлана» is talk
            if (s.contains("мужск") && s.contains("голос")) return "换成男声";                // SET_VOICE_TONE
            if (s.contains("женск") && s.contains("голос")) return "换成女声";
        }
        if (s.contains("слово пробужден") || s.contains("слово активац")) return "修改唤醒词"; // SET_WAKE_WORD
        if (s.contains("без пробужден") || s.contains("свободное общение"))
            return (off ? "关闭" : "打开") + "免唤醒";                                        // OP_NON_WUW
        return null;
    }

    // ---------------------------------------------------------------- connectivity / charging

    private static String zhConnectivity(String s, boolean off) {
        if ((s.contains("блютус") || s.contains("bluetooth"))
            && !s.contains("источник") && !s.contains("музык"))                               // "источник блютус" -> media source
            return (off ? "关闭" : "打开") + "蓝牙";                                          // OP_BT
        if ((s.contains("вайфай") || s.contains("wifi") || s.contains("wi-fi")) && !s.contains("раздай"))
            return (off ? "关闭" : "打开") + "WiFi";                                          // OP_WIFI ("раздай вайфай" -> хотспот ниже)
        if (s.contains("точку доступа") || s.contains("точка доступа")
            || (s.contains("раздай") && (s.contains("интернет") || s.contains("вайфай") || s.contains("wifi"))))
            return (off ? "关闭" : "打开") + "热点";                                          // OP_HOTSPOT ("интернет раздай")
        if ((s.contains("беспроводн") && s.contains("заряд"))
            || (s.contains("заряди") && s.contains("телефон"))) return (off ? "关闭" : "打开") + "无线充电"; // OP_WIRELESS_CHARGING
        if (s.contains("розетк") || (s.contains("отдач") && s.contains("энерг")) || s.contains("разрядк"))
            return (off ? "关闭" : "打开") + "对外放电";                                      // OP_DISCHARGE_POWER
        if (s.contains("не беспокоить")) return (off ? "关闭" : "打开") + "勿扰模式";         // OP_MOBILE_DND
        return null;
    }

    // ---------------------------------------------------------------- cameras / DVR

    private static String zhCameraDvr(String s, boolean off) {
        if (s.contains("регистратор")) {
            if (s.contains("альбом") || s.contains("записи")) return "打开行车记录仪相册";     // OP_DVR_ALBUM
            if (s.contains("кадр") || s.contains("скрин") || s.contains("фото")) return "行车记录仪拍照"; // CAPTUR_DVR
            if (off || s.contains("останови")) return "停止录像";                             // TAKE_DVR_REC (close)
            return "行车记录仪开始录像";                                                      // TAKE_DVR_REC
        }
        if (s.contains("сфотк") || s.contains("сделай фото") || s.contains("селфи")
            || s.contains("снимок") || (s.contains("фото") && s.contains("камер"))) return "拍照"; // TAKE_PHOTO
        if (s.contains("сними видео") || s.contains("запиши видео")) return "拍个视频";       // TAKE_VIDEO
        // stem "кругов"+"обзор" covers all cases: «круговой обзор», «камера КРУГОВОГО ОБЗОРА», «круговым
        // обзором»; «вид сверху» = the bird's-eye SVM view. (Owner's preferred term is «круговой обзор».)
        if (s.contains("360") || (s.contains("кругов") && s.contains("обзор"))
            || (s.contains("обзор") && (numIn(s) == 360 || s.contains("триста шестьдесят")))
            || (s.contains("панорам") && s.contains("обзор"))                                  // «панорамный обзор» = 360 (NOT the sunroof)
            || (s.contains("сверху") && (s.contains("вид") || s.contains("камер") || s.contains("покажи")))
            || (s.contains("камер") && (numIn(s) == 360 || s.contains("триста шестьдесят"))))  // "камера триста шестьдесят"
            return off ? "关闭360" : "打开360全景影像";                                       // SET_SVM
        if (s.contains("задн") && s.contains("обзор") && s.contains("ассистент"))
            return (off ? "关闭" : "打开") + "后方视野辅助";                                  // OP_REAR_VIEW_ASSIST
        if (s.contains("камер") && s.contains("задн"))
            return off ? "关闭360" : "打开360全景影像";                                       // "камера заднего вида" -> SET_SVM
        if (s.contains("вид камеры") || (s.contains("переключи") && s.contains("камер")))
            return "切换摄像头视角";                                                          // SWITCH_CAMERA_VIEW
        return null;
    }

    // ---------------------------------------------------------------- navi
    // DISABLED (2026-09-04): navigation is served online via appActions -> Yandex Navi. The call site
    // in the ru2zh dispatcher is commented out, so this method is kept only for reference/history —
    // it used to map nav phrases to Chinese for the stock China-only navigator (dead weight in Russia).
    private static String zhNavi(String s, boolean off, String grade) {
        if (s.contains("навигаци") || s.contains("навигат")) {
            if (s.contains("подсказ") || s.contains("громкост") || s.contains("кратк") || s.contains("подробн") || grade.length() > 0) {
                if (s.contains("выключ") || s.contains("замолч")) return "关闭导航播报";       // SET_BROADCAST
                if (s.contains("включ")) return "打开导航播报";
                if (s.contains("кратк")) return "切换到简洁播报";
                if (s.contains("подробн")) return "切换到详细播报";
                if (s.contains("смени") || s.contains("друго") || s.contains("переключи")) return "切换播报模式"; // SWITCH_BROADCAST
                if (grade.equals("PLUS")) return "导航音量调大一点";                           // ADJ_BROADCAST
                if (grade.equals("MINUS")) return "导航音量调小一点";
            }
            if (s.contains("карточк")) return (off ? "关闭" : "打开") + "导航卡片";           // OP_NAVIGATION_CARD
            if (s.contains("домой")) return "回家";                                           // "навигатор домой"
            if (s.contains(" до ")) {                                                          // "навигатор до аэропорта"
                String d = tailAfter(s, " до ");
                if (!d.isEmpty()) return "导航去" + d;
            }
            if (off || s.contains("заверши") || s.contains("выйди")) return "退出导航";       // OP_NAVIGATION
            return "开始导航";
        }
        if (s.contains("маршрут") && (s.contains("останови") || s.contains("отмени")
            || s.contains("заверши") || s.contains("сбрось") || s.contains("удали")))
            return "退出导航";                                                                // "останови маршрут"
        if (s.contains("домой")) return "回家";                                              // LBS_ROUTE (bare "домой" = navigate, not desktop)
        if (s.contains("на работу") && (s.contains("поехали") || s.contains("поедем") || s.contains("едем")
            || s.contains("вези") || s.contains("отвези") || s.contains("маршрут"))) return "去公司";
        for (String k : new String[]{"поехали", "поедем в", "поедем на", "едем в", "едем на",
                "отвези", "вези", "гони в", "езжай в", "маршрут до", "доедем до", "доехать до"}) {
            if (s.contains(k)) {
                String poi = zhPoiOf(s);                       // "заедем на заправку" -> ближайшая АЗС
                if (!poi.isEmpty()) return "附近的" + poi;
                String dest = tailAfter(s, k);
                // junk tails are not destinations ("поехали уже", "поехали быстрее")
                if (!dest.isEmpty() && dest.length() >= 3
                    && !dest.matches("(уже|туда|сюда|потом|позже|быстрее|скорее|дальше|давай|ну)( .*)?"))
                    return "导航去" + dest;  // RU place name as-is — geocoder may need testing
                break;
            }
        }
        if (s.contains("хочу есть") || s.contains("хочу кушать") || s.contains("проголодал")) return "附近的餐厅"; // SEARCH_POI
        if (s.contains("найди") || s.contains("поищи") || s.contains("где ближайш") || s.contains("где ")
            || s.contains("заед") || s.contains("заскочим")
            || s.contains("хочу") || s.contains("хочется") || s.contains("нужн") || s.contains("надо")) {
            String poi = zhPoiOf(s);
            if (!poi.isEmpty()) return s.contains("по пути") || s.contains("по дороге")
                ? "沿途搜" + poi : "附近的" + poi;                                            // SEARCH_PASSBY / SEARCH_POI
        }
        if (s.contains("где мы") || s.contains("где я") || s.contains("наше местоположен")) return "我在哪里"; // ASK_LOCATION
        if (s.contains("куда мы едем") || s.contains("куда едем") || s.contains("пункт назначен")) return "目的地是哪里"; // ASK_NAVI_POI
        if ((s.contains("сколько") && !s.contains("пробег")
             && (s.contains("км") || s.contains("километр") || s.contains("до места")))
            || s.contains("далеко еще") || s.contains("еще далеко")) return "还有多远到";       // ASK_DISTANCE_LEFT
        if (s.contains("сколько ехать") || s.contains("когда приедем") || s.contains("время в пути")
            || s.contains("долго еще") || s.contains("еще долго")) return "还要多久到";        // ASK_TIME_LEFT
        if (s.contains("объезжай") || s.contains("объедь") || s.contains("объехать")) return "躲避拥堵"; // SET_ROUTE_PREFERENCE — before the traffic question
        if (s.contains("пробк") || (s.contains("как") && s.contains("дорог") && s.contains("впереди"))) return "前方路况怎么样"; // ASK_TRAFFIC_CONDITION
        if (s.contains("карту") || s.contains("карта")) {
            if (s.contains("приблиз") || s.contains("увелич") || grade.equals("PLUS")) return "放大地图"; // ZOOM_MAP_SIZE ("карту побольше")
            if (s.contains("отдали") || s.contains("уменьши") || grade.equals("MINUS")) return "缩小地图";
            if (s.contains("спутник")) return "切换到卫星地图";                                // SWITCH_MAP_LAYER
            if (s.contains("обычн") || s.contains("стандартн")) return "切换到标准地图";
            if (s.contains("север")) return "切换到正北朝上";                                  // SWITCH_VIEW_ORIENTATION
            if (s.contains("по курсу") || s.contains("по ходу")) return "切换到车头朝上";
            if (s.contains("3d") || s.contains("объемн")) return "切换到3D视角";
            if (off || s.contains("закрой")) return "关闭地图";                                // CLOSE_MAP_PAGE
            return "打开地图";                                                                // SWITCH_MAP_PAGE
        }
        if (s.contains("весь маршрут") || s.contains("полный маршрут")) return "查看全程路线"; // VIEW_FULL_ROUTE
        if (s.contains("без платных") || s.contains("платн дорог")) return "避开收费";        // SET_ROUTE_PREFERENCE
        if (s.contains("без шоссе") || s.contains("без трассы")) return "不走高速";
        if (s.contains("объезжай пробк") || s.contains("объедь пробк")) return "躲避拥堵";
        if (s.contains("сохрани") && (s.contains("место") || s.contains("адрес"))) return "收藏这个地点"; // COLLECT_ADDRESS
        if (s.contains("запомни дом") || (s.contains("дом") && s.contains("адрес"))) return "设置家的地址"; // SET_HOME_POI
        if (s.contains("запомни работу") || (s.contains("работ") && s.contains("адрес"))) return "设置公司地址"; // SET_COMPANY_POI
        return null;
    }

    // ---------------------------------------------------------------- phone

    private static String zhPhone(String s) {
        // "вызови такси/эвакуатор/помощь" — не звонок контакту, пусть уходит в чат
        boolean callVerb = s.contains("позвони") || s.contains("набери")
            || ((s.contains("вызови") || s.contains("звякни"))
                && !s.contains("такси") && !s.contains("эвакуатор") && !s.contains("помощь"));
        if (callVerb) {
            if (s.contains("еще раз") || s.contains("снова") || s.contains("повтори")) return "重拨"; // REDIAL
            String who = tailAfter(s, s.contains("позвони") ? "позвони"
                : s.contains("набери") ? "набери" : s.contains("вызови") ? "вызови" : "звякни");
            if (who.startsWith("номер")) who = who.substring(5).trim();
            if (who.matches("(перв|втор|трет|четверт|пят|шест|седьм|восьм|девят|десят)\\S*( по списку| из списка)?")) {
                int i = ordinalIn(who); if (i > 0) return "打第" + i + "个";                    // LIST_SELECTION_PHONE
            }
            if (who.matches("[\\d\\s+-]+")) return "拨打" + who.replaceAll("[^\\d+]", "");     // digits -> dial number
            if (!who.isEmpty() && who.split("\\s+").length <= 3) return "给" + who + "打电话";  // CALL_REQUEST — RU contact name as-is
            return null;                                       // «позвони пушкина новые ворота 11» = an address, not a contact
        }
        if (s.contains("перезвони")) return "回拨";                                           // CALL_BACK
        if (s.contains("ответь") || s.contains("возьми трубку") || s.contains("прими звонок")) return "接听"; // ANSWER_CALL
        if (s.contains("сбрось") || s.contains("положи трубку") || s.contains("отбой")
            || s.contains("скинь вызов") || s.contains("скинь звонок")) return "挂断";        // HANG_UP
        if (s.contains("отклони") || s.contains("не отвечай")) return "拒接";                 // IGNORE_CALL
        if (s.contains("отмени вызов") || s.contains("отмени звонок")) return "取消拨打";     // CANCEL_DIAL
        if (s.contains("журнал") && s.contains("вызов") || s.contains("история звонк")) return "打开通话记录"; // RENDER_CALL_HISTORY
        if (s.contains("пропущенн")) return "播放未接来电";
        if (s.contains("контакт")) {
            if (s.contains("синхрон")) return "同步通讯录";                                   // SYNC_PHONE_CONTACT
            if (s.contains("найди")) {                                                        // SEARCH_PHONEBOOK
                String who = tailAfter(s, "найди");
                if (who.startsWith("в контактах")) who = who.substring("в контактах".length()).trim();
                if (who.startsWith("контактах")) who = who.substring("контактах".length()).trim();
                if (who.startsWith("контакт")) who = who.replaceFirst("^контакт\\S*\\s*", "");
                if (!who.isEmpty()) return "查找联系人" + who;
            }
            return "打开通讯录";                                                              // RENDER_PHONE_CONTACT
        }
        return null;
    }

    // ---------------------------------------------------------------- sound / media

    private static String zhSoundMedia(String s, boolean off, String grade, int n) {
        if (s.contains("выключи звук") || s.contains("без звука") || s.contains("замолчи")
            || s.contains("убери звук") || s.contains("убрать звук")) return "静音";           // MUTE
        if (s.contains("включи звук") || s.contains("со звуком") || s.contains("верни звук")) return "取消静音"; // UNMUTE
        if (s.contains("хватит") || s.contains("перестань говорить") || s.contains("замолкни")) return "停止播报"; // STOP_BROADCAST
        // "на полную (катушку)" рядом со звуком/музыкой = volume max
        if ((s.contains("на полную") || s.contains("на всю катушку"))
            && (s.contains("звук") || s.contains("громк") || s.contains("музы") || s.contains("колонк")))
            return s.contains("музы") ? "音乐音量调到最大" : "音量调到最大";
        if ((s.contains("громкост") || s.contains("громче") || s.contains("тише")
             || (s.contains("звук") && (grade.length() > 0 || n >= 0)))
            && !s.contains("едь") && !s.contains("езжай") && !s.contains("качеств")) {                                   // "тише едь" - не про громкость
            // "музыку громче" = громкость МЕДИА (音乐音量), иначе штатная система крутит
            // громкость голосового ассистента; generic 声音 — только без упоминания музыки
            // Volume CHANNEL = SET_VOLUME slot audio_source (verified on the car 2026-09-05, NLU + DM):
            //   音乐音量 -> MULTIMEDIA (多媒体), 导航音量 -> NAVIGATION (导航), 通话音量 -> PHONE (蓝牙电话),
            //   语音音量 -> BROADCAST (语音 = the assistant's own voice). Bare 音量/声音 -> DM default = media.
            boolean media = s.contains("музы") || s.contains("музон") || s.contains("песн")
                || s.contains("трек") || s.contains("медиа") || s.contains("радио");
            boolean navi  = s.contains("навигац") || s.contains("навигатор") || s.contains("карт");
            boolean phone = s.contains("телефон") || s.contains("звонк") || s.contains("звонок")
                || s.contains("разговор") || s.contains("вызов") || s.contains("собеседник");
            boolean voice = s.contains("ассистент") || s.contains("помощник") || s.contains("помошник")
                || s.contains("голос") || s.contains("тво") || s.contains("тебя") || s.contains("себя") || s.contains("подсказ")
                || s.contains("озвучк") || s.contains("реч") || s.contains("говори");        // «говори тише» = голос ассистента
            String vol = media ? "音乐音量" : navi ? "导航音量" : phone ? "通话音量" : voice ? "语音音量" : "音量";
            String step = media ? "音乐音量" : navi ? "导航音量" : phone ? "通话音量" : voice ? "语音音量" : "声音";
            // "громче на 5" is a RELATIVE step, not "set volume to 5"
            boolean rel = s.contains("громче") || s.contains("тише") || s.contains("прибав") || s.contains("убав");
            if (!rel && n >= 0 && n <= 40 && (s.contains("громкост") || s.contains("на "))) return vol + "调到" + n; // SET_VOLUME
            if (grade.equals("MAX") || s.contains("на полную")) return vol + "调到最大";
            if (grade.equals("MIN")) return vol + "调到最小";
            if (s.contains("громче") || grade.equals("PLUS") || plusWordOf(s)) return step + "调大一点";
            if (s.contains("тише") || grade.equals("MINUS") || minusWordOf(s)) return step + "调小一点";
            return null;
        }
        if (s.contains("что") && s.contains("играет")) return "这是什么歌";                   // "что играет" без слова "песня"
        if (s.contains("играй дальше") || s.trim().equals("играй")) return "播放";            // resume без слова "музыка"
        // media source switch before the track branch ("включи блютус музыку" is a source, not a song)
        if (s.contains("блютус") || s.contains("bluetooth")) return "切换到蓝牙音乐";          // CONTROL_MEDIA_SOURCE
        if (s.contains("юсб") || s.contains("usb") || s.contains("флешк")) return "切换到USB音乐";
        // radio station next/prev ("следующая станция")
        if (s.contains("станци") || s.contains("канал")) {
            if (s.contains("предыдущ") || s.contains("прошл")) return "上一首";
            if (s.contains("следующ") || s.contains("переключи") || s.contains("смени") || s.contains("друг")) return "下一首";
        }
        // "песен(ку)" - fleeting vowel; "музычку" needs the shorter root "музы"
        boolean track = s.contains("трек") || s.contains("песн") || s.contains("песен")
            || s.contains("композиц") || s.contains("музы") || s.contains("музон");
        if (track) {
            int idx = ordinalIn(s);
            if (idx > 0 && (s.contains("включи") || s.contains("поставь") || s.contains("выбери") || s.contains("запусти")))
                return "播放第" + idx + "首";                                                 // LIST_SELECTION_MEDIA
            if (s.contains("следующ") || s.contains("дальше") || s.contains("переключи") || s.contains("смени")
                || s.contains("другую") || s.contains("другой трек")) return "下一首";        // NEXT_MEDIA
            if (s.contains("предыдущ") || s.contains("прошл")) return "上一首";               // PREVIOUS_MEDIA
            // "не нравится" ДО "нравится" — иначе отрицание попадает в лайк
            if (s.contains("убери из избранн") || s.contains("не нрав")) return "取消收藏这首歌"; // CANCEL_COLLECT_MEDIA
            if (s.contains("нрав") || s.contains("в избранное") || s.contains("лайк") || s.contains("сохрани"))
                return "收藏这首歌";                                                          // COLLECT_MEDIA ("понравилась песня")
            if (s.contains("скачай")) return "下载这首歌";                                    // DOWNLOAD_SONG
            if (s.contains("начал") || s.contains("заново")) return "重新播放";                // RESTART_PLAYBACK ("сначала", "в начало")
            if (s.contains("что") && (s.contains("играет") || s.contains("за"))) return "这是什么歌"; // ASK_CURRENT_MEDIA
            if (s.contains("текст")) return (off ? "关闭" : "打开") + "歌词";                 // OP_LYRICS
            if (s.contains("по кругу") || s.contains("повтор")) return "单曲循环";             // SET_PLAYBACK_MODE
            if (s.contains("случайн") || s.contains("перемешай") || s.contains("вперемешку")) return "随机播放";
            if (s.contains("по порядку")) return "顺序播放";
            // проверено на авто: голое 暂停 ставит текущее воспроизведение на паузу (работает оффлайн)
            if (off || s.contains("выключи") || s.contains("останови") || s.contains("стоп")) return "暂停";
            if (s.contains("пауз")) return "暂停";                                            // проверено на авто
            if (s.contains("продолж") || s.contains("играй")) return "播放";                   // проверено на авто: 播放 запускает проигрыватель
            if ((s.contains("включи") || s.contains("вруб") || s.contains("поставь") || s.contains("запусти")
                 || s.contains("давай") || s.contains("послушать") || s.contains("послушаем") || s.contains("хочу"))
                && (s.contains("музы") || s.contains("музон"))) {
                // «включи музыку григорий лепс» names an artist: bare 播放 would resume whatever was playing.
                // Anything after the word «музыку» beyond a filler → the backend (opens the music app with a search).
                String tail = s.replaceFirst("^.*?(музы|музон)\\S*", "")            // verb-final order: «музыку включи»
                    .replaceAll("(?<![а-я])(включи|включай|вруби|врубай|поставь|запусти|давай|хочу|послушать|послушаем|отправь)(?![а-я])", " ").trim();
                if (!tail.isEmpty() && !tail.matches("((пожалуйста|мне|нам|уже|снова|опять|давай|погромче|потише|громче|тише|в машине|в салоне|какую-нибудь|любую)\\s*)+"))
                    return null;
                return "播放";
            }  // проверено на авто: голое 播放 запускает проигрыватель (оффлайн)
            String w = s.trim();
            if (w.equals("музыку") || w.equals("музыка") || w.equals("музычку")) return "播放"; // bare "музыку!"
            if (w.matches("(включи|поставь|запусти|давай|вруби|включай) (песню|песенку|песни|музон|трек|что-нибудь)")) return "播放"; // unnamed
            // A named song/artist («включи песню снуп дог») can't be expressed in Chinese with a Russian tail —
            // the stock NLU never parses it. Leave it to the backend (it launches the user's music app).
        }
        if (s.contains("перемотай")) {
            if (s.contains("начал")) return "重新播放";                                       // "перемотай в начало"
            if (n > 0 && s.contains("минут")) return "跳到第" + n + "分钟";                    // SET_PLAYBACK_TIME_POINT
            if (n > 0) return (s.contains("назад") ? "快退" : "快进") + n + "秒";              // ADJUST_PLAYBACK_PROGRESS
            return s.contains("назад") ? "快退15秒" : "快进15秒";
        }
        if (s.contains("скорост") && s.contains("воспроизведен")) {
            if (s.contains("полтора") || s.contains("1.5")) return "1.5倍速播放";              // SPEED_PLAY
            if (n == 2) return "2倍速播放";
            return "1倍速播放";
        }
        if (s.contains("радио")) {
            if (!FM_RADIO_OFFLINE && off && !s.contains("частот")) return "暂停";   // «выключи радио» = stop what is playing (works offline)
            if (!FM_RADIO_OFFLINE) return null;   // no FM intent on the A06 → backend opens the radio/music app («европа плюс», «радио онлайн»)
            if (n > 0 && (s.contains("частот") || s.contains("волн") || s.contains("fm"))) return "收音机调到" + n + "兆赫"; // OP_RADIO_HARDWARE
            return off ? "关闭收音机" : "打开收音机";
        }
        if (s.contains("источник")) {
            if (s.contains("блютус")) return "切换到蓝牙音乐";                                 // CONTROL_MEDIA_SOURCE
            if (s.contains("юсб") || s.contains("usb") || s.contains("флешк")) return "切换到USB音乐";
            if (s.contains("онлайн")) return "切换到在线音乐";
        }
        if (s.contains("случайный порядок") || s.contains("перемешай")) return "随机播放";    // без слова "песня"
        if (s.contains("плейлист") || s.contains("список воспроизведен")) return "打开播放列表"; // CONTROL_PLAYLIST
        if (s.contains("избранн") && (s.contains("песн") || s.contains("трек") || s.contains("музы")))
            return "打开我的收藏";                                                            // OP_MEDIA_FAVORITES_LIST ("покажи избранные песни")
        if (s.contains("истори") && s.contains("прослушив")) return "打开播放历史";           // OP_MEDIA_HISTORY
        if (s.contains("звук на водителя") || (s.contains("звуков") && s.contains("сцен")))
            return s.contains("весь") || s.contains("всех") ? "音场切换到全车" : "音场切换到主驾"; // SWITCH_SOUND_FIELD
        if (s.contains("качество звука") || s.contains("аудиофил")) return "切换到高音质";    // SET_SOUND_QUALITY
        if (s.contains("улучшени звука") || s.contains("улучшение звука")) return (off ? "关闭" : "打开") + "音质增强"; // OP_QUALITY_ENHANCE
        if (s.contains("караоке")) return (off ? "关闭" : "打开") + "无麦K歌";                // OP_NO_MIC_KARAOKE
        return null;
    }

    // ---------------------------------------------------------------- vehicleInfo

    private static String zhVehicleInfo(String s) {
        if ((s.contains("сколько") && (s.contains("заряд") || s.contains("батаре")))
            || (s.contains("заряд") && (s.contains("какой") || s.contains("уровень") || s.contains("остал") || s.contains("батаре")
                || s.contains("покажи") || s.contains("скажи")))
            || s.contains("что по заряд") || s.contains("как там заряд")
            || s.contains("запас хода") || s.contains("сколько бензина") || s.contains("остаток топлива"))
            return "还有多少电";                                                              // REMAINING_POWER
        if (s.contains("давлени") && (s.contains("шин") || s.contains("колес"))) return "胎压是多少"; // TIRE_PRESSURE
        if (s.contains("пробег")) return "现在的里程是多少";                                  // CURRENT_MILEAGE
        if (s.contains("воздух") && s.contains("салон")) return "车内空气质量怎么样";         // IN_CAR_AIR_QUALITY
        if (s.contains("пожалов") || s.contains("фидбек") || s.contains("обратн связ")) return "我要反馈问题"; // FEEDBACK
        return null;
    }

    // ---------------------------------------------------------------- smartHome (all cloud)

    private static String zhSmartHome(String s, boolean off, int n) {
        if (!s.contains("дома") && !s.contains("домашн") && !s.contains("в квартире")) return null;
        if (s.contains("кондиционер")) {
            if (n >= 16 && n <= 33) return "家里空调调到" + n + "度";                          // SET_HOME_AIR_CONDITIONER
            return (off ? "关闭" : "打开") + "家里的空调";
        }
        if (s.contains("свет")) return (off ? "关闭" : "打开") + "家里的灯";                  // SET_HOME_LIGHT
        if (s.contains("штор")) return (off ? "关闭" : "打开") + "家里的窗帘";                // SET_HOME_CURTAIN
        if (s.contains("очистител")) return (off ? "关闭" : "打开") + "家里的空气净化器";     // SET_HOME_AIR_PURIFIER
        if (s.contains("осушител")) return (off ? "关闭" : "打开") + "家里的除湿机";          // SET_HOME_DEHUMIDIFIER
        if (s.contains("пылесос")) return "让扫地机器人开始打扫";                             // SET_HOME_ROBOTIC_VACUUM
        return null;
    }

    // ---------------------------------------------------------------- appPageControl

    private static String zhAppUi(String s, boolean off) {
        if (s.contains("главный экран") || s.contains("рабочий стол") || s.contains("на главную")) return "返回桌面"; // BACK_DESKTOP ("домой" -> navi 回家)
        if ((s.contains("ассистент") || s.contains("помощник")) && (off || s.contains("выйди") || s.contains("закройся")))
            return "退出语音";                                                                // EXIT_VOICE_ASSIST
        if (s.contains("отстань") || s.contains("уйди") || s.contains("отвали")
            || s.trim().equals("закройся")) return "退下";                                    // EMOTION_EXIT_ASSIST
        if (s.contains("предыдущ") && (s.contains("экран") || s.contains("страниц"))) return "返回上一页"; // GO_BACK_PAGE
        if (s.contains("внешн") && (s.contains("голос") || s.contains("динамик")))
            return (off ? "关闭" : "打开") + "车外语音";                                      // OP_EXTERNAL_VOICE
        if (s.contains("галере") || (s.contains("фотограф") && s.contains("открой"))) return "打开相册"; // OP_PHOTO_ALBUM
        if (s.contains("настройк")) {
            if (s.contains("подсветк")) return "打开氛围灯设置";                              // CONTROL_AMBIENT_LIGHTING_PAGE
            if (s.contains("зеркал")) return "打开后视镜设置";                                // CONTROL_REAR_MIRROR_PAGE
            return "打开设置";                                                                // CONTROL_APP
        }
        if (s.contains("сценари") && s.contains("открой")) return "打开场景模式页面";         // CONTROL_SCENARIO_PAGE
        if (s.contains("приложени") || s.contains("открой") || s.contains("запусти")) {
            String app = zhAppOf(s);
            if (!app.isEmpty()) return (off || s.contains("закрой") ? "关闭" : "打开") + app; // CONTROL_APP
        }
        return null;
    }

    // ---------------------------------------------------------------- generalControl (bare context words — LAST)

    private static String zhGeneralUi(String s, int n) {
        String w = s.trim();
        if (w.equals("да") || w.equals("подтверждаю") || w.equals("согласен") || w.equals("давай")) return "确认"; // CONFIRM
        if (w.equals("нет") || w.equals("не надо") || w.equals("отмена")) return "不用了";     // CONFIRM_NO
        if (w.equals("назад") || w.equals("вернись")) return "返回";                          // BACK
        if (w.equals("продолжай") || w.equals("продолжи")) return "继续";                     // CONTINUE
        if (s.contains("пауз") || w.equals("подожди")) return "暂停";                         // PAUSE ("поставь на паузу")
        if (w.equals("следующий") || w.equals("дальше") || w.equals("следующая")) return "下一个"; // NEXT
        if (w.equals("предыдущий") || w.equals("предыдущая")) return "上一个";                // PREVIOUS
        if (w.equals("закрой все")) return "全部关闭";                                        // CLOSE_ALL
        int i = ordinalIn(s);
        if (i > 0) {
            if (s.contains("страниц")) return "第" + i + "页";                                // PAGE_SELECTION
            if (s.contains("удали")) return "删除第" + i + "个";                              // DELETE_INDEX
            if (s.contains("позвони") || s.contains("набери")) return "打第" + i + "个";      // LIST_SELECTION_PHONE
            // LIST_SELECTION_MEDIA only with a track word or a bare two-word «включи третью»: a number inside a longer
            // phrase («рекуперацию на десять процентов», «зарядку с пяти утра») is NOT a track index
            if (s.contains("песн") || s.contains("трек") || s.contains("композиц")
                || w.matches("(включи|поставь|запусти) \\S+")) return "播放第" + i + "首";
            if (s.contains("выбери") || w.matches("(перв|втор|трет|четверт|пят)\\S*")) return "第" + i + "个"; // LIST_SELECTION
        }
        if (s.contains("листай") || s.contains("следующая страница")) return "下一页";        // TURN_PAGE
        if (s.contains("предыдущая страница")) return "上一页";
        if (s.contains("в избранное") || w.equals("сохрани")) return "收藏";                  // COLLECT
        if (s.contains("убери из избранного")) return "取消收藏";                             // CANCEL_COLLECT
        if (s.contains("избранное") && s.contains("открой")) return "打开收藏夹";             // OP_COLLECTION
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // NOTE on cloud/chat domains (weather, lifeService, carKnowledge, xiaoAnWorldview,
    // sceneArrangement, memorizeUserInfo): these are useCloud — an unmatched phrase falls through to
    // null and handlePhraseZh() routes it to the chat LLM, which is the intended behaviour. Weather
    // questions can optionally be pre-translated (今天天气怎么样 etc., see RESULT.md) when the stock
    // cloud stack is reachable.
    // NOTE: langOf() from the TODO list is intentionally NOT added — the 328-intent catalog contains
    // no system-language intent to feed it.

    // ---------------------------------------------------------------- shared phrase helpers

    /** SrBaseSession direction number -> our normalized zone code. DirectUtils numbering:
     *  ALL=0 LEFT=1 RIGHT=2 REAR_LEFT=3 REAR_RIGHT=4 MID=5 (stand/knowledge/06-widget-scene-protocol.md).
     *  3/4 must stay left/right: a rear-right passenger saying «открой окно» means HIS window
     *  (后排右车窗), not both rear windows (后排车窗). */
    static String zoneCode(int dir) {
        switch (dir) {
            case 1: return "DRIVER";
            case 2: return "PASSENGER";
            case 3: return "REAR_LEFT";
            case 4: return "REAR_RIGHT";
            case 5: return "REAR";
            default: return "DRIVER";
        }
    }

    // Grade words -> normalized grade codes (as used by arbiConfig/dm: PLUS/MINUS/MIN/MAX).
    static String gradeOf(String s) {
        if (s.contains("максим") || s.contains("на всю") || s.contains("полностью")) return "MAX";
        if (s.contains("миним")) return "MIN";
        if (s.contains("тепл") || s.contains("больш") || s.contains("выше") || s.contains("прибав")
            || s.contains("громч") || s.contains("ярче") || s.contains("сильн")) return "PLUS";
        if (s.contains("холод") || s.contains("мень") || s.contains("ниже") || s.contains("убав")
            || s.contains("тише") || s.contains("темнее") || s.contains("слаб")) return "MINUS";
        return "";
    }
    static boolean isOn(String s) {
        return s.contains("включ") || s.contains("откр") || s.contains("подним") || s.contains("запус")
            || s.contains("вклчю") || s.contains("вкл ");
    }
    static boolean isOff(String s) {
        return s.contains("выключ") || s.contains("отключ") || s.contains("выруб") || s.contains("закр")
            || s.contains("закро") || s.contains("прикро") || s.contains("задвинь")
            || ((s.contains("крой") || s.contains("кройте")) && !s.contains("откр"))
            || s.contains("опусти") || s.contains("останов") || s.contains("выкл ");
    }

    /** Plain integer in the phrase: a digit run (1..4 digits — longer runs are phone numbers, skipped),
     *  else a spoken Russian numeral ("двадцать три" -> 23, "сто" -> 100, "ноль" -> 0). -1 if none. */
    static int numIn(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(s);
        while (m.find()) { if (m.group().length() <= 4) return Integer.parseInt(m.group()); }
        // «ноль/нуль/нулю» = 0 — ниже 0 считался бы «нет числа», и «холодильник на ноль градусов» включал холодильник
        if (s.contains("ноль") || s.contains("нуль") || s.contains("нулю")) return 0;
        String[] w = s.split("[^а-я]+");
        int total = -1;
        for (String t : w) {
            int v = -1;
            if (t.startsWith("одиннадцат")) v = 11;
            else if (t.startsWith("двенадцат")) v = 12;
            else if (t.startsWith("тринадцат")) v = 13;
            else if (t.startsWith("четырнадцат")) v = 14;
            else if (t.startsWith("пятнадцат")) v = 15;
            else if (t.startsWith("шестнадцат")) v = 16;
            else if (t.startsWith("семнадцат")) v = 17;
            else if (t.startsWith("восемнадцат")) v = 18;
            else if (t.startsWith("девятнадцат")) v = 19;
            else if (t.startsWith("двадцат")) v = 20;
            else if (t.startsWith("тридцат")) v = 30;
            else if (t.startsWith("сорок")) v = 40;
            else if (t.startsWith("пятьдесят") || t.startsWith("пятидесят")) v = 50;
            else if (t.startsWith("шестьдесят")) v = 60;
            else if (t.startsWith("семьдесят")) v = 70;
            else if (t.startsWith("восемьдесят")) v = 80;
            else if (t.startsWith("девяност")) v = 90;
            else if (t.startsWith("сто") || t.startsWith("ста")) v = 100;
            else if (t.equals("один") || t.equals("одна") || t.equals("одну")) v = 1;
            else if (t.equals("два") || t.equals("две")) v = 2;
            else if (t.equals("три")) v = 3;
            else if (t.startsWith("четыре")) v = 4;
            else if (t.equals("пять")) v = 5;
            else if (t.equals("шесть")) v = 6;
            else if (t.equals("семь")) v = 7;
            else if (t.startsWith("восемь")) v = 8;
            else if (t.startsWith("девять")) v = 9;
            else if (t.startsWith("десять")) v = 10;
            if (v >= 20 && v <= 90 && t.length() > 8) {               // «двадцатьшесть», «тридцатьдва» written as one word
                String[] u = {"один", "одна", "два", "две", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"};
                int[] uv = {1, 1, 2, 2, 3, 4, 5, 6, 7, 8, 9};
                for (int k = 0; k < u.length; k++) if (t.endsWith(u[k]) && t.length() > u[k].length() + 4) { v += uv[k]; break; }
            }
            if (v >= 0) {
                if (total < 0) total = v;
                else if (total % 10 == 0 && v < 10) total += v;   // "двадцать" + "три" -> 23
                else total = v;
            }
        }
        return total;
    }

    /** Temperature-like value with an optional half: "22.5"/"22,5" -> "22.5", "двадцать два с половиной"
     *  -> "22.5", "23 и пять (десятых)" -> "23.5", "22" -> "22". null if no number. */
    static String tempValueOf(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})[.,](\\d)").matcher(s);
        if (m.find()) return m.group(2).equals("0") ? m.group(1) : m.group(1) + "." + m.group(2);
        boolean half = s.contains("с половиной") || s.contains("и пять десятых") || s.contains("и половин")
            || s.matches(".*(?<![а-я])и пять(?![а-я]).*");
        String base = half ? s.replace("с половиной", " ").replace("и пять десятых", " ")
            .replace("и половиной", " ").replaceAll("(?<![а-я])и пять(?![а-я])", " ") : s;
        int n = numIn(base);
        if (n < 0) return null;
        return half ? n + ".5" : String.valueOf(n);
    }

    /** Russian color word -> normalized color token (best-effort). */
    static String colorOf(String s) {
        if (s.contains("красн")) return "RED";
        if (s.contains("син")) return "BLUE";
        if (s.contains("зелен")) return "GREEN";
        if (s.contains("бел")) return "WHITE";
        if (s.contains("желт")) return "YELLOW";
        if (s.contains("фиолет")||s.contains("пурпур")) return "PURPLE";
        if (s.contains("оранж")) return "ORANGE";
        if (s.contains("розов")) return "PINK";
        return "";
    }

    /** Normalized color code -> Chinese color word. */
    private static String zhColor(String c) {
        if ("RED".equals(c)) return "红色";
        if ("BLUE".equals(c)) return "蓝色";
        if ("GREEN".equals(c)) return "绿色";
        if ("WHITE".equals(c)) return "白色";
        if ("YELLOW".equals(c)) return "黄色";
        if ("PURPLE".equals(c)) return "紫色";
        if ("ORANGE".equals(c)) return "橙色";
        if ("PINK".equals(c)) return "粉色";
        return "";
    }
}
