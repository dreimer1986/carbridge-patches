package org.carbridge.extension.splitscreen.payload;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.Set;

/** Tracks the app's foreground activity on one display; input goes there. */
final class ActivityTracker implements Application.ActivityLifecycleCallbacks {
    private final Application app;
    private int displayId = Display.INVALID_DISPLAY;
    private boolean registered;
    @Nullable private Activity top;
    private final Set<Activity> onDisplay = new HashSet<>();

    ActivityTracker(@NonNull Application app) {
        this.app = app;
    }

    void start(int displayId) {
        this.displayId = displayId;
        if (!registered) app.registerActivityLifecycleCallbacks(this);
        registered = true;
    }

    void stop() {
        if (registered) app.unregisterActivityLifecycleCallbacks(this);
        registered = false;
        top = null;
        onDisplay.clear();
    }

    /** True while any activity lives on the display, including one being recreated. */
    boolean hasActivities() {
        onDisplay.removeIf(a -> a.isFinishing() || !onDisplay(a));
        return !onDisplay.isEmpty();
    }

    @Nullable
    Activity top() {
        // The user can move the task back to the phone; input must not follow it there
        if (top != null && (top.isFinishing() || !onDisplay(top))) top = null;
        return top;
    }

    @SuppressWarnings("deprecation") // Activity.getDisplay() needs API 30
    private boolean onDisplay(Activity activity) {
        Display display = activity.getWindowManager().getDefaultDisplay();
        return display != null && display.getDisplayId() == displayId;
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (onDisplay(activity)) top = activity;
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        onDisplay.remove(activity);
        if (activity == top) top = null;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {
        if (onDisplay(activity)) onDisplay.add(activity);
    }

    @Override public void onActivityStarted(@NonNull Activity activity) {}
    @Override public void onActivityPaused(@NonNull Activity activity) {}
    @Override public void onActivityStopped(@NonNull Activity activity) {}
    @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) {}
}
