package com.efran.buttons;

/** Tracks a consumed Voice-on press so its release cannot trigger a second action. */
public final class VoiceGesture {
    public static final int PASS = 0;
    public static final int CONSUME = 1;
    public static final int TRY_ACTION = 2;
    private long acceptedAt = -1;
    public int decide(int state, long now, boolean mapped, boolean protectedMode) {
        if (protectedMode || (acceptedAt >= 0 && (now < acceptedAt || now - acceptedAt > 10000))) reset();
        if (protectedMode) return PASS;
        if (state < 0 || state > 2) { reset(); return PASS; }
        if (acceptedAt >= 0) {
            if (state == 0) reset();
            return CONSUME;
        }
        return state == 1 && mapped ? TRY_ACTION : PASS;
    }
    public void accepted(long now) { acceptedAt = now; }
    public void reset() { acceptedAt = -1; }
}
