package com.stand.loader;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.file.Files;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexClassLoader;

/**
 * Lets our code (classes35.dex, ~500 KB) be updated without reinstalling the 730 MB APK: a dex at
 * files/overlay/classes.dex goes ahead of the bundled one on the app's class path. Lives in its own dex and is
 * never overlaid. VoiceApp must not reference com.stand.bridge directly: verifying VoiceApp could load the bundled
 * classes before install() runs, so boot() reaches them by reflection.
 *
 * The folder is writable only by uid 1000. Whoever writes there (OpenQiyuan) checks the signed manifest first.
 */
public final class DexOverlay {

    private static final String TAG = "DexOverlay";
    private static final int MAX_BOOTS = 3;
    /** A boot that survives this long counts as good and clears the crash counter. */
    private static final long GOOD_AFTER_MS = 60_000L;

    private DexOverlay() {}

    /** From VoiceApp.attachBaseContext, in every process: before any class of ours is loaded. */
    public static void install(Context base) {
        try {
            File dir = new File(base.getFilesDir(), "overlay");
            File dex = new File(dir, "classes.dex");
            if (!dex.isFile()) return;
            if (isMainProcess(base) && !countBoot(dir, dex)) return;
            File opt = new File(dir, "oat");
            opt.mkdirs();
            ClassLoader app = base.getClassLoader();
            DexClassLoader overlay = new DexClassLoader(dex.getPath(), opt.getPath(), null, app);
            prepend(app, overlay);
            Log.i(TAG, "overlay on: size=" + dex.length() + " time=" + dex.lastModified());
        } catch (Throwable t) {
            Log.e(TAG, "overlay failed, using the bundled dex", t);
        }
    }

    /** From VoiceApp.onCreate. */
    public static void boot(Application app) {
        try {
            Class.forName("com.stand.bridge.RuBridge").getMethod("init", Context.class).invoke(null, app);
        } catch (Throwable t) {
            Log.e(TAG, "RuBridge.init", t);
        }
        try {
            BroadcastReceiver r = (BroadcastReceiver) Class.forName("com.stand.bridge.StandNluReceiver").newInstance();
            app.registerReceiver(r, new IntentFilter("com.stand.NLU"), 2);
        } catch (Throwable t) {
            Log.e(TAG, "StandNluReceiver", t);
        }
        if (isMainProcess(app)) {
            final File attempts = new File(new File(app.getFilesDir(), "overlay"), "attempts");
            new Handler(Looper.getMainLooper()).postDelayed(attempts::delete, GOOD_AFTER_MS);
        }
    }

    /** False when this dex keeps crashing the process: it is set aside and the bundled one runs. */
    private static boolean countBoot(File dir, File dex) throws Exception {
        File attempts = new File(dir, "attempts");
        int n = 0;
        if (attempts.isFile()) {
            try { n = Integer.parseInt(new String(Files.readAllBytes(attempts.toPath())).trim()); } catch (NumberFormatException ignored) {}
        }
        if (n >= MAX_BOOTS) {
            Log.e(TAG, "overlay crashed " + n + " boots in a row, setting it aside");
            dex.renameTo(new File(dir, "classes.dex.bad"));
            attempts.delete();
            return false;
        }
        try (FileOutputStream out = new FileOutputStream(attempts)) {
            out.write(String.valueOf(n + 1).getBytes());
        }
        return true;
    }

    /** Puts the overlay's dex elements in front of the app loader's own. */
    private static void prepend(ClassLoader app, ClassLoader overlay) throws Exception {
        Field pathListField = BaseDexClassLoader.class.getDeclaredField("pathList");
        pathListField.setAccessible(true);
        Object appPath = pathListField.get(app);
        Object overlayPath = pathListField.get(overlay);
        Field elementsField = appPath.getClass().getDeclaredField("dexElements");
        elementsField.setAccessible(true);
        Object own = elementsField.get(appPath);
        Object extra = elementsField.get(overlayPath);
        int a = Array.getLength(extra), b = Array.getLength(own);
        Object merged = Array.newInstance(own.getClass().getComponentType(), a + b);
        System.arraycopy(extra, 0, merged, 0, a);
        System.arraycopy(own, 0, merged, a, b);
        elementsField.set(appPath, merged);
    }

    private static boolean isMainProcess(Context c) {
        return c.getPackageName().equals(Application.getProcessName());
    }
}
