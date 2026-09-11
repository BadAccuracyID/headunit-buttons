package com.efran.buttons;

import java.util.HashMap;
import java.util.Map;

public final class RulesTest {
    private static int checks;
    private static void expect(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Map<Integer, String> map = new HashMap<>();
        map.put(2, "key:3");
        map.put(181, "block");
        Rules.Snapshot active = new Rules.Snapshot(true, 1000, map);
        expect("key:3".equals(active.action(2, 1200, false)), "Configured event maps");
        expect(active.action(6, 1200, false) == null, "Unmapped event passes through");
        expect(active.action(2, 1200, true) == null, "Camera and powered-off modes pass through");
        expect(active.action(2, 3501, false) == null, "Expired connection cannot suppress buttons");
        expect(active.action(2, 999, false) == null, "Invalid clock cannot authorize overrides");
        expect("key:3".equals(active.action(2, 3500, false)), "Lease boundary is defined");
        expect("block".equals(active.action(-75, 1200, false)), "Signed firmware bytes normalize");
        expect(new Rules.Snapshot(false, 1000, map).action(2, 1200, false) == null, "Master off retains original behavior");
        map.put(2, "block");
        expect("key:3".equals(active.action(2, 1200, false)), "Published config is immutable");
        for (int code : Rules.OUTPUTS) expect(Rules.validAction("key:" + code), "Known output accepted");
        for (String rejected : new String[]{null, "", "key:1", "key:999", "key:garbage", "key:-75", "shell:reboot", "app:", "app:com.test;reboot", "app:com/test", "app:com..test"})
            expect(!Rules.validAction(rejected), "Invalid action rejected: " + rejected);
        expect(Rules.validAction("app:com.zjinnova.zlink"), "Package launch accepted");
        expect(Rules.validAction("block"), "Explicit suppression accepted");
        byte[] input = {(byte)0xF2, 0, (byte)0xA1, 3, 0x17, 20, 1, 0};
        expect(McuPacket.isButton(input), "CAN button packet recognized before dispatch");
        expect(McuPacket.describe(input).equals("MCU button 20 / state 1"), "Raw code preserves physical identity");
        expect(McuPacket.hex(input).equals("F2 00 A1 03 17 14 01 00"), "Raw packet bytes preserved");
        expect(!McuPacket.isButton(null), "Null packet ignored");
        expect(!McuPacket.isButton(new byte[6]), "Truncated packet ignored");
        input[4] = 0x18;
        expect(!McuPacket.isButton(input), "Clock telemetry is not a button");
        input[4] = 0x13;
        expect(!McuPacket.isButton(input), "Steering angle is not a button");
        input[4] = 0x1A;
        input[5] = 2;
        expect(McuPacket.isSourceSwitch(input), "Stock display switch recognized independently");
        expect(!McuPacket.isButton(input), "Display switch does not masquerade as a physical button");
        expect(!McuPacket.isSourceSwitch(null), "Null is not a display switch");
        expect(!McuPacket.isSourceSwitch(new byte[5]), "Truncated display switch ignored");
        input[4] = 0x17;
        input[1] = (byte)0xA0;
        expect(!McuPacket.isButton(input), "Other MCU channels are not button packets");
        expect(McuPacket.hex(new byte[500]).length() < 200, "Diagnostic output is bounded");
        VoiceGesture voice = new VoiceGesture();
        expect(voice.decide(0, 1000, true, false) == VoiceGesture.PASS, "Release without mapped press remains stock");
        expect(voice.decide(1, 1000, false, false) == VoiceGesture.PASS, "Unmapped voice press remains stock");
        expect(voice.decide(1, 1000, true, true) == VoiceGesture.PASS, "Voice does not override reverse or power mode");
        expect(voice.decide(1, 1000, true, false) == VoiceGesture.TRY_ACTION, "Mapped voice press requests a launch");
        expect(voice.decide(0, 1050, true, false) == VoiceGesture.PASS, "Failed launch leaves release stock");
        voice.accepted(2000);
        expect(voice.decide(1, 2001, true, false) == VoiceGesture.CONSUME, "Repeated down cannot launch twice");
        expect(voice.decide(2, 2200, true, false) == VoiceGesture.CONSUME, "Hold cannot invoke stock navigation");
        expect(voice.decide(0, 2300, false, false) == VoiceGesture.CONSUME, "Existing gesture finishes even if disabled mid-press");
        expect(voice.decide(1, 2400, false, false) == VoiceGesture.PASS, "Next press respects disabled mapping");
        voice.accepted(3000);
        expect(voice.decide(2, 3100, true, true) == VoiceGesture.PASS, "Camera transition clears consumed gesture");
        expect(voice.decide(0, 3200, true, false) == VoiceGesture.PASS, "Cleared gesture does not consume later release");
        voice.accepted(4000);
        expect(voice.decide(1, 14001, true, false) == VoiceGesture.TRY_ACTION, "Missing release cannot latch forever");
        voice.accepted(5000);
        expect(voice.decide(0, 4999, true, false) == VoiceGesture.PASS, "Clock reset clears latch");
        voice.accepted(6000);
        expect(voice.decide(9, 6050, true, false) == VoiceGesture.PASS, "Unknown voice state remains stock");
        expect(Rules.validVoiceAction(Rules.CAMERA_HOME), "Voice camera toggle accepted");
        expect(!Rules.validAction(Rules.CAMERA_HOME), "Camera toggle cannot enter the semantic adapter");
        expect(Rules.voiceGoesHome(Rules.CAMERA_HOME, "com.ivicar.avm"), "Visible camera toggles Home");
        expect(!Rules.voiceGoesHome(Rules.CAMERA_HOME, "com.zjinnova.zlink"), "ZLink foreground opens camera");
        expect(!Rules.voiceGoesHome(Rules.CAMERA_HOME, ""), "Unknown foreground cannot send Home");
        expect(!Rules.voiceGoesHome("app:com.ivicar.avm", "com.ivicar.avm"), "Ordinary app mapping does not toggle Home");
        expect(!Rules.validVoiceAction("key:55"), "Unsupported raw voice output rejected");
        System.out.println("PASS: " + checks + " policy checks");
    }
}
