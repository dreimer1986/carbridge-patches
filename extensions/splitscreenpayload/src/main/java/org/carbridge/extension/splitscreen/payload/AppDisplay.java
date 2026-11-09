package org.carbridge.extension.splitscreen.payload;

import static org.carbridge.extension.splitscreen.payload.AppScreen.TAG;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.car.app.SurfaceContainer;

/**
 * Runs the app's launcher activity on a virtual display sized to the visible part of the car's
 * map surface, and forwards car input to it. Everything here runs on the main thread.
 */
final class AppDisplay {
    private static final long LAUNCH_CHECK_MS = 3000;
    private static final long AREA_SETTLE_MS = 300;
    private static final long RELEASE_DELAY_MS = 5000;
    // Written by the patch's "Use the full pane height" option
    // The service the patch declares; it reads the option from its meta-data
    private static final String SERVICE = "org.carbridge.extension.splitscreen.SplitScreenCarAppService";
    private static final String FULL_HEIGHT_META = "org.carbridge.extension.splitscreen.FULL_HEIGHT";

    // Kept for a moment after its screen closes, so a restarted car session gets the app back
    @Nullable private static AppDisplay retained;

    private CarContext context;
    private Runnable onSurfaceStuck;
    private final Runnable releaseNow = this::release;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ActivityTracker tracker;
    private final TouchForwarder touch;
    private final Compositor compositor;
    private final ShizukuShell shell;
    private final boolean fullHeight;
    private final Rect area = new Rect();
    private final Rect pendingArea = new Rect();
    private final Runnable applyArea = this::applyVisibleArea;
    private VirtualDisplay virtualDisplay;
    private int surfaceWidth;
    private int surfaceHeight;
    private int dpi;
    private int displayWidth;
    private int displayHeight;
    private int stableTop;
    private boolean launching;

    private AppDisplay(@NonNull CarContext context, @NonNull Runnable onSurfaceStuck) {
        this.context = context;
        this.onSurfaceStuck = onSurfaceStuck;
        compositor = new Compositor(() -> this.onSurfaceStuck.run());
        tracker = new ActivityTracker((Application) context.getApplicationContext());
        touch = new TouchForwarder(tracker::top);
        shell = new ShizukuShell(context);
        fullHeight = readFullHeight(context);
    }

    /** Adopts the display a just-closed car session left behind, or creates one. */
    @NonNull
    static AppDisplay obtain(@NonNull CarContext context, @NonNull Runnable onSurfaceStuck) {
        AppDisplay display = retained;
        retained = null;
        if (display == null) return new AppDisplay(context, onSurfaceStuck);
        display.handler.removeCallbacks(display.releaseNow);
        display.context = context;
        display.onSurfaceStuck = onSurfaceStuck;
        Log.i(TAG, "Reusing the virtual display from the previous car session");
        return display;
    }

    /** True while a just-closed car session's display waits for a new session to adopt it. */
    static boolean isRetained() {
        return retained != null;
    }

    /** Releases the display soon, unless a new car session adopts it first. */
    void releaseLater() {
        if (retained != null && retained != this) retained.release();
        retained = this;
        handler.postDelayed(releaseNow, RELEASE_DELAY_MS);
    }

    private static boolean readFullHeight(Context context) {
        try {
            Bundle meta = context.getPackageManager().getServiceInfo(
                new ComponentName(context.getPackageName(), SERVICE),
                PackageManager.GET_META_DATA).metaData;
            return meta != null && meta.getBoolean(FULL_HEIGHT_META);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    void attach(@NonNull SurfaceContainer container) {
        Surface surface = container.getSurface();
        if (surface == null) return;
        surfaceWidth = container.getWidth();
        surfaceHeight = container.getHeight();
        dpi = container.getDpi();
        if (area.isEmpty() || !fitsSurface(area)) area.set(0, 0, surfaceWidth, surfaceHeight);
        compositor.setArea(area);
        compositor.setOutput(surface);
        updateDisplay();
        if (!tracker.hasActivities()) launch();
    }

    void detach() {
        // Keep the display so the activity survives the surface being recreated
        compositor.setOutput(null);
    }

    void release() {
        if (retained == this) retained = null;
        handler.removeCallbacksAndMessages(null);
        touch.cancel();
        tracker.stop();
        shell.release();
        if (virtualDisplay != null) {
            // Android moves the display's activities back to the phone screen
            virtualDisplay.release();
            virtualDisplay = null;
        }
        compositor.release();
    }

    void setVisibleArea(@NonNull Rect visibleArea) {
        if (visibleArea.isEmpty()) return;
        // Android Auto also reports the side-card crop of the surface, right after the main
        // pane's area; sizing the display to it would squeeze the app into the card column
        if (isSideCard(visibleArea)) {
            Log.d(TAG, "Ignoring side-card area " + visibleArea.toShortString());
            return;
        }
        Log.i(TAG, "Visible area " + visibleArea.toShortString());
        pendingArea.set(visibleArea);
        scheduleArea();
    }

    void setStableArea(@NonNull Rect stableArea) {
        if (stableArea.isEmpty() || isSideCard(stableArea)) return;
        stableTop = stableArea.top;
        scheduleArea();
    }

    private boolean isSideCard(Rect rect) {
        return surfaceWidth > 0 && rect.left > surfaceWidth / 3;
    }

    private void scheduleArea() {
        // Layout animations report passing areas; each resize can make the activity relayout
        handler.removeCallbacks(applyArea);
        handler.postDelayed(applyArea, AREA_SETTLE_MS);
    }

    private void applyVisibleArea() {
        if (pendingArea.isEmpty()) return;
        Rect next = new Rect(pendingArea);
        // The action strip auto-hides and the visible area grows and shrinks with it. Pin the top
        // so the display doesn't resize each time: below the strip, or under it at full height
        next.top = fullHeight && surfaceHeight > 0
            ? Math.min(next.top, surfaceHeight - next.bottom)
            : Math.max(next.top, stableTop);
        if (next.equals(area)) return;
        touch.cancel();
        area.set(next);
        compositor.setArea(area);
        if (surfaceWidth > 0) updateDisplay();
    }

    void tap(float x, float y) {
        if (tracker.top() == null) {
            // An activity being recreated after a resize has no resumed instance for a moment
            if (!tracker.hasActivities()) launch();
            return;
        }
        if (!area.contains((int) x, (int) y)) return;
        touch.tap(x - area.left, y - area.top);
    }

    void scroll(float distanceX, float distanceY) {
        touch.scroll(distanceX, distanceY, displayWidth / 2f, displayHeight / 2f);
    }

    void fling(float velocityX, float velocityY) {
        touch.fling(velocityX, velocityY, displayWidth / 2f, displayHeight / 2f);
    }

    @SuppressWarnings("deprecation") // onBackPressed still routes through OnBackPressedDispatcher
    void back() {
        Activity top = tracker.top();
        if (top != null) top.onBackPressed();
        else if (!tracker.hasActivities()) launch();
    }

    private boolean fitsSurface(Rect rect) {
        return rect.right <= surfaceWidth && rect.bottom <= surfaceHeight;
    }

    private void updateDisplay() {
        int width = area.width();
        int height = area.height();
        if (virtualDisplay == null) {
            Surface target = compositor.configure(width, height);
            DisplayManager dm = context.getSystemService(DisplayManager.class);
            virtualDisplay = dm.createVirtualDisplay(
                "CarBridge", width, height, dpi, target,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            tracker.start(virtualDisplay.getDisplay().getDisplayId());
        } else if (width != displayWidth || height != displayHeight) {
            Surface target = compositor.configure(width, height);
            virtualDisplay.resize(width, height, dpi);
            virtualDisplay.setSurface(target);
        } else {
            return;
        }
        displayWidth = width;
        displayHeight = height;
        Log.i(TAG, "Virtual display " + width + "x" + height + " @" + dpi + "dpi");
    }

    private void launch() {
        if (virtualDisplay == null || launching) return;
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        ComponentName component = intent == null ? null : intent.getComponent();
        if (component == null) {
            toast("This app has no launcher screen to show");
            return;
        }
        int displayId = virtualDisplay.getDisplay().getDisplayId();
        launching = true;

        // Allowed once any of the app's activities is on the display, e.g. after a Back to root
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId);
        try {
            context.startActivity(intent, options.toBundle());
            checkLaunched();
            return;
        } catch (SecurityException e) {
            Log.i(TAG, "First activity on the display needs the shell; asking Shizuku");
        }

        shell.start(component, displayId, new ShizukuShell.Listener() {
            @Override
            public void onStarted() {
                checkLaunched();
            }

            @Override
            public void onProblem(@NonNull String message) {
                launching = false;
                toast(message);
            }
        });
    }

    private void checkLaunched() {
        handler.postDelayed(() -> {
            launching = false;
            if (tracker.hasActivities()) return;
            Log.w(TAG, "No activity on the virtual display after launch");
            toast("The app didn't open here. Tap to try again");
        }, LAUNCH_CHECK_MS);
    }

    private void toast(String text) {
        CarToast.makeText(context, text, CarToast.LENGTH_LONG).show();
    }
}
