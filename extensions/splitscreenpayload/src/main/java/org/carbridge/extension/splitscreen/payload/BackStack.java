package org.carbridge.extension.splitscreen.payload;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;

/**
 * Tells whether Back would stay inside the app. The app's own AndroidX classes may be obfuscated,
 * so its back dispatcher is read by shape, not by name: a collection of callbacks whose base class
 * holds one boolean, the enabled flag. Anything unexpected counts as "can go back".
 */
final class BackStack {
    private static final String DISPATCHER = "getOnBackPressedDispatcher";

    private BackStack() {}

    /** Why Back may stay inside the app, or null on the app's root screen. */
    @Nullable
    static String whyBackStays(@NonNull Activity activity) {
        if (!activity.isTaskRoot()) return "not the task root";
        try {
            Method getter = activity.getClass().getMethod(DISPATCHER);
            String override = backOverride(activity.getClass(), getter.getDeclaringClass());
            if (override != null) return override + " handles Back itself";
            Object dispatcher = getter.invoke(activity);
            if (dispatcher == null) return "no back dispatcher";
            Collection<?> callbacks = callbacks(dispatcher);
            if (callbacks == null) return "unknown back dispatcher";
            for (Object callback : callbacks.toArray()) {
                if (callback == null || isEnabled(callback)) {
                    return "enabled callback " + (callback == null ? null : callback.getClass().getName());
                }
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "not an AndroidX activity (" + e + ")";
        }
    }

    /** The app's own activity class that overrides onBackPressed, below AndroidX's activity. */
    private static String backOverride(Class<?> leaf, Class<?> componentActivity) {
        for (Class<?> c = leaf; c != null && c != componentActivity; c = c.getSuperclass()) {
            try {
                c.getDeclaredMethod("onBackPressed");
                // AndroidX's own subclasses only forward to the dispatcher
                if (!c.getName().startsWith("androidx.")) return c.getName();
            } catch (NoSuchMethodException ignored) {
                // Not overridden here
            }
        }
        return null;
    }

    private static Collection<?> callbacks(Object dispatcher) throws IllegalAccessException {
        Collection<?> found = null;
        for (Field field : dispatcher.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (!Collection.class.isAssignableFrom(field.getType())) continue;
            if (found != null) return null;
            field.setAccessible(true);
            found = (Collection<?>) field.get(dispatcher);
        }
        return found;
    }

    private static boolean isEnabled(Object callback) throws IllegalAccessException {
        // Callbacks are anonymous subclasses; the base class sits just below Object
        Class<?> base = callback.getClass();
        while (base.getSuperclass() != null && base.getSuperclass() != Object.class) {
            base = base.getSuperclass();
        }
        Field enabled = null;
        for (Field field : base.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != boolean.class) continue;
            if (enabled != null) return true;
            enabled = field;
        }
        if (enabled == null) return true;
        enabled.setAccessible(true);
        return enabled.getBoolean(callback);
    }
}
