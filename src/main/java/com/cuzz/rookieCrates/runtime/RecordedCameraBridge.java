package com.cuzz.rookieCrates.runtime;

import org.bukkit.entity.Player;

import java.util.Objects;

/** Optional runtime boundary for a third-party recorded camera provider. */
public interface RecordedCameraBridge extends AutoCloseable {
    Validation validate(Player player, String routeName);

    void start(SceneSession session, String routeName);

    void stop(SceneSession session);

    @Override
    default void close() {
    }

    record Validation(boolean valid, String canonicalRoute, String message) {
        public Validation {
            message = Objects.requireNonNull(message, "message");
            if (valid && (canonicalRoute == null || canonicalRoute.isBlank())) {
                throw new IllegalArgumentException("A valid camera route needs a canonical name");
            }
        }

        public static Validation accepted(String canonicalRoute) {
            return new Validation(true, Objects.requireNonNull(canonicalRoute, "canonicalRoute"), "ready");
        }

        public static Validation rejected(String message) {
            return new Validation(false, null, Objects.requireNonNull(message, "message"));
        }
    }
}
