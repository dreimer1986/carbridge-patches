package org.carbridge.extension.splitscreen.payload;

import androidx.annotation.NonNull;
import androidx.car.app.AppManager;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.ScreenManager;
import androidx.car.app.model.Action;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Template;

/**
 * Asks for a passenger's confirmation before the app opens. Android Auto allows navigation apps
 * while driving, so it doesn't stop the driver from using the full app the way it does for games.
 */
final class PassengerScreen extends Screen {
    PassengerScreen(@NonNull CarContext carContext) {
        super(carContext);
        // After process death the host keeps calling the dead process's callback, then shows an ANR
        carContext.getCarService(AppManager.class).setSurfaceCallback(null);
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        CarContext context = getCarContext();
        String name = context.getApplicationInfo().loadLabel(context.getPackageManager()).toString();
        Action passenger = new Action.Builder()
            .setTitle("I'm a passenger")
            .setFlags(Action.FLAG_PRIMARY)
            .setOnClickListener(this::open)
            .build();
        return new MessageTemplate.Builder(
                "This shows the full " + name + " app on the car screen. It's for passengers, "
                    + "or for when you're parked. Don't use it while you drive.")
            .setTitle("For passengers only")
            .setHeaderAction(Action.APP_ICON)
            .addAction(passenger)
            .build();
    }

    private void open() {
        ScreenManager screens = getScreenManager();
        screens.push(new AppScreen(getCarContext()));
        // Back on the app must not return here
        screens.remove(this);
    }
}
