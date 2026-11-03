package com.efran.buttons;

/** Host checks for the observed inputs; no claim that either stream reports every press. */
public final class VoiceSourcesTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void idle(VoiceSources sources, long at) {
        check(!sources.canSample(0, at, at), "Initial CAN idle cannot request an action");
    }

    private static void canOnly() {
        VoiceSources sources = new VoiceSources();
        idle(sources, 1000);
        int actions = 0;
        if (sources.canSample(1, 1100, 1100)) {
            actions++;
            sources.canAccepted();
        }
        check(actions == 1, "CAN-only rising edge requests one action");
        check(!sources.canSample(1, 1200, 1200), "Repeated CAN high cannot repeat an action");
        check(!sources.canSample(0, 1568, 1568), "Observed 468 ms CAN release cannot act");
        check(sources.rawDuplicate(1818), "Late raw input is correlated through the inclusive release window");
        check(!sources.rawDuplicate(1819), "A later independent raw press is allowed after the release window");
        check(!sources.canSample(0, 1900, 1900), "Further idle does not create a new action");
        if (sources.canSample(1, 2100, 2100)) {
            actions++;
            sources.canAccepted();
        }
        check(actions == 2, "A second CAN-only press is independent");
        check(!sources.canSample(0, 2100, 2100), "High and low delivered in one read still represent one press");
        check(sources.rawDuplicate(2200), "Same-read release retains the successful action correlation");
    }

    private static void rawOnly() {
        VoiceSources sources = new VoiceSources();
        VoiceGesture gesture = new VoiceGesture();
        int actions = 0;
        for (long at : new long[]{1000, 4000, 7000}) {
            check(!sources.rawDuplicate(at), "MCU-only press does not depend on CAN availability");
            check(gesture.decide(1, at, true, false) == VoiceGesture.TRY_ACTION, "MCU-only down requests an action");
            actions++;
            sources.rawAccepted(at);
            gesture.accepted(at);
            check(gesture.decide(2, at + 20, false, true) == VoiceGesture.CONSUME, "Accepted MCU hold remains owned");
            check(gesture.decide(0, at + 36, false, true) == VoiceGesture.CONSUME, "Observed short MCU release remains owned");
        }
        check(actions == 3, "Each MCU-only press requests exactly one action");
    }

    private static void rawThenCan() {
        VoiceSources sources = new VoiceSources();
        VoiceGesture gesture = new VoiceGesture();
        idle(sources, 900);
        check(!sources.rawDuplicate(1000), "First raw down has no CAN duplicate");
        sources.rawAccepted(1000);
        gesture.accepted(1000);
        check(gesture.decide(0, 1036, false, false) == VoiceGesture.CONSUME, "Raw release can precede the corresponding CAN rise");
        check(!sources.canSample(1, 1250, 1250), "CAN rise at the cross-source boundary cannot repeat raw action");
        check(sources.rawDuplicate(1500), "Raw-first correlation owns the held CAN cycle beyond 250 ms");
        check(!sources.canSample(1, 1600, 1600), "Continued CAN high remains the same raw-first cycle");
        check(!sources.canSample(0, 1700, 1700), "CAN release after raw release cannot act");
        check(!sources.rawDuplicate(1951), "Raw-first completed cycle eventually permits another raw press");

        VoiceSources outsideWindow = new VoiceSources();
        idle(outsideWindow, 900);
        outsideWindow.rawAccepted(1000);
        check(outsideWindow.canSample(1, 1251, 1251), "A CAN rise outside the declared cross-source window is independent");
    }

    private static void canThenRaw() {
        VoiceSources sources = new VoiceSources();
        VoiceGesture gesture = new VoiceGesture();
        idle(sources, 1000);
        check(sources.canSample(1, 1100, 1100), "CAN-first rise requests an action");
        sources.canAccepted();
        check(sources.rawDuplicate(1500), "Raw down after 400 ms of held CAN cannot toggle the camera twice");
        // The caller owns a suppressed real MCU down, even though it launches nothing.
        gesture.accepted(1500);
        check(gesture.decide(0, 1536, false, false) == VoiceGesture.CONSUME, "Raw release before CAN release stays consumed");
        check(sources.rawDuplicate(1550), "Raw release does not clear the independent CAN cycle");
        check(!sources.canSample(0, 1568, 1568), "CAN release after raw release cannot repeat action");
        check(sources.rawDuplicate(1600), "Delayed raw input just after CAN release remains correlated");

        VoiceSources reverseRelease = new VoiceSources();
        VoiceGesture reverseGesture = new VoiceGesture();
        idle(reverseRelease, 2000);
        check(reverseRelease.canSample(1, 2100, 2100), "Second CAN-first scenario requests an action");
        reverseRelease.canAccepted();
        check(reverseRelease.rawDuplicate(2200), "Corresponding raw down is suppressed");
        reverseGesture.accepted(2200);
        check(!reverseRelease.canSample(0, 2250, 2250), "CAN may release before raw MCU");
        check(reverseGesture.decide(2, 2300, false, true) == VoiceGesture.CONSUME, "Raw hold remains owned after CAN release");
        check(reverseGesture.decide(0, 2400, false, true) == VoiceGesture.CONSUME, "Raw release remains owned after CAN release");
    }

    private static void initialHeldAndFailures() {
        VoiceSources held = new VoiceSources();
        check(!held.canSample(1, 1000, 1000), "Starting capture with Voice held cannot launch");
        check(!held.canSample(1, 1100, 1100), "Further initial high samples cannot launch");
        check(!held.rawDuplicate(1150), "An unaccepted initial high cannot suppress working raw input");
        check(!held.canSample(0, 1200, 1200), "Initial held sample must first return idle");
        check(held.canSample(1, 1300, 1300), "A press after observed idle can act");
        // Intentionally omit canAccepted(): the requested launch failed.
        check(!held.rawDuplicate(1320), "Failed CAN launch cannot claim raw duplicate ownership");
        held.rawAccepted(1330);
        check(held.rawDuplicate(1400), "Successful raw retry can own the still-held CAN cycle");
        check(!held.canSample(0, 1450, 1450), "Release after successful raw retry cannot act");
        check(held.canSample(1, 1700, 1700), "A later CAN press can retry independently");

        VoiceSources failedRaw = new VoiceSources();
        VoiceGesture failedGesture = new VoiceGesture();
        idle(failedRaw, 2000);
        check(failedGesture.decide(1, 2100, true, false) == VoiceGesture.TRY_ACTION, "Raw launch may be attempted first");
        // Neither accepted method is called for a failed raw launch.
        check(failedGesture.decide(0, 2136, false, false) == VoiceGesture.PASS, "Failed raw launch leaves its release stock");
        check(failedRaw.canSample(1, 2200, 2200), "Failed raw launch cannot suppress a later CAN opportunity");
        check(!failedRaw.rawDuplicate(2250), "A CAN request alone does not imply successful ownership");
    }

    private static void freshnessAndReset() {
        VoiceSources delivery = new VoiceSources();
        idle(delivery, 1000);
        check(delivery.canSample(1, 1500, 2300), "Delivery exactly 800 ms old is accepted");
        delivery.canAccepted();
        check(delivery.rawDuplicate(3000), "CAN freshness boundary is inclusive");
        check(!delivery.rawDuplicate(3001), "Missing CAN traffic cannot suppress raw input forever");
        check(!delivery.canSample(0, 1600, 2401), "Delivery older than 800 ms is rejected");
        check(!delivery.rawDuplicate(2450), "Stale delivery clears CAN ownership");
        check(!delivery.canSample(1, 2500, 2500), "After stale delivery, high must reestablish a baseline");
        check(!delivery.canSample(0, 2600, 2600), "Idle rearms after stale delivery");
        check(delivery.canSample(1, 2700, 2700), "A fresh press after stale recovery works");
        delivery.canAccepted();
        check(!delivery.canSample(0, 2900, 2800), "Future sample timestamps are rejected");
        check(!delivery.rawDuplicate(2800), "Future sample invalidates CAN duplicate ownership");

        VoiceSources gap = new VoiceSources();
        idle(gap, 1000);
        check(gap.canSample(1, 2500, 2500), "Sample gap exactly 1500 ms still permits an edge");
        gap.canAccepted();
        check(!gap.canSample(0, 4001, 4001), "Release after a long gap updates the baseline without acting");
        check(!gap.rawDuplicate(4010), "Long gap cannot preserve prior CAN ownership");
        check(gap.canSample(1, 4100, 4100), "New edge after gap baseline works");
        gap.canAccepted();
        gap.resetCan();
        check(!gap.rawDuplicate(4110), "Capture stop clears CAN ownership immediately");
        check(!gap.canSample(1, 4200, 4200), "Capture restart while held cannot launch");
        check(!gap.canSample(0, 4300, 4300), "Capture restart waits for idle");
        check(gap.canSample(1, 4400, 4400), "Capture restart permits the next complete press");
        gap.canAccepted();
        check(!gap.canSample(2, 4450, 4450), "CAN state 2 is invalid; it is not an MCU hold");
        check(!gap.rawDuplicate(4460), "Invalid CAN state clears CAN ownership");
        check(!gap.canSample(0, -1, 4500), "Negative timestamps cannot seed CAN state");

        VoiceSources reordered = new VoiceSources();
        idle(reordered, 5000);
        check(!reordered.canSample(1, 4999, 5100), "Out-of-order sample cannot create a rising action");
        check(!reordered.rawDuplicate(5100), "Out-of-order sample cannot create ownership");
    }

    private static void sparseCanKeepsBaseline() {
        VoiceSources sources = new VoiceSources();
        idle(sources, 1000);
        check(sources.canSample(1, 10000, 10000), "A real rising edge after nine seconds of idle must not be discarded");
        sources.canAccepted();
        check(sources.rawDuplicate(10400), "Successfully handled sparse CAN rise owns its current cycle");
        check(!sources.rawDuplicate(11501), "Sparse CAN baseline retention must not extend duplicate freshness");
        check(!sources.canSample(1, 12000, 12000), "Same held level after a long gap is not another rising edge");
        check(!sources.rawDuplicate(12001), "A high heartbeat after a gap cannot revive expired action ownership");
        check(!sources.canSample(0, 12100, 12100), "The retained high baseline can return idle without acting");
        check(sources.canSample(1, 12200, 12200), "A complete new edge after sparse held reports still works");
        sources.canAccepted();
        check(!sources.canSample(0, 12300, 12300), "Release establishes the next observed idle baseline");
        check(sources.canSample(1, 20000, 20000), "Long silence after a real release preserves the next rising edge");
        sources.canAccepted();
        sources.resetCan();
        check(!sources.canSample(1, 30000, 30000), "Explicit stop still clears the baseline despite long-gap retention");
        check(!sources.rawDuplicate(30001), "Held first sample after explicit stop has no action ownership");

        VoiceSources rollback = new VoiceSources();
        idle(rollback, 5000);
        check(!rollback.canSample(1, 4000, 4000), "Clock rollback resets an idle baseline instead of acting on a false edge");
        check(!rollback.canSample(1, 7000, 7000), "Rolled-back held baseline does not turn into an edge after a long gap");
        check(!rollback.canSample(0, 7100, 7100), "Rollback recovery observes a real idle");
        check(rollback.canSample(1, 10000, 10000), "A real edge after rollback recovery and a long gap works");
    }

    private static void rawTailSurvivesCanStop() {
        VoiceSources sources = new VoiceSources();
        VoiceGesture gesture = new VoiceGesture();
        idle(sources, 1000);
        check(sources.canSample(1, 1100, 1100), "CAN action is requested before capture stop");
        sources.canAccepted();
        check(sources.rawDuplicate(1450), "Real MCU down is identified as an already-handled press");
        gesture.accepted(1450);
        sources.resetCan();
        check(!sources.rawDuplicate(1500), "Stopped capture no longer claims new raw input");
        check(gesture.decide(2, 1600, false, true) == VoiceGesture.CONSUME, "Existing MCU hold stays owned after CAN stop and protection change");
        check(gesture.decide(0, 1700, false, true) == VoiceGesture.CONSUME, "Existing MCU release stays owned after CAN stop");
        check(gesture.decide(1, 1800, true, true) == VoiceGesture.PASS, "A new protected MCU gesture stays stock");
        check(gesture.decide(0, 1850, false, true) == VoiceGesture.PASS, "New protected gesture release also stays stock");
        check(gesture.decide(1, 2100, true, false) == VoiceGesture.TRY_ACTION, "Raw-only operation resumes after leaving protection");
    }

    public static void main(String[] args) {
        canOnly();
        rawOnly();
        rawThenCan();
        canThenRaw();
        initialHeldAndFailures();
        freshnessAndReset();
        sparseCanKeepsBaseline();
        rawTailSurvivesCanStop();
        System.out.println("PASS: " + checks + " Voice source checks");
    }
}
