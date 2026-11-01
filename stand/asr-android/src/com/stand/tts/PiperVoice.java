package com.stand.tts;

import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.File;

/**
 * A Piper (VITS) voice through the sherpa-onnx already in the APK. A voice is a folder: one .onnx file,
 * tokens.txt and espeak-ng-data, as Piper voices are published, so any of them can be dropped in.
 * generate() is not thread-safe, the caller holds a lock.
 */
final class PiperVoice {
    private static final String TAG = "PiperVoice";
    private static final int THREADS = 2;

    private final OfflineTts tts;

    private PiperVoice(OfflineTts tts) {
        this.tts = tts;
    }

    /** @return null if the folder holds no complete voice. */
    static PiperVoice load(File dir) {
        File[] models = dir.listFiles((d, name) -> name.endsWith(".onnx"));
        File tokens = new File(dir, "tokens.txt");
        File data = new File(dir, "espeak-ng-data");
        if (models == null || models.length != 1 || !tokens.isFile() || !data.isDirectory()) {
            Log.e(TAG, "no complete voice in " + dir);
            return null;
        }
        long t0 = System.currentTimeMillis();
        OfflineTtsVitsModelConfig vits = OfflineTtsVitsModelConfig.builder()
                .setModel(models[0].getAbsolutePath())
                .setTokens(tokens.getAbsolutePath())
                .setDataDir(data.getAbsolutePath())
                .build();
        OfflineTtsModelConfig model = OfflineTtsModelConfig.builder()
                .setVits(vits)
                .setNumThreads(THREADS)
                .setProvider("cpu")
                .build();
        PiperVoice voice = new PiperVoice(new OfflineTts(OfflineTtsConfig.builder().setModel(model).build()));
        Log.i(TAG, "voice " + models[0].getName() + " ready in " + (System.currentTimeMillis() - t0) + "ms, rate=" + voice.rate());
        return voice;
    }

    int rate() {
        return tts.getSampleRate();
    }

    float[] synthesize(String text) {
        GeneratedAudio audio = tts.generate(text);
        return audio.getSamples();
    }
}
