package com.efran.buttons;

/**
 * Screen brightness by time of day. Each period starts at a minute of the day and lasts until the
 * next start; the latest period wraps past midnight. Levels use the GT6 brightness slider's 0-100
 * scale. The headlight state is not used: the tested car (W205) never reports it to EventCenter,
 * and its screen dims by itself, below Android, when the headlights are on.
 */
public final class BrightnessSchedule {
    public static final String[] NAMES = {"Morning", "Noon", "Afternoon", "Evening"};
    public static final String DEFAULT = "330:100,660:100,900:100,1080:50";
    // A level of 0 can turn the backlight off entirely; keep the screen readable.
    public static final int MIN_LEVEL = 5;
    private final int[] starts;
    private final int[] levels;

    private BrightnessSchedule(int[] starts, int[] levels) {
        this.starts = starts;
        this.levels = levels;
    }

    /** Returns null for anything but four valid "start:level" entries. */
    public static BrightnessSchedule parse(String encoded) {
        if (encoded == null) return null;
        String[] periods = encoded.split(",", -1);
        if (periods.length != NAMES.length) return null;
        int[] starts = new int[NAMES.length];
        int[] levels = new int[NAMES.length];
        try {
            for (int index = 0; index < periods.length; index++) {
                String[] fields = periods[index].split(":", -1);
                if (fields.length != 2) return null;
                starts[index] = Integer.parseInt(fields[0]);
                levels[index] = Integer.parseInt(fields[1]);
                if (starts[index] < 0 || starts[index] >= 24 * 60 || !validLevel(levels[index])) return null;
            }
        } catch (NumberFormatException error) {
            return null;
        }
        return new BrightnessSchedule(starts, levels);
    }

    /** The saved schedule, or the default when nothing valid is saved. */
    public static BrightnessSchedule parseOrDefault(String encoded) {
        BrightnessSchedule saved = parse(encoded);
        return saved != null ? saved : parse(DEFAULT);
    }

    public static boolean validLevel(int level) { return level >= MIN_LEVEL && level <= 100; }

    public String encode() {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < NAMES.length; index++) {
            if (index > 0) out.append(',');
            out.append(starts[index]).append(':').append(levels[index]);
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

    public int levelAt(int minuteOfDay) { return levels[periodAt(minuteOfDay)]; }
    public int start(int period) { return starts[period]; }
    public int level(int period) { return levels[period]; }

    public BrightnessSchedule withStart(int period, int minuteOfDay) {
        int[] changed = starts.clone();
        changed[period] = minuteOfDay;
        return parse(new BrightnessSchedule(changed, levels).encode());
    }

    public BrightnessSchedule withLevel(int period, int level) {
        int[] changed = levels.clone();
        changed[period] = level;
        return parse(new BrightnessSchedule(starts, changed).encode());
    }

    public static String time(int minuteOfDay) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }
}
