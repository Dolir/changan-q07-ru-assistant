package com.stand.bridge;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/**
 * Where the models live. Normally they are not in the APK: OpenQiyuan downloads them into its own folder, which the
 * assistant can read (both run as uid 1000), and which survives going back to stock and returning to the mod.
 * The assistant's own folder is what older builds unpacked to, used until OpenQiyuan moves it. A standalone build
 * (BUNDLE_MODELS in build_q07.sh) carries them as assets and unpacks them there on the first run.
 */
public final class ModelStore {
    private static final String TAG = "ModelStore";
    private static final String SHARED = "/data/data/org.openqiyuan.app/files/assistant/models";

    private ModelStore() {}

    public static File dir(Context c, String name) {
        File shared = new File(SHARED, name);
        if (shared.isDirectory()) return shared;
        File own = new File(c.getFilesDir(), name);
        unpack(c, name, own);
        return own;
    }

    /** A rename finishes the job, so an existing folder is complete. The lock is for the assistant's other processes. */
    private static synchronized void unpack(Context c, String name, File own) {
        if (own.isDirectory()) return;
        File tmp = new File(c.getFilesDir(), name + ".unpacking");
        try {
            String[] assets = c.getAssets().list(name);
            if (assets == null || assets.length == 0) return;
            try (RandomAccessFile file = new RandomAccessFile(new File(c.getFilesDir(), ".models.lock"), "rw");
                 FileLock lock = file.getChannel().lock()) {
                if (own.isDirectory()) return;
                Log.i(TAG, "unpacking " + name);
                delete(tmp);
                copy(c, name, tmp);
                if (!tmp.renameTo(own)) throw new java.io.IOException("cannot rename " + tmp);
                Log.i(TAG, "unpacked " + name);
            }
        } catch (Throwable t) {
            Log.e(TAG, "unpack " + name, t);
            delete(tmp);
        }
    }

    private static void copy(Context c, String asset, File to) throws Exception {
        String[] children = c.getAssets().list(asset);
        if (children != null && children.length > 0) {
            if (!to.mkdirs()) throw new java.io.IOException("cannot create " + to);
            for (String child : children) copy(c, asset + "/" + child, new File(to, child));
            return;
        }
        try (InputStream in = c.getAssets().open(asset); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void delete(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File child : children) delete(child);
        f.delete();
    }
}
