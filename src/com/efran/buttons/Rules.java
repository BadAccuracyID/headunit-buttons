package com.efran.buttons;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Pure policy: no firmware classes or Android dependencies. */
public final class Rules {
    public static final long LEASE_MS = 2500;
    public static final String CAMERA_HOME = "camera-home";
    public static final String CARPLAY_SELECTED = "carplay-selected";
    public static final String CAMERA_PACKAGE = "com.ivicar.avm";
    public static final int[] OUTPUTS = {2, 3, 6, 9, 16, 48, 55, 85};
    public static final class Snapshot {
        final boolean enabled;
        final long receivedAt;
        final Map<Integer, String> actions;
        public Snapshot(boolean enabled, long receivedAt, Map<Integer, String> actions) {
            this.enabled = enabled;
            this.receivedAt = receivedAt;
            this.actions = Collections.unmodifiableMap(new HashMap<>(actions));
        }
        public String action(int code, long now, boolean protectedMode) {
            if (!enabled || protectedMode || now < receivedAt || now - receivedAt > LEASE_MS) return null;
            return actions.get(code & 255);
        }
    }
    public static String label(int code) {
        switch (code & 255) {
            case 2: return "Next";
            case 3: return "Previous";
            case 6: return "Play / pause";
            case 9: return "Home";
            case 16: return "Mode";
            case 48: return "Voice";
            case 55: return "Navigation button";
            case 85: return "Back";
            default: return "Button " + (code & 255);
        }
    }
    public static boolean validAction(String action) {
        if ("block".equals(action) || CARPLAY_SELECTED.equals(action)) return true;
        if (action == null) return false;
        if (action.startsWith("app:")) return action.substring(4).matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
        if (action.startsWith("key:")) {
            try {
                int code = Integer.parseInt(action.substring(4));
                for (int allowed : OUTPUTS) if (code == allowed) return true;
            } catch (NumberFormatException ignored) { }
        }
        return false;
    }
    public static String actionLabel(String action) {
        if (CAMERA_HOME.equals(action)) return "360 camera / Home";
        if (CARPLAY_SELECTED.equals(action)) return "Open selected CarPlay app";
        if ("block".equals(action)) return "Do nothing";
        if (action.startsWith("key:")) return label(Integer.parseInt(action.substring(4)));
        return "Open " + action.substring(4);
    }
    public static boolean validVoiceAction(String action) {
        return CAMERA_HOME.equals(action) || "block".equals(action)
                || (action != null && action.startsWith("app:") && validAction(action));
    }
    public static boolean voiceGoesHome(String action, String foregroundPackage) {
        return CAMERA_HOME.equals(action) && CAMERA_PACKAGE.equals(foregroundPackage);
    }
}
