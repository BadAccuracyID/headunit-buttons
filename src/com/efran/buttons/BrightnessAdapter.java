package com.efran.buttons;

import android.app.Service;
import android.os.Handler;
import android.util.Log;
import java.lang.reflect.Field;
import java.util.Calendar;
import de.robv.android.xposed.XposedHelpers;

/**
 * Applies the brightness schedule inside EventCenter, the way the GT6 Settings slider does:
 * the level goes to the MCU backlight (SendBLVal) and to Android's brightness and the SystemUI
 * slider (sendBrightnessToSystemUI). It acts only when the period or headlight state changes, so a
 * manual slider change lasts until the next change.
 */
final class BrightnessAdapter {
    static final String DAY = "COM.SZCHOICEWAY_BRIGHTNESS_SETTINGS";
    static final String NIGHT = "KSW_SETTINGS_DISPLAY_BRIGHTNESS_NIGHT";
    private static final String NIGHT_SHOW = "KSW_SETTINGS_DISPLAY_BRIGHTNESS_NIGHT_SHOW";
    private static final String BACKLIGHT_CONTROL = "KSW_BACKLIGHT_CONTROL_INDEX";
    private final Field headlights;
    private final Field backlightOff;
    private String applied;
    private volatile String status = "Schedule off";

    private BrightnessAdapter(Field headlights, Field backlightOff) {
        this.headlights = headlights;
        this.backlightOff = backlightOff;
    }

    /** Checks every firmware member the adapter uses; throws if this EventCenter differs. */
    static BrightnessAdapter install(Class<?> service) throws Exception {
        service.getDeclaredMethod("putSettingInt", String.class, int.class);
        service.getDeclaredMethod("getSettingInt", String.class, int.class);
        service.getDeclaredMethod("SendBLVal", byte.class, byte.class);
        service.getDeclaredMethod("sendBrightnessToSystemUI");
        service.getDeclaredField("mSysProviderOpt");
        return new BrightnessAdapter(field(service, "mSmallLightOn"), field(service, "mBackLightOff"));
    }

    String status() { return status; }

    /** Called from the configuration poll. A failed poll does not call it, so nothing re-applies. */
    synchronized void tick(Service service, boolean enabled, BrightnessSchedule schedule, boolean protectedMode) {
        if (service == null) return;
        if (!enabled || schedule == null) {
            applied = null;
            status = enabled ? "Schedule invalid; brightness unchanged" : "Schedule off";
            return;
        }
        try {
            boolean lights = headlights.getBoolean(service);
            Calendar now = Calendar.getInstance();
            int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            int period = schedule.periodAt(minute);
            String state = schedule.encode() + "|" + period + "|" + lights;
            if (state.equals(applied)) return;
            if (XposedHelpers.getObjectField(service, "mSysProviderOpt") == null) {
                status = "Waiting for EventCenter settings";
                return;
            }
            // Reverse and screen-off states own the backlight; apply once they end.
            if (protectedMode || backlightOff.getBoolean(service)) {
                status = "Waiting: camera or screen off";
                return;
            }
            int level = schedule.level(minute, lights);
            applied = state;
            String reason = BrightnessSchedule.NAMES[period] + ", headlights " + (lights ? "on" : "off");
            new Handler(service.getMainLooper()).post(() -> apply(service, level, lights, reason));
        } catch (Throwable error) {
            status = "Brightness failed: " + error.getClass().getSimpleName();
            Log.w("HeadunitButtons", "Brightness schedule failed; brightness unchanged", error);
        }
    }

    private void apply(Service service, int level, boolean lights, String reason) {
        try {
            Object records = XposedHelpers.getObjectField(service, "mSysProviderOpt");
            boolean nightShow = (Boolean) XposedHelpers.callMethod(records, "getRecordBoolean", NIGHT_SHOW, false);
            boolean backlightControl = (Boolean) XposedHelpers.callMethod(records, "getRecordBoolean", BACKLIGHT_CONTROL, false);
            // Write the record EventCenter reads for the current headlight state, so its own
            // headlight handling and the Settings app show the same level.
            String key = nightShow && !backlightControl && lights ? NIGHT : DAY;
            XposedHelpers.callMethod(service, "putSettingInt", key, level);
            int day = (Integer) XposedHelpers.callMethod(service, "getSettingInt", DAY, 50);
            int night = (Integer) XposedHelpers.callMethod(records, "getRecordInteger", NIGHT, 50);
            XposedHelpers.callMethod(service, "SendBLVal", (byte) day, (byte) night);
            XposedHelpers.callMethod(service, "sendBrightnessToSystemUI");
            status = level + "%: " + reason + " (set " + BrightnessSchedule.time(minuteNow()) + ")";
            Log.i("HeadunitButtons", "BRIGHTNESS " + level + " " + reason + " record=" + key);
        } catch (Throwable error) {
            // No retry loop: the next period or headlight change tries again.
            status = "Brightness failed: " + error.getClass().getSimpleName();
            Log.w("HeadunitButtons", "Brightness apply failed; brightness unchanged", error);
        }
    }

    private static int minuteNow() {
        Calendar now = Calendar.getInstance();
        return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        if (field.getType() != boolean.class) throw new IllegalStateException("Unexpected field " + name);
        field.setAccessible(true);
        return field;
    }
}
