package com.efran.buttons;

/** Correlates the two observed Voice inputs without delaying the first action. */
public final class VoiceSources {
    public static final long FRESH_MS = 1500, DELIVERY_MS = 800, DUPLICATE_MS = 250;
    private long canAt = -1, canUpAt = -1, rawAcceptedAt = -1;
    private boolean canDown, canOwned;
    private static boolean recent(long at, long now, long limit) {
        return at >= 0 && now >= at && now - at <= limit;
    }
    public void resetCan() { canAt = canUpAt = -1; canDown = canOwned = false; }
    public boolean canSample(int state, long at, long now) {
        if ((state != 0 && state != 1) || !recent(at, now, DELIVERY_MS)) { resetCan(); return false; }
        boolean down = state == 1;
        if (canAt < 0 || at < canAt) {
            resetCan(); canAt = at; canDown = down;
            return false; // Require an observed idle before a rising edge.
        }
        // CAN reports can be sparse. Keep the physical baseline through a gap,
        // but never keep suppressing MCU input on an old accepted CAN press.
        if (!recent(canAt, at, FRESH_MS)) { canOwned = false; canUpAt = -1; }
        canAt = at;
        if (down == canDown) return false;
        canDown = down;
        if (!down) { canUpAt = at; return false; }
        canOwned = recent(rawAcceptedAt, at, DUPLICATE_MS);
        return !canOwned;
    }
    public void canAccepted() { canOwned = true; }
    public void rawAccepted(long now) {
        rawAcceptedAt = now;
        if (canDown && recent(canAt, now, FRESH_MS)) canOwned = true;
    }
    public boolean rawDuplicate(long now) {
        return canOwned && recent(canAt, now, FRESH_MS)
                && (canDown || recent(canUpAt, now, DUPLICATE_MS));
    }
}
