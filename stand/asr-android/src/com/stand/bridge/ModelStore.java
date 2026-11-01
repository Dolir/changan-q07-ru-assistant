package com.stand.bridge;

import android.content.Context;

import java.io.File;

/**
 * Where the models live. They are not in the APK: OpenQiyuan downloads them into its own folder, which the
 * assistant can read (both run as uid 1000), and which survives going back to stock and returning to the mod.
 * The assistant's own folder is what older builds unpacked to, used until OpenQiyuan moves it.
 */
public final class ModelStore {
    private static final String SHARED = "/data/data/org.openqiyuan.app/files/assistant/models";

    private ModelStore() {}

    public static File dir(Context c, String name) {
        File shared = new File(SHARED, name);
        return shared.isDirectory() ? shared : new File(c.getFilesDir(), name);
    }
}
