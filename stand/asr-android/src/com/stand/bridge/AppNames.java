package com.stand.bridge;

import java.util.Collections;
import java.util.List;
import java.util.Arrays;

/**
 * «открой ютуб», «запусти яндекс навигатор»: which app the phrase names. Labels of installed apps are mostly
 * Latin and the recognizer writes Russian, with case endings, so names are compared by a Latin form of the word stems.
 */
final class AppNames {
    private static final String[] VERBS = { "открой", "окрой", "открыть", "запусти", "запустить", "включи", "покажи", "зайди в", "перейди в" };
    private static final String[] NOISE = { "приложение", "программу", "программа", "пожалуйста" };

    /** Names that don't read like the label; the first installed package wins. */
    private static final String[][][] KNOWN = {
        { {"ютуб", "youtube", "you tube"}, {"com.google.android.youtube", "org.smarttube.stable", "org.schabi.newpipe", "com.biomes.vanced"} },
        { {"смарттьюб", "смарт тьюб", "смарт тюб"}, {"org.smarttube.stable"} },
        { {"навигатор", "навигация", "яндекс навигатор", "яндекс нави", "нави"}, {"ru.yandex.yandexnavi", "ru.dublgis.dgismobile", "ru.yandex.yandexmaps"} },
        { {"2гис", "два гис", "дубльгис", "дубль гис", "гис"}, {"ru.dublgis.dgismobile"} },
        { {"яндекс музыка"}, {"ru.yandex.music"} },
        { {"ватсап", "вотсап", "вацап", "whatsapp"}, {"com.whatsapp"} },
        { {"браузер", "брейв"}, {"com.brave.browser"} },
        { {"антирадар"}, {"com.mybedy.antiradar.rustore"} },
        { {"рустор", "русстор", "rustore"}, {"ru.vk.store"} },
    };

    private AppNames() {}

    /** The app name in the phrase, or null if the phrase doesn't ask to open an app. */
    static String nameIn(String phrase) {
        String p = phrase.trim().toLowerCase();
        for (String verb : VERBS) {
            if (!p.startsWith(verb + " ")) continue;
            String name = " " + p.substring(verb.length()) + " ";
            for (String n : NOISE) name = name.replace(" " + n + " ", " ");
            name = name.trim();
            return name.length() < 2 ? null : name;
        }
        return null;
    }

    static List<String> knownPackages(String name) {
        String form = form(name);
        for (String[][] app : KNOWN) {
            for (String spoken : app[0]) if (form(spoken).equals(form)) return Arrays.asList(app[1]);
        }
        return Collections.emptyList();
    }

    /** «открой навигатор» names no app, so it is the one chosen in OpenQiyuan; «яндекс навигатор» is that app. */
    static boolean isAnyNavigator(String name) {
        String form = form(name);
        return form.equals(form("навигатор")) || form.equals(form("навигация")) || form.equals(form("нави"));
    }

    static boolean sameName(String spoken, String label) {
        String a = form(spoken), b = form(label);
        return distance(a, b) <= Math.min(a.length(), b.length()) / 5;
    }

    /** Stems of the words in Latin letters: «яндекс музыку» and «Yandex Музыка» give the same form. */
    private static String form(String name) {
        StringBuilder out = new StringBuilder();
        for (String word : name.toLowerCase().replace('ё', 'е').split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 5 && "ауыиюяе".indexOf(word.charAt(word.length() - 1)) >= 0) {
                word = word.substring(0, word.length() - 1);
            }
            for (int i = 0; i < word.length(); i++) out.append(latin(word.charAt(i)));
        }
        return out.toString();
    }

    private static String latin(char c) {
        final String cyr = "абвгдежзийклмнопрстуфхцчшщъыьэюя";
        final String[] lat = { "a", "b", "v", "g", "d", "e", "zh", "z", "i", "i", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u",
            "f", "h", "c", "ch", "sh", "sh", "", "i", "", "e", "yu", "ya" };
        int i = cyr.indexOf(c);
        if (i >= 0) return lat[i];
        return c == 'y' ? "i" : String.valueOf(c);
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int swap = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(swap, Math.min(prev[j], cur[j - 1]) + 1);
            }
            prev = cur;
        }
        return prev[b.length()];
    }
}
