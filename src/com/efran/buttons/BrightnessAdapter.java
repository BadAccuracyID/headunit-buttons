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
 * slider (sendBrightnessToSystemUI). It acts only when a period starts or the schedule changes, so
 * a manual slider change lasts until the next period.
 */
final class BrightnessAdapter {
    static final String DAY = "COM.SZCHOICEWAY_BRIGHTNESS_SETTINGS";
    static final String NIGHT = "KSW_SETTINGS_DISPLAY_BRIGHTNESS_NIGHT";
    private final Field backlightOff;
    private String applied;
    private volatile String status = "Schedule off";

    private BrightnessAdapter(Field backlightOff) {
        this.backlightOff = backlightOff;
    }

    /** Checks every firmware member the adapter uses; throws if this EventCenter differs. */
    static BrightnessAdapter install(Class<?> service) throws Exception {
        service.getDeclaredMethod("putSettingInt", String.class, int.class);
        service.getDeclaredMethod("getSettingInt", String.class, int.class);
        service.getDeclaredMethod("SendBLVal", byte.class, byte.class);
        service.getDeclaredMethod("sendBrightnessToSystemUI");
        service.getDeclaredField("mSysProviderOpt");
        return new BrightnessAdapter(field(service, "mBackLightOff"));
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
            int period = schedule.periodAt(minuteNow());
            String state = schedule.encode() + "|" + period;
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
            int level = schedule.level(period);
            applied = state;
            String reason = BrightnessSchedule.NAMES[period];
            new Handler(service.getMainLooper()).post(() -> apply(service, level, reason));
        } catch (Throwable error) {
            status = "Brightness failed: " + error.getClass().getSimpleName();
            Log.w("HeadunitButtons", "Brightness schedule failed; brightness unchanged", error);
        }
    }

    private void apply(Service service, int level, String reason) {
        try {
            Object records = XposedHelpers.getObjectField(service, "mSysProviderOpt");
            // The day record is the GT6 slider's value. The night record is left as it is: on the
            // W205 the MCU ignores it and dims for the headlights by itself.
            XposedHelpers.callMethod(service, "putSettingInt", DAY, level);
            int night = (Integer) XposedHelpers.callMethod(records, "getRecordInteger", NIGHT, 50);
            XposedHelpers.callMethod(service, "SendBLVal", (byte) level, (byte) night);
            XposedHelpers.callMethod(service, "sendBrightnessToSystemUI");
            status = level + "%: " + reason + " (set " + BrightnessSchedule.time(minuteNow()) + ")";
            Log.i("HeadunitButtons", "BRIGHTNESS " + level + " " + reason);
        } catch (Throwable error) {
            // No retry loop: the next period or schedule change tries again.
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
