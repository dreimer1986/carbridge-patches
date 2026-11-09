package org.carbridge.extension.splitscreen;

import android.app.Service;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * The car app service Android Auto binds to. The real service lives in the payload, which brings
 * its own Car App Library: apps that ship an older copy of that library would otherwise replace
 * classes of ours with theirs when the patch merges the two.
 */
public final class SplitScreenCarAppService extends Service {
    private static final String IMPL = "org.carbridge.extension.splitscreen.payload.CarAppServiceImpl";

    private Service impl;

    @Override
    public void onCreate() {
        super.onCreate();
        impl = createImpl(this);
        impl.onCreate();
    }

    private static Service createImpl(Context context) {
        try {
            Service service = (Service) PayloadClassLoader.get(context)
                .loadClass(IMPL).getDeclaredConstructor().newInstance();
            // A Service only needs its base context for what the Car App Library uses
            Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
            attach.setAccessible(true);
            attach.invoke(service, ((ContextWrapper) context).getBaseContext());
            return service;
        } catch (IOException | ReflectiveOperationException e) {
            Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
            throw new IllegalStateException("Couldn't load the split-screen payload", cause);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(@NonNull Intent intent) {
        return impl.onBind(intent);
    }

    @Override
    public boolean onUnbind(@NonNull Intent intent) {
        return impl.onUnbind(intent);
    }

    @Override
    public void onRebind(@NonNull Intent intent) {
        impl.onRebind(intent);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration config) {
        super.onConfigurationChanged(config);
        impl.onConfigurationChanged(config);
    }

    @Override
    public void onDestroy() {
        impl.onDestroy();
        super.onDestroy();
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("CarBridge split screen");
    }
}
