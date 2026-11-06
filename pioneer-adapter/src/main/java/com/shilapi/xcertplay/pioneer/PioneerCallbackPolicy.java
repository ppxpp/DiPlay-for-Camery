package com.shilapi.xcertplay.pioneer;

/** Only an enabled adaptation may repair callbacks; authorized cleanup remains possible afterward. */
public final class PioneerCallbackPolicy {
    private PioneerCallbackPolicy() {}
    public static boolean callbackAllowed(String mode, boolean enabled, boolean cleanupAuthorized) {
        return (mode.equals("--audit") || mode.equals("--repair") || mode.equals("--clear-idle")) &&
            (enabled || (cleanupAuthorized && !mode.equals("--repair")));
    }
}
