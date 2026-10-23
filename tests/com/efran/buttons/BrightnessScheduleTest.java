package com.efran.buttons;

public final class BrightnessScheduleTest {
    private static int checks;
    private static void expect(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        BrightnessSchedule schedule = BrightnessSchedule.parse(BrightnessSchedule.DEFAULT);
        expect(schedule != null, "Default schedule parses");
        expect(BrightnessSchedule.DEFAULT.equals(schedule.encode()), "Encoding round-trips");
        expect(schedule.levelAt(12 * 60) == 100, "Daytime is 100%");
        expect(schedule.levelAt(19 * 60) == 50, "Evening is 50%");
        expect(schedule.periodAt(18 * 60) == 3 && schedule.periodAt(18 * 60 - 1) == 2, "Evening starts exactly at its start minute");
        expect(schedule.periodAt(3 * 60) == 3, "Evening wraps past midnight");
        expect(schedule.periodAt(5 * 60 + 30) == 0, "Morning ends the evening");
        expect(schedule.periodAt(0) == 3 && schedule.periodAt(24 * 60 - 1) == 3, "Midnight edges stay in the evening");

        BrightnessSchedule late = schedule.withStart(3, 19 * 60 + 15);
        expect(late.periodAt(19 * 60) == 2 && late.periodAt(19 * 60 + 15) == 3, "Changing a start moves the boundary");
        expect(schedule.periodAt(19 * 60) == 3, "Edits return a new schedule");
        expect(schedule.withLevel(3, 30).levelAt(19 * 60) == 30, "Changing a level applies to that period");
        expect(schedule.withLevel(3, 30).levelAt(12 * 60) == 100, "Other periods keep their levels");
        expect(schedule.withLevel(3, 0) == null, "Levels below the minimum are refused");

        // Starts out of name order still follow the clock.
        BrightnessSchedule swapped = BrightnessSchedule.parse("600:100,300:40,900:100,1080:50");
        expect(swapped.periodAt(400) == 1 && swapped.periodAt(700) == 0, "Periods follow their times, not their names");
        BrightnessSchedule shared = BrightnessSchedule.parse("330:100,330:60,900:100,1080:50");
        expect(shared.periodAt(400) == 1, "Equal starts resolve to the later period");

        for (String rejected : new String[]{null, "", "330:100", "330:100,660:100,900:100,1440:50",
                "330:100,660:100,900:100,-1:50", "330:100,660:100,900:100,1080:4", "330:100,660:100,900:100,1080:101",
                "330,660:100,900:100,1080:50", "a:100,660:100,900:100,1080:50", "330:100,660:100,900:100,1080:50,",
                "330:100:100,660:100:100,900:100:100,1080:25:50"})
            expect(BrightnessSchedule.parse(rejected) == null, "Invalid schedule rejected: " + rejected);
        expect(BrightnessSchedule.DEFAULT.equals(BrightnessSchedule.parseOrDefault("330:100:100,660:100:100,900:100:100,1080:25:50").encode()),
                "A 0.9 schedule with headlight levels falls back to the default");
        expect("05:30".equals(BrightnessSchedule.time(330)) && "00:00".equals(BrightnessSchedule.time(0)), "Times format as HH:MM");
        System.out.println("PASS: " + checks + " brightness schedule checks");
    }
}
