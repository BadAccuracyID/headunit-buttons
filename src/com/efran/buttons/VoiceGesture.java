package com.efran.buttons;

/** Tracks a consumed Voice-on press so its release cannot trigger a second action. */
public final class VoiceGesture {
    public static final int PASS = 0;
    public static final int CONSUME = 1;
    public static final int TRY_ACTION = 2;
    public static final long DUPLICATE_DOWN_MS = 250;
    private long acceptedAt = -1;
    public int decide(int state, long now, boolean mapped, boolean protectedMode) {
        if (acceptedAt >= 0 && now < acceptedAt) reset();
        if (state < 0 || state > 2) { reset(); return PASS; }
        if (acceptedAt >= 0) {
            // We consumed the down: never hand its hold/release to the stock
            // handler, which uses a shared saved button rather than this ID.
            if (state == 0) { reset(); return CONSUME; }
            if (state == 2 || now - acceptedAt <= DUPLICATE_DOWN_MS) return CONSUME;
            // State 2 denotes a hold. A later state 1 begins a new gesture even
            // if the MCU omitted the preceding release; do not latch for 10 s.
            reset();
        }
        if (protectedMode) return PASS;
        return state == 1 && mapped ? TRY_ACTION : PASS;
    }
    public void accepted(long now) { acceptedAt = now; }
    public void reset() { acceptedAt = -1; }
}
