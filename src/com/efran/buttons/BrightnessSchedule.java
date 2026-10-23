package com.efran.buttons;

/**
 * Screen brightness by time of day and headlight state. Each period starts at a minute of the
 * day and lasts until the next start; the latest period wraps past midnight. Levels are the
 * GT6 brightness slider's 0-100 scale, one for headlights off and one for headlights on.
 */
public final class BrightnessSchedule {
    public static final String[] NAMES = {"Morning", "Noon", "Afternoon", "Evening"};
    public static final String DEFAULT = "330:100:100,660:100:100,900:100:100,1080:25:50";
    // A level of 0 can turn the backlight off entirely; keep the screen readable.
    public static final int MIN_LEVEL = 5;
    private final int[] starts;
    private final int[] lightsOff;
    private final int[] lightsOn;

    private BrightnessSchedule(int[] starts, int[] lightsOff, int[] lightsOn) {
        this.starts = starts;
        this.lightsOff = lightsOff;
        this.lightsOn = lightsOn;
    }

    /** Returns null for anything but four valid "start:off:on" entries. */
    public static BrightnessSchedule parse(String encoded) {
        if (encoded == null) return null;
        String[] periods = encoded.split(",", -1);
        if (periods.length != NAMES.length) return null;
        int[] starts = new int[NAMES.length];
        int[] off = new int[NAMES.length];
        int[] on = new int[NAMES.length];
        try {
            for (int index = 0; index < periods.length; index++) {
                String[] fields = periods[index].split(":", -1);
                if (fields.length != 3) return null;
                starts[index] = Integer.parseInt(fields[0]);
                off[index] = Integer.parseInt(fields[1]);
                on[index] = Integer.parseInt(fields[2]);
                if (starts[index] < 0 || starts[index] >= 24 * 60 || !validLevel(off[index]) || !validLevel(on[index]))
                    return null;
            }
        } catch (NumberFormatException error) {
            return null;
        }
        return new BrightnessSchedule(starts, off, on);
    }

    public static boolean validLevel(int level) { return level >= MIN_LEVEL && level <= 100; }

    public String encode() {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < NAMES.length; index++) {
            if (index > 0) out.append(',');
            out.append(starts[index]).append(':').append(lightsOff[index]).append(':').append(lightsOn[index]);
        }
        return out.toString();
    }

    /** The period whose start is the latest at or before this minute, wrapping past midnight. */
    public int periodAt(int minuteOfDay) {
        int best = -1;
        int latest = -1;
        for (int index = 0; index < NAMES.length; index++) {
            if (latest < 0 || starts[index] >= starts[latest]) latest = index;
            if (starts[index] <= minuteOfDay && (best < 0 || starts[index] >= starts[best])) best = index;
        }
        return best >= 0 ? best : latest;
    }

    public int level(int minuteOfDay, boolean headlightsOn) {
        int period = periodAt(minuteOfDay);
        return headlightsOn ? lightsOn[period] : lightsOff[period];
    }

    public int start(int period) { return starts[period]; }
    public int levelFor(int period, boolean headlightsOn) { return headlightsOn ? lightsOn[period] : lightsOff[period]; }

    public BrightnessSchedule withStart(int period, int minuteOfDay) {
        int[] changed = starts.clone();
        changed[period] = minuteOfDay;
        return parse(new BrightnessSchedule(changed, lightsOff, lightsOn).encode());
    }

    public BrightnessSchedule withLevel(int period, boolean headlightsOn, int level) {
        int[] off = lightsOff.clone();
        int[] on = lightsOn.clone();
        (headlightsOn ? on : off)[period] = level;
        return parse(new BrightnessSchedule(starts, off, on).encode());
    }

    public static String time(int minuteOfDay) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }
}
