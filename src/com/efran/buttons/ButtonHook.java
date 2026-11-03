package com.efran.buttons;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Remaps semantic actions and the identified Voice-on input; other MCU traffic passes through. */
public final class ButtonHook implements IXposedHookLoadPackage {
    private volatile Rules.Snapshot snapshot = new Rules.Snapshot(false, 0, Collections.emptyMap());
    private final ArrayBlockingQueue<String> events = new ArrayBlockingQueue<>(100);
    private ScheduledExecutorService poller;
    private Field reverse;
    private Field rearCamera;
    private Field poweredOff;
    private volatile long rawUntil;
    private volatile boolean packetHookReady;
    private volatile Service hookedService;
    private volatile Rules.Snapshot voiceSnapshot = new Rules.Snapshot(false, 0, Collections.emptyMap());
    private final VoiceGesture voiceGesture = new VoiceGesture();
    private final VoiceSources voiceSources = new VoiceSources();
    private static final String CAN_VOICE_BRIDGE = "com.efran.buttons.CAN_VOICE_V1";
    private volatile BrightnessAdapter brightness;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (!"com.szchoiceway.eventcenter".equals(load.packageName)) return;
        try {
            Class<?> service = XposedHelpers.findClass("com.szchoiceway.eventcenter.EventService", load.classLoader);
            // Refuse this adapter if the expected firmware shape differs.
            Method keyMethod = service.getDeclaredMethod("ProcessCanKey", int.class);
            if (keyMethod.getReturnType() != void.class) throw new IllegalStateException("Unexpected key handler");
            reverse = booleanField(service, "bBackCar");
            rearCamera = booleanField(service, "m_bInBackcarMode");
            poweredOff = booleanField(service, "m_bPowerOff");
            installPacketObserver(load.classLoader);
            installSerialObserver(load.classLoader);
            installBrightness(service);
            XposedBridge.hookMethod(keyMethod, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    int code = ((Integer) param.args[0]) & 255;
                    String result = "stock";
                    try {
                        boolean protectedMode = reverse.getBoolean(param.thisObject)
                                || rearCamera.getBoolean(param.thisObject) || poweredOff.getBoolean(param.thisObject);
                        String action = snapshot.action(code, SystemClock.elapsedRealtime(), protectedMode);
                        if (action != null && Rules.validAction(action)) {
                            if ("block".equals(action)) {
                                param.setResult(null);
                                result = "blocked";
                            } else if (action.startsWith("key:")) {
                                int replacement = Integer.parseInt(action.substring(4));
                                // Replace the argument before the stock handler runs. The handler
                                // executes once, including when its replacement action throws.
                                param.args[0] = replacement;
                                result = "routed to " + Rules.label(replacement);
                            } else if (Rules.CARPLAY_SELECTED.equals(action)) {
                                Context context = (Context) param.thisObject;
                                Intent launch = new Intent().setClassName("com.efran.carplayswitch",
                                        "com.shilapi.xcertplay.gt6switch.OpenSelectedActivity");
                                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                context.startActivity(launch);
                                param.setResult(null);
                                result = "opened selected CarPlay receiver";
                            } else {
                                Context context = (Context) param.thisObject;
                                Intent launch = context.getPackageManager().getLaunchIntentForPackage(action.substring(4));
                                if (launch != null) {
                                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    context.startActivity(launch);
                                    param.setResult(null);
                                    result = "opened " + action.substring(4);
                                } else result = "app unavailable; stock";
                            }
                        } else if (protectedMode) result = "camera / power mode; stock";
                    } catch (Throwable error) {
                        // No result set on failure: the original method is allowed to run.
                        result = "action failed; stock (" + error.getClass().getSimpleName() + ")";
                        XposedBridge.log("HeadunitButtons: " + result);
                    } finally {
                        events.offer(code + "|" + SystemClock.elapsedRealtime() + "|" + result);
                        Log.i("HeadunitButtons", "ACTION " + code + " " + result);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(service, "onCreate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable()) {
                        hookedService = (Service) param.thisObject;
                        installCanVoiceBridge(hookedService);
                        startPolling(hookedService.getApplicationContext());
                    }
                }
            });
            XposedHelpers.findAndHookMethod(service, "onDestroy", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    XposedHelpers.removeAdditionalInstanceField(param.thisObject, CAN_VOICE_BRIDGE);
                    hookedService = null;
                    stopPolling();
                }
            });
            XposedBridge.log("HeadunitButtons: ProcessCanKey adapter installed; remapping defaults off");
        } catch (Throwable error) {
            XposedBridge.log("HeadunitButtons: unsupported firmware; stock behavior retained: " + error);
        }
    }
    private void installBrightness(Class<?> service) {
        try {
            brightness = BrightnessAdapter.install(service);
            XposedBridge.log("HeadunitButtons: brightness schedule adapter installed; schedule defaults off");
        } catch (Throwable error) {
            XposedBridge.log("HeadunitButtons: brightness schedule unavailable: " + error);
        }
    }
    private void installPacketObserver(ClassLoader loader) {
        try {
            Class<?> parser = XposedHelpers.findClass("com.szchoiceway.eventcenter.EventService$3$1", loader);
            XposedHelpers.findAndHookMethod(parser, "processKSWCmd", byte[].class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    byte[] data = (byte[]) param.args[0];
                    if (data == null || data.length < 4) return;
                    boolean button = McuPacket.isButton(data);
                    if (!button && !McuPacket.isSourceSwitch(data) && SystemClock.elapsedRealtime() >= rawUntil) return;
                    String description = McuPacket.describe(data);
                    String hex = McuPacket.hex(data);
                    // Record first. Only the explicitly mapped Voice-on gesture may be consumed.
                    Log.i("HeadunitButtons", description + ": " + hex);
                    if (button || McuPacket.isSourceSwitch(data)) events.offer("raw|" + SystemClock.elapsedRealtime() + "|" + description + "\n" + hex);
                    if (button && (data[5] & 255) == 20) handleVoice(param, data);
                }
            });
            packetHookReady = true;
            XposedBridge.log("HeadunitButtons: MCU packet observer installed");
        } catch (Throwable error) {
            XposedBridge.log("HeadunitButtons: MCU packet observer unavailable: " + error);
        }
    }
    private void installCanVoiceBridge(Service value) {
        try {
            BiConsumer<Integer, Long> callback = (state, at) -> {
                // Follow the same two queues as normal MCU frames, so an earlier
                // gear update cannot be overtaken by this CAN action.
                Handler ingress = (Handler) XposedHelpers.getObjectField(value, "mHandler");
                if (ingress == null) return;
                ingress.post(() -> {
                    if (hookedService != value) return;
                    // Firmware creates this second Handler asynchronously.
                    Handler handler = (Handler) XposedHelpers.getObjectField(value, "mProcessHandler");
                    if (handler == null) return;
                    handler.post(() -> {
                        if (hookedService != value) return;
                        try {
                            long now = SystemClock.elapsedRealtime();
                            if (voiceSources.canSample(state, at, now) && performVoiceAction(now, "CAN"))
                                voiceSources.canAccepted();
                        } catch (Throwable error) {
                            Log.w("HeadunitButtons", "CAN Voice action failed", error);
                        }
                    });
                });
            };
            XposedHelpers.setAdditionalInstanceField(value, CAN_VOICE_BRIDGE, callback);
            XposedBridge.log("HeadunitButtons: CAN Voice bridge installed");
        } catch (Throwable error) {
            XposedBridge.log("HeadunitButtons: CAN Voice unavailable; MCU route retained: " + error);
        }
    }
    private void handleVoice(XC_MethodHook.MethodHookParam param, byte[] data) {
        try {
            int state = data[6] & 255;
            long now = SystemClock.elapsedRealtime();
            // An accepted MCU gesture owns its tails even if CAN capture stops.
            if (voiceGesture.decide(state, now, false, false) == VoiceGesture.CONSUME) { param.setResult(null); return; }
            if (state != 1) return;
            if (voiceSources.rawDuplicate(now)) {
                voiceGesture.accepted(now);
                param.setResult(null);
                Log.i("HeadunitButtons", "VOICE_DUPLICATE MCU after CAN");
                return;
            }
            if (performVoiceAction(now, "MCU")) {
                voiceSources.rawAccepted(now);
                voiceGesture.accepted(now);
                param.setResult(null);
            }
        } catch (Throwable error) {
            voiceGesture.reset();
            Log.w("HeadunitButtons", "Voice override failed; stock retained", error);
        }
    }
    private boolean performVoiceAction(long now, String source) throws Throwable {
        Service service = hookedService;
        if (service == null) return false;
        Object mipi = XposedHelpers.getObjectField(service, "mMipiModeUtil");
        String mode = mipi == null ? null : String.valueOf(XposedHelpers.callMethod(mipi, "getCurrentMode"));
        Object camera = XposedHelpers.getObjectField(service, "cameraUtilXYQ");
        boolean cameraReversing = camera != null && Boolean.TRUE.equals(XposedHelpers.callMethod(camera, "isReverse"));
        boolean protectedMode = Rules.voiceProtected(reverse.getBoolean(service) || cameraReversing, rearCamera.getBoolean(service),
                poweredOff.getBoolean(service), XposedHelpers.getIntField(service, "mGearType"), mode);
        String action = voiceSnapshot.action(20, now, protectedMode);
        if (!Rules.validVoiceAction(action)) {
            Log.i("HeadunitButtons", "VOICE_PASS source=" + source + " protected=" + protectedMode + " mode=" + mode);
            return false;
        }
        String foreground = "";
        if (Rules.CAMERA_HOME.equals(action)) {
            Class<?> firmwareUtils = XposedHelpers.findClass("com.szchoiceway.eventcenter.EventUtils", service.getClassLoader());
            // AVM runs on display 2; the filtered helper only checks display 0.
            foreground = (String) XposedHelpers.callStaticMethod(firmwareUtils, "getTopPackageNameUnfiltered", service);
            Log.i("HeadunitButtons", "VOICE_FOREGROUND " + foreground);
        }
        String result;
        if (Rules.voiceGoesHome(action, foreground)) {
            XposedHelpers.callMethod(service, "sendKeyDownUpSync", 3);
            result = "Home (camera foreground)";
        } else if (!"block".equals(action)) {
            String target = Rules.CAMERA_HOME.equals(action) ? Rules.CAMERA_PACKAGE : action.substring(4);
            Intent launch = service.getPackageManager().getLaunchIntentForPackage(target);
            if (launch == null) return false;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            service.startActivity(launch);
            result = "opened " + target;
        } else result = "blocked";
        Log.i("HeadunitButtons", "VOICE_ON source=" + source + " " + result);
        events.offer("voice|" + now + "|" + result);
        return true;
    }
    private static Field booleanField(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        if (field.getType() != boolean.class) throw new IllegalStateException("Unexpected field " + name);
        field.setAccessible(true);
        return field;
    }
    private void installSerialObserver(ClassLoader loader) {
        try {
            Class<?> reader = XposedHelpers.findClass("android.serialport.SerialReadThread", loader);
            for (String name : new String[]{"parseKSWRxData", "parseKSWCanGatherRxData", "parseRxData"}) {
                XposedHelpers.findAndHookMethod(reader, name, byte[].class, int.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (SystemClock.elapsedRealtime() >= rawUntil) return;
                        try {
                            byte[] data = (byte[]) param.args[0];
                            if (data == null) return;
                            int count = Math.min(data.length, Math.max(0, (Integer) param.args[1]));
                            // Log bounded copies of the existing read. Never read the serial device
                            // ourselves, consume an event, or change the original parser arguments.
                            long time = SystemClock.elapsedRealtime();
                            for (int offset = 0; offset < Math.min(count, 4096); offset += 64) {
                                byte[] part = java.util.Arrays.copyOfRange(data, offset, Math.min(count, offset + 64));
                                Log.i("HeadunitSerial", name + " t=" + time + " offset=" + offset
                                        + " count=" + count + " " + McuPacket.hex(part));
                            }
                        } catch (Throwable ignored) { /* Observation must not disrupt input. */ }
                    }
                });
            }
            XposedBridge.log("HeadunitButtons: pre-parser serial observers installed");
        } catch (Throwable error) {
            XposedBridge.log("HeadunitButtons: serial observer unavailable: " + error);
        }
    }
    private synchronized void startPolling(Context context) {
        stopPolling();
        poller = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "HeadunitButtons-config");
            thread.setDaemon(true);
            return thread;
        });
        poller.scheduleWithFixedDelay(() -> {
            try {
                long requestStarted = SystemClock.elapsedRealtime();
                Bundle request = new Bundle();
                ArrayList<String> pending = new ArrayList<>();
                events.drainTo(pending);
                request.putStringArrayList("events", pending);
                request.putBoolean("packetHookReady", packetHookReady);
                BrightnessAdapter schedule = brightness;
                request.putString("brightnessStatus", schedule == null ? "Not supported by this EventCenter" : schedule.status());
                Bundle response = context.getContentResolver().call(ConfigProvider.URI, "poll", null, request);
                if (response == null) throw new IllegalStateException("No configuration");
                rawUntil = response.getLong("rawUntil", 0);
                Map<Integer, String> mappings = new HashMap<>();
                Bundle saved = response.getBundle("mappings");
                if (saved != null) for (String key : saved.keySet()) {
                    try {
                        int code = Integer.parseInt(key);
                        String action = saved.getString(key);
                        if (code >= 0 && code <= 255 && Rules.validAction(action)) mappings.put(code, action);
                    } catch (RuntimeException ignored) { }
                }
                snapshot = new Rules.Snapshot(response.getBoolean("enabled", false), requestStarted, mappings);
                Map<Integer, String> voiceActions = new HashMap<>();
                String voiceAction = response.getString("voiceAction");
                if (Rules.validVoiceAction(voiceAction)) voiceActions.put(20, voiceAction);
                voiceSnapshot = new Rules.Snapshot(response.getBoolean("enabled", false), requestStarted, voiceActions);
                Service service = hookedService;
                if (schedule != null && service != null) {
                    boolean protectedMode = reverse.getBoolean(service) || rearCamera.getBoolean(service)
                            || poweredOff.getBoolean(service);
                    schedule.tick(service, response.getBoolean("brightnessEnabled", false),
                            BrightnessSchedule.parse(response.getString("brightnessSchedule")), protectedMode);
                }
            } catch (Throwable error) {
                snapshot = new Rules.Snapshot(false, 0, Collections.emptyMap());
                voiceSnapshot = new Rules.Snapshot(false, 0, Collections.emptyMap());
            }
        }, 0, 500, TimeUnit.MILLISECONDS);
    }
    private synchronized void stopPolling() {
        snapshot = new Rules.Snapshot(false, 0, Collections.emptyMap());
        voiceSnapshot = new Rules.Snapshot(false, 0, Collections.emptyMap());
        if (poller != null) poller.shutdownNow();
        poller = null;
    }
}
