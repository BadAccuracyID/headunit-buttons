package com.efran.buttons;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
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
                        startPolling(hookedService.getApplicationContext());
                    }
                }
            });
            XposedHelpers.findAndHookMethod(service, "onDestroy", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    hookedService = null;
                    stopPolling();
                }
            });
            XposedBridge.log("HeadunitButtons: ProcessCanKey adapter installed; remapping defaults off");
        } catch (Throwable error) {
            XposedBridge.log("HeadunitButtons: unsupported firmware; stock behavior retained: " + error);
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
    private void handleVoice(XC_MethodHook.MethodHookParam param, byte[] data) {
        try {
            Service service = hookedService;
            if (service == null) { voiceGesture.reset(); return; }
            boolean protectedMode = reverse.getBoolean(service) || rearCamera.getBoolean(service)
                    || poweredOff.getBoolean(service);
            Object mipi = XposedHelpers.getObjectField(service, "mMipiModeUtil");
            if (mipi != null) {
                Object mode = XposedHelpers.callMethod(mipi, "getCurrentMode");
                protectedMode |= mode instanceof String && ((String) mode).startsWith("MODE_REVERSE");
            }
            long now = SystemClock.elapsedRealtime();
            String action = voiceSnapshot.action(20, now, protectedMode);
            boolean supported = Rules.validVoiceAction(action);
            int decision = voiceGesture.decide(data[6] & 255, now, supported, protectedMode);
            if (decision == VoiceGesture.CONSUME) { param.setResult(null); return; }
            if (decision != VoiceGesture.TRY_ACTION) return;
            String foreground = "";
            if (Rules.CAMERA_HOME.equals(action)) {
                Class<?> firmwareUtils = XposedHelpers.findClass("com.szchoiceway.eventcenter.EventUtils", service.getClassLoader());
                // The AVM app uses display 2. The firmware's filtered helper only checks
                // display 0 and misses it, so read the focused task across displays.
                foreground = (String) XposedHelpers.callStaticMethod(firmwareUtils, "getTopPackageNameUnfiltered", service);
                Log.i("HeadunitButtons", "VOICE_FOREGROUND " + foreground);
            }
            String result;
            if (Rules.voiceGoesHome(action, foreground)) {
                // Use the firmware's Home key injection, as its stock Star/Home handler does.
                XposedHelpers.callMethod(service, "sendKeyDownUpSync", 3);
                result = "Home (camera foreground)";
            } else if (!"block".equals(action)) {
                String target = Rules.CAMERA_HOME.equals(action) ? Rules.CAMERA_PACKAGE : action.substring(4);
                Intent launch = service.getPackageManager().getLaunchIntentForPackage(target);
                if (launch == null) return;
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                service.startActivity(launch);
                result = "opened " + target;
            } else result = "blocked";
            // Suppress this press only after the requested launch is accepted. Finish the
            // same gesture without allowing its hold/release to invoke stock navigation.
            voiceGesture.accepted(now);
            param.setResult(null);
            Log.i("HeadunitButtons", "VOICE_ON " + result);
            events.offer("voice|" + now + "|" + result);
        } catch (Throwable error) {
            voiceGesture.reset();
            Log.w("HeadunitButtons", "Voice override failed; stock retained", error);
        }
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
