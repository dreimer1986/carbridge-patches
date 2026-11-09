package org.carbridge.extension.splitscreen.payload;

import android.graphics.Rect;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.car.app.AppManager;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;

/** The car-side screen: a navigation template whose map surface is the app's virtual display. */
final class AppScreen extends Screen implements SurfaceCallback {
    static final String TAG = "CarBridgeSplit";

    private final AppDisplay display;

    AppScreen(@NonNull CarContext carContext) {
        super(carContext);
        display = AppDisplay.obtain(carContext, this::closeForNewSurface, this::invalidate);
        carContext.getCarService(AppManager.class).setSurfaceCallback(this);
        getLifecycle().addObserver((LifecycleEventObserver) (owner, event) -> {
            if (event == Lifecycle.Event.ON_DESTROY) display.releaseLater();
        });
    }

    /** Android Auto reopens a finished car app right away, with a fresh surface. */
    private void closeForNewSurface() {
        Log.w(TAG, "Car surface is held by a previous app process; restarting the car app");
        getCarContext().finishCarApp();
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        return new NavigationTemplate.Builder()
            .setActionStrip(new ActionStrip.Builder().addAction(stripAction()).build())
            // Android Auto only sends scroll and fling events in pan mode
            .setMapActionStrip(new ActionStrip.Builder().addAction(Action.PAN).build())
            .build();
    }

    private Action stripAction() {
        if (display.canGoBack()) {
            return new Action.Builder()
                .setTitle("Back")
                .setOnClickListener(display::back)
                .build();
        }
        // The strip needs an action, and Android Auto tints icons into blank tiles: use the name
        CarContext context = getCarContext();
        return new Action.Builder()
            .setTitle(context.getApplicationInfo().loadLabel(context.getPackageManager()))
            .setOnClickListener(() -> {})
            .build();
    }

    @Override
    public void onSurfaceAvailable(@NonNull SurfaceContainer container) {
        display.attach(container);
    }

    @Override
    public void onSurfaceDestroyed(@NonNull SurfaceContainer container) {
        display.detach();
    }

    @Override
    public void onVisibleAreaChanged(@NonNull Rect visibleArea) {
        display.setVisibleArea(visibleArea);
    }

    @Override
    public void onStableAreaChanged(@NonNull Rect stableArea) {
        Log.d(TAG, "Stable area " + stableArea.toShortString());
        display.setStableArea(stableArea);
    }

    @Override
    public void onClick(float x, float y) {
        display.tap(x, y);
    }

    @Override
    public void onScroll(float distanceX, float distanceY) {
        display.scroll(distanceX, distanceY);
    }

    @Override
    public void onFling(float velocityX, float velocityY) {
        display.fling(velocityX, velocityY);
    }

    @Override
    public void onScale(float focusX, float focusY, float scaleFactor) {
        Log.d(TAG, "Pinch zoom is not forwarded yet");
    }
}
