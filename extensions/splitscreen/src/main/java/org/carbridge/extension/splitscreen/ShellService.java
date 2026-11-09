package org.carbridge.extension.splitscreen;

import android.content.Context;
import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Shizuku user service: Shizuku runs it in a separate process with shell rights. Android only lets
 * the app start activities on its own virtual display once one of them is there; the shell may
 * place that first one. It only ever starts this app's own activities.
 */
public final class ShellService extends Binder {
    // Duplicated in the payload's ShizukuShell, which can't reference this class
    static final String DESCRIPTOR = "org.carbridge.extension.splitscreen.ShellService";
    static final int START_ON_DISPLAY = FIRST_CALL_TRANSACTION;
    // Shizuku's UserService destroy() transaction
    private static final int DESTROY = 16777115;
    private static final Pattern COMPONENT = Pattern.compile("[\\w.]+/[\\w.$]+");

    private final String packageName;

    public ShellService(@NonNull Context context) {
        packageName = context.getPackageName();
    }

    @Override
    protected boolean onTransact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags)
            throws RemoteException {
        if (code == DESTROY) {
            System.exit(0);
            return true;
        }
        if (code != START_ON_DISPLAY) return super.onTransact(code, data, reply, flags);
        data.enforceInterface(DESCRIPTOR);
        String error = start(data.readString(), data.readInt());
        if (reply != null) {
            reply.writeNoException();
            reply.writeString(error);
        }
        return true;
    }

    /** Returns null on success, otherwise what went wrong. */
    @Nullable
    private String start(@Nullable String component, int displayId) {
        if (component == null || !COMPONENT.matcher(component).matches()
                || !component.startsWith(packageName + "/") || displayId <= 0) {
            return "Rejected start request: " + component + " on display " + displayId;
        }
        ProcessBuilder builder = new ProcessBuilder(
            "/system/bin/am", "start", "--display", String.valueOf(displayId),
            // FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK
            "-f", "0x18000000", "-n", component)
            .redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output = read(process.getInputStream());
            int exit = process.waitFor();
            // am reports most failures on stdout with exit code 0
            if (exit != 0 || output.contains("Error") || output.contains("Exception")) {
                return output.trim();
            }
            return null;
        } catch (IOException e) {
            return e.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return e.toString();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int n; (n = in.read(buffer)) != -1; ) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
