package com.stand.bridge;

public class AppNamesTest {
    private static int failed;

    private static void check(boolean ok, String what) {
        if (!ok) { failed++; System.out.println("FAIL " + what); }
    }

    private static void name(String phrase, String expected) {
        String got = AppNames.nameIn(phrase);
        check(expected == null ? got == null : expected.equals(got), phrase + " -> " + got);
    }

    private static void known(String phrase, String pkg) {
        check(AppNames.knownPackages(AppNames.nameIn(phrase)).contains(pkg), phrase + " -> " + pkg);
    }

    public static void main(String[] a) {
        name("открой ютуб", "ютуб");
        name("запусти приложение ватсап", "ватсап");
        name("открой приложение", null);
        name("какая погода", null);
        known("открой ютуб", "com.google.android.youtube");
        known("открой яндекс навигатор", "ru.yandex.yandexnavi");
        known("открой навигатор", "ru.yandex.yandexnavi");
        known("запусти яндекс музыку", "ru.yandex.music");
        known("открой два гис", "ru.dublgis.dgismobile");
        known("открой вотсап", "com.whatsapp");
        check(AppNames.sameName("телеграм", "Telegram"), "телеграм");
        check(AppNames.sameName("телеграмм", "Telegram"), "телеграмм");
        check(AppNames.sameName("настройки", "Настройки"), "настройки");
        check(AppNames.sameName("яндекс музыку", "Яндекс Музыка"), "музыку");
        check(!AppNames.sameName("дверь", "Brave"), "дверь");
        check(!AppNames.sameName("окно", "Okko"), "окно");
        System.out.println("AppNames: FAIL=" + failed);
        if (failed > 0) System.exit(1);
    }
}
