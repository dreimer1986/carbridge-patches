package org.carbridge.extension.splitscreen.payload;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.annotation.Nullable;

import java.util.function.Supplier;

/**
 * Turns Android Auto's surface gestures (tap, scroll deltas, fling velocity) into touch events
 * dispatched straight to the activity. Same process, so no input-injection permission is needed.
 */
final class TouchForwarder {
    private static final long TAP_MS = 50;
    // Android Auto sends no "finger up" for scrolls, so a pause ends the drag
    private static final long DRAG_END_MS = 150;
    private static final long FRAME_MS = 16;
    private static final int FLING_FRAMES = 3;

    private final Supplier<Activity> target;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable endDrag = this::endDrag;
    @Nullable private Activity dragTarget;
    private long downTime;
    private long lastTime;
    private float x;
    private float y;

    TouchForwarder(Supplier<Activity> target) {
        this.target = target;
    }

    void tap(float tapX, float tapY) {
        endDrag();
        Activity activity = target.get();
        if (activity == null) return;
        long now = SystemClock.uptimeMillis();
        send(activity, now, now, MotionEvent.ACTION_DOWN, tapX, tapY);
        send(activity, now, now + TAP_MS, MotionEvent.ACTION_UP, tapX, tapY);
    }

    void scroll(float distanceX, float distanceY, float anchorX, float anchorY) {
        if (!ensureDrag(anchorX, anchorY)) return;
        // Distances follow GestureDetector: previous minus current position
        x -= distanceX;
        y -= distanceY;
        lastTime = Math.max(SystemClock.uptimeMillis(), lastTime + 1);
        send(dragTarget, downTime, lastTime, MotionEvent.ACTION_MOVE, x, y);
        handler.removeCallbacks(endDrag);
        handler.postDelayed(endDrag, DRAG_END_MS);
    }

    void fling(float velocityX, float velocityY, float anchorX, float anchorY) {
        if (!ensureDrag(anchorX, anchorY)) return;
        handler.removeCallbacks(endDrag);
        // A few fast moves with synthetic times, so the view's VelocityTracker sees the speed
        float stepX = velocityX * FRAME_MS / 1000f;
        float stepY = velocityY * FRAME_MS / 1000f;
        long time = Math.max(SystemClock.uptimeMillis(), lastTime + 1);
        for (int i = 0; i < FLING_FRAMES; i++) {
            x += stepX;
            y += stepY;
            time += FRAME_MS;
            send(dragTarget, downTime, time, MotionEvent.ACTION_MOVE, x, y);
        }
        send(dragTarget, downTime, time, MotionEvent.ACTION_UP, x, y);
        dragTarget = null;
    }

    void cancel() {
        handler.removeCallbacks(endDrag);
        if (dragTarget != null) {
            send(dragTarget, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, x, y);
            dragTarget = null;
        }
    }

    private boolean ensureDrag(float anchorX, float anchorY) {
        if (dragTarget != null) return true;
        Activity activity = target.get();
        if (activity == null) return false;
        dragTarget = activity;
        x = anchorX;
        y = anchorY;
        downTime = SystemClock.uptimeMillis();
        lastTime = downTime;
        send(activity, downTime, downTime, MotionEvent.ACTION_DOWN, x, y);
        return true;
    }

    private void endDrag() {
        handler.removeCallbacks(endDrag);
        if (dragTarget == null) return;
        lastTime = Math.max(SystemClock.uptimeMillis(), lastTime + 1);
        send(dragTarget, downTime, lastTime, MotionEvent.ACTION_UP, x, y);
        dragTarget = null;
    }

    private static void send(Activity activity, long down, long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            activity.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }
}
