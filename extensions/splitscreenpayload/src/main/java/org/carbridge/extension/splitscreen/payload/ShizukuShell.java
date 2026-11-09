package org.carbridge.extension.splitscreen.payload;

import static org.carbridge.extension.splitscreen.payload.AppScreen.TAG;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/** Places the app's first activity on the car display with Shizuku's shell rights. */
final class ShizukuShell {
    interface Listener {
        void onStarted();

        /** Something the user must do on the phone, or a failure; shown on the car screen. */
        void onProblem(@NonNull String message);
    }

    // The extension's ShellService, merged into the app; its class name is also its descriptor
    private static final String SHELL_SERVICE = "org.carbridge.extension.splitscreen.ShellService";
    private static final int START_ON_DISPLAY = IBinder.FIRST_CALL_TRANSACTION;
    private static final int REQUEST_CODE = 0xCB;
    // Shizuku sends its binder shortly after the app process starts
    private static final long BINDER_WAIT_MS = 2000;

    private final Context context;
    private final String appName;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Shizuku.UserServiceArgs serviceArgs;
    private final Shizuku.OnRequestPermissionResultListener permissionListener = this::onPermissionResult;
    @Nullable private IBinder service;
    private boolean bound;
    @Nullable private Request pending;

    private static final class Request {
        final String component;
        final int displayId;
        final Listener listener;

        Request(String component, int displayId, Listener listener) {
            this.component = component;
            this.displayId = displayId;
            this.listener = listener;
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (binder == null || !binder.pingBinder()) return;
            service = binder;
            runPending();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    ShizukuShell(@NonNull Context context) {
        this.context = context;
        appName = context.getApplicationInfo().loadLabel(context.getPackageManager()).toString();
        serviceArgs = new Shizuku.UserServiceArgs(
            new ComponentName(context.getPackageName(), SHELL_SERVICE))
            .processNameSuffix("carbridge_shell")
            .daemon(false)
            .debuggable(false)
            .version(1);
    }

    void start(@NonNull ComponentName component, int displayId, @NonNull Listener listener) {
        if (!Shizuku.pingBinder()) {
            waitForBinder(component, displayId, listener);
            return;
        }
        if (Shizuku.isPreV11()) {
            listener.onProblem("Update Shizuku on your phone, then tap here");
            return;
        }
        pending = new Request(component.flattenToString(), displayId, listener);
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                pending = null;
                listener.onProblem("Allow " + appName + " in the Shizuku app on your phone, then tap here");
                return;
            }
            Shizuku.addRequestPermissionResultListener(permissionListener);
            Shizuku.requestPermission(REQUEST_CODE);
            listener.onProblem("Allow " + appName + " in the Shizuku prompt on your phone");
            return;
        }
        runPending();
    }

    private void waitForBinder(ComponentName component, int displayId, Listener listener) {
        Shizuku.OnBinderReceivedListener[] received = new Shizuku.OnBinderReceivedListener[1];
        Runnable timeout = () -> {
            Shizuku.removeBinderReceivedListener(received[0]);
            listener.onProblem("Start Shizuku on your phone, then tap here");
        };
        received[0] = () -> {
            Shizuku.removeBinderReceivedListener(received[0]);
            main.removeCallbacks(timeout);
            start(component, displayId, listener);
        };
        main.postDelayed(timeout, BINDER_WAIT_MS);
        Shizuku.addBinderReceivedListener(received[0], main);
    }

    void release() {
        main.removeCallbacksAndMessages(null);
        pending = null;
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        if (bound) {
            try {
                Shizuku.unbindUserService(serviceArgs, connection, true);
            } catch (RuntimeException e) {
                Log.w(TAG, "Unbinding the Shizuku service failed", e);
            }
            bound = false;
        }
        service = null;
        worker.shutdown();
    }

    private void onPermissionResult(int requestCode, int grantResult) {
        if (requestCode != REQUEST_CODE) return;
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        Request request = pending;
        if (request == null) return;
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            runPending();
        } else {
            pending = null;
            request.listener.onProblem("Shizuku access was denied. Allow it in the Shizuku app, then tap here");
        }
    }

    private void runPending() {
        Request request = pending;
        if (request == null) return;
        IBinder binder = service;
        if (binder == null) {
            if (!bound) {
                try {
                    Shizuku.bindUserService(serviceArgs, connection);
                    bound = true;
                } catch (RuntimeException e) {
                    Log.w(TAG, "Binding the Shizuku service failed", e);
                    pending = null;
                    request.listener.onProblem("Couldn't connect to Shizuku. Tap to try again");
                }
            }
            return;
        }
        pending = null;
        // am start takes a moment; keep it off the main thread
        worker.execute(() -> {
            String error = transact(binder, request);
            main.post(() -> {
                if (error == null) {
                    request.listener.onStarted();
                } else {
                    Log.w(TAG, "Shell start failed: " + error);
                    request.listener.onProblem("Couldn't open the app on the car screen. Tap to try again");
                }
            });
        });
    }

    @Nullable
    private String transact(IBinder binder, Request request) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SHELL_SERVICE);
            data.writeString(request.component);
            data.writeInt(request.displayId);
            binder.transact(START_ON_DISPLAY, data, reply, 0);
            reply.readException();
            return reply.readString();
        } catch (RemoteException | RuntimeException e) {
            service = null;
            return e.toString();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
