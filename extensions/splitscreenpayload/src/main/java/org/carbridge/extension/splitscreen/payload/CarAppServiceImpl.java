package org.carbridge.extension.splitscreen.payload;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

/**
 * The real car app service, created by the extension's SplitScreenCarAppService. A
 * navigation-category car app, so Android Auto gives it the main split-screen pane. Its map
 * surface shows the app's own launcher activity, running on a virtual display.
 */
public final class CarAppServiceImpl extends CarAppService {
    // Android Auto's signing digests, from the Car App Library's hosts_allowlist_sample
    private static final String GEARHEAD = "com.google.android.projection.gearhead";
    private static final String[] GEARHEAD_DIGESTS = {
        "fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf",
        "70811a3eacfd2e83e18da9bfede52df16ce91f2e69a44d21f18ab66991130771",
        "1975b2f17177bc89a5dff31f9e64a6cae281a53dc1d1d59b1d147fe1c82afa00",
    };

    @NonNull
    @Override
    public HostValidator createHostValidator() {
        // Only Android Auto may bind: the session forwards taps into the app
        HostValidator.Builder builder = new HostValidator.Builder(getApplicationContext());
        for (String digest : GEARHEAD_DIGESTS) builder.addAllowedHost(GEARHEAD, digest);
        return builder.build();
    }

    @NonNull
    @Override
    public Session onCreateSession() {
        return new Session() {
            @NonNull
            @Override
            public Screen onCreateScreen(@NonNull Intent intent) {
                CarContext context = getCarContext();
                // A session restarted to recover the car surface was already confirmed
                return AppDisplay.isRetained()
                    ? new AppScreen(context)
                    : new PassengerScreen(context);
            }
        };
    }
}
