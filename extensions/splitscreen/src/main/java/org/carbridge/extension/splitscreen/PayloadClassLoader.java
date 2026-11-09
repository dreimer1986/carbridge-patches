package org.carbridge.extension.splitscreen;

import android.content.Context;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;

import dalvik.system.InMemoryDexClassLoader;

/**
 * Loads the payload dex from the app's assets, apart from the app's classes. So the payload's Car
 * App Library is used even when the app ships another version of it. Only the platform and this
 * extension's own classes come from outside the payload.
 */
final class PayloadClassLoader extends ClassLoader {
    // Written by the patch, with the payload's class-path resources beside it
    private static final String ASSETS = "carbridge/";
    private static final String DEX = "splitscreen.dex";
    private static final String EXTENSION = "org.carbridge.extension.splitscreen.";
    private static final String PAYLOAD = EXTENSION + "payload.";

    private static ClassLoader payload;

    private final ClassLoader app;
    private final Context context;

    private PayloadClassLoader(ClassLoader app, Context context) {
        // The boot class loader: platform classes resolve first, everything else below
        super(Context.class.getClassLoader());
        this.app = app;
        this.context = context;
    }

    @NonNull
    static synchronized ClassLoader get(@NonNull Context context) throws IOException {
        if (payload == null) {
            ClassLoader app = PayloadClassLoader.class.getClassLoader();
            Context appContext = context.getApplicationContext();
            payload = new InMemoryDexClassLoader(ByteBuffer.wrap(readAsset(appContext, DEX)),
                new PayloadClassLoader(app, appContext));
        }
        return payload;
    }

    private static byte[] readAsset(Context context, String name) throws IOException {
        try (InputStream in = context.getAssets().open(ASSETS + name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    /** Shizuku and ShellService must be the app's copies: Shizuku hands its binder to those. */
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        boolean shared = name.startsWith("rikka.shizuku.") || name.startsWith("moe.shizuku.")
            || (name.startsWith(EXTENSION) && !name.startsWith(PAYLOAD));
        if (shared) return app.loadClass(name);
        throw new ClassNotFoundException(name);
    }

    /**
     * The payload's own resources, e.g. its Car App Library's API level: the app may ship another
     * version of the same file. Copied out of the assets, since a resource needs a URL.
     */
    @Override
    protected URL findResource(String name) {
        if (name.contains("..")) return null;
        File file = new File(context.getCodeCacheDir(), "carbridge/" + name);
        try {
            byte[] bytes = readAsset(context, name);
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return null;
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(bytes);
            }
            return file.toURI().toURL();
        } catch (IOException e) {
            return null;
        }
    }
}
