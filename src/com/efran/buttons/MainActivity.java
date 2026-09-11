package com.efran.buttons;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MainActivity extends Activity {
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout mappings;
    private LinearLayout captured;
    private TextView status;
    private Switch enabled;
    private String previousEvents = "";
    private boolean connected;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { refreshStatus(); handler.postDelayed(this, 1000); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("rules", 0);
        if (getIntent().getBooleanExtra("capture_raw", false)) prefs.edit().putLong("rawUntil", SystemClock.elapsedRealtime() + 600000).apply();
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int gutter = dp(getResources().getConfiguration().screenWidthDp >= 600 ? 32 : 16);
        root.setPadding(gutter, dp(16), gutter, dp(32));
        scroll.addView(root);
        setContentView(scroll);
        text(root, "Headunit Buttons", 28);
        text(root, "Capture a button, then choose what it does. Version 0.7", 18);
        status = text(root, "Checking connection...", 18);
        enabled = new Switch(this);
        enabled.setText("Enable remapping");
        enabled.setTextSize(20);
        enabled.setMinHeight(dp(64));
        enabled.setChecked(prefs.getBoolean("enabled", false));
        enabled.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("enabled", checked).apply());
        root.addView(enabled);
        text(root, "Off keeps every original action. Changes take up to 2.5 seconds to reach the button handler.", 16);
        text(root, "Mappings", 24);
        mappings = column(root);
        button(root, "Set Voice on action").setOnClickListener(view -> chooseAction(-20));
        button(root, "Set Navi action").setOnClickListener(view -> chooseAction(55));
        text(root, "Captured buttons", 24);
        text(root, "Press a physical button while parked. Tap an event below to assign an action. Capture keeps running with this screen closed.", 16);
        Button clear = button(root, "Clear captured events");
        clear.setOnClickListener(view -> {
            getContentResolver().call(ConfigProvider.URI, "clear", null, null);
            previousEvents = "";
            refreshStatus();
        });
        captured = column(root);
        Button record = button(root, "Record all MCU packets for 10 minutes");
        record.setOnClickListener(view -> {
            prefs.edit().putLong("rawUntil", SystemClock.elapsedRealtime() + 600000).apply();
            Toast.makeText(this, "Recording diagnostic packets to ADB log for 10 minutes", Toast.LENGTH_LONG).show();
        });
        text(root, "Setup", 24);
        text(root, "This preview needs an Xposed-compatible framework on the headunit. Enable this module for com.szchoiceway.eventcenter. The module loads when EventCenter next starts.", 16);
        text(root, "Voice on, Star and Navi have been captured on this headunit. Voice off is unidentified. Tel currently reports a switch to A20. Voice on can open an app or do nothing.", 16);
        renderMappings();
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("capture_raw", false)) prefs.edit().putLong("rawUntil", SystemClock.elapsedRealtime() + 600000).apply();
    }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    private void refreshStatus() {
        try {
            Bundle data = getContentResolver().call(ConfigProvider.URI, "status", null, null);
            connected = data != null && data.getBoolean("connected");
            status.setText(connected ? (data.getBoolean("packetHookReady") ? "MCU capture and button handler connected" : "Button handler connected; MCU capture unavailable") : "Button handler not connected. Capture is unavailable.");
            // A saved enabled switch can always be turned off, including when disconnected.
            enabled.setEnabled(connected || enabled.isChecked());
            ArrayList<String> events = data == null ? new ArrayList<>() : data.getStringArrayList("events");
            if (events == null) events = new ArrayList<>();
            String fingerprint = events.toString();
            if (fingerprint.equals(previousEvents)) return;
            previousEvents = fingerprint;
            captured.removeAllViews();
            if (events.isEmpty()) text(captured, "No captured buttons yet.", 18);
            for (int index = events.size() - 1; index >= Math.max(0, events.size() - 20); index--) {
                String[] parts = events.get(index).split("\\|", 3);
                if (parts.length != 3) continue;
                if ("raw".equals(parts[0])) {
                    if (parts[2].startsWith("MCU button 20 /")) {
                        button(captured, "Voice on [MCU 20]\n" + parts[2]).setOnClickListener(view -> chooseAction(-20));
                    } else text(captured, parts[2] + "\nRaw input captured.", 18);
                    continue;
                }
                if ("voice".equals(parts[0])) {
                    button(captured, "Voice on\n" + parts[2]).setOnClickListener(view -> chooseAction(-20));
                    continue;
                }
                try {
                    int code = Integer.parseInt(parts[0]);
                    Button row = button(captured, Rules.label(code) + "  [" + code + "]\n" + parts[2]);
                    row.setOnClickListener(view -> chooseAction(code));
                } catch (NumberFormatException ignored) { }
            }
        } catch (RuntimeException error) {
            status.setText("Cannot read button status: " + error.getClass().getSimpleName());
        }
    }
    private void renderMappings() {
        mappings.removeAllViews();
        List<String> keys = new ArrayList<>(prefs.getAll().keySet());
        Collections.sort(keys);
        int count = 0;
        for (String key : keys) {
            if (!key.startsWith("key_") && !"voice_20".equals(key)) continue;
            try {
                int code = "voice_20".equals(key) ? -20 : Integer.parseInt(key.substring(4));
                String action = prefs.getString(key, "");
                if (!(code == -20 ? Rules.validVoiceAction(action) : Rules.validAction(action))) continue;
                Button row = button(mappings, inputLabel(code) + "  →  " + actionLabel(action));
                row.setOnClickListener(view -> chooseAction(code));
                count++;
            } catch (RuntimeException ignored) { }
        }
        if (count == 0) text(mappings, "No mappings. Original actions stay active.", 18);
    }
    private void chooseAction(int code) {
        if (code == -20) {
            new AlertDialog.Builder(this).setTitle("Voice on [MCU 20]")
                    .setItems(new String[]{"Keep original action", "Do nothing", "Open an app", "360 camera / Home"}, (dialog, which) -> {
                        if (which == 2) chooseApp(code);
                        else if (which == 3) save(code, Rules.CAMERA_HOME);
                        else save(code, which == 0 ? null : "block");
                    }).setNegativeButton("Cancel", null).show();
            return;
        }
        String[] choices = {"Keep original action", "Do nothing", "Next", "Previous", "Play / pause", "Home", "Mode", "Voice (firmware)", "Back", "Open an app"};
        String[] actions = {null, "block", "key:2", "key:3", "key:6", "key:9", "key:16", "key:48", "key:85"};
        new AlertDialog.Builder(this).setTitle(Rules.label(code) + " [" + code + "]")
                .setItems(choices, (dialog, which) -> {
                    if (which == choices.length - 1) chooseApp(code);
                    else save(code, actions[which]);
                }).setNegativeButton("Cancel", null).show();
    }
    private void chooseApp(int code) {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(launcher, 0);
        Collections.sort(resolved, (a, b) -> appLabel(a.activityInfo.packageName).compareToIgnoreCase(appLabel(b.activityInfo.packageName)));
        ArrayList<String> names = new ArrayList<>();
        ArrayList<String> packages = new ArrayList<>();
        for (ResolveInfo app : resolved) {
            String name = app.activityInfo.packageName;
            if (packages.contains(name)) continue;
            packages.add(name);
            names.add(appLabel(name));
        }
        new AlertDialog.Builder(this).setTitle("Open an app")
                .setItems(names.toArray(new String[0]), (dialog, which) -> save(code, "app:" + packages.get(which)))
                .setNegativeButton("Cancel", null).show();
    }
    private void save(int code, String action) {
        SharedPreferences.Editor edit = prefs.edit();
        String key = code == -20 ? "voice_20" : "key_" + code;
        if (action == null) edit.remove(key);
        else if (code == -20 ? Rules.validVoiceAction(action) : Rules.validAction(action)) edit.putString(key, action);
        else return;
        edit.apply();
        renderMappings();
        Toast.makeText(this, "Mapping saved", Toast.LENGTH_SHORT).show();
    }
    private String inputLabel(int code) {
        return code == -20 ? "Voice on [MCU 20]" : Rules.label(code) + " [" + code + "]";
    }
    private String actionLabel(String action) {
        return action.startsWith("app:") ? "Open " + appLabel(action.substring(4)) : Rules.actionLabel(action);
    }
    private String appLabel(String packageName) {
        if ("com.zjinnova.zlink".equals(packageName)) return "ZLink (CarPlay)";
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(packageName, 0)).toString(); }
        catch (android.content.pm.PackageManager.NameNotFoundException error) { return packageName; }
    }
    private LinearLayout column(LinearLayout parent) {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        parent.addView(view);
        return view;
    }
    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(0, dp(8), 0, dp(8));
        parent.addView(view);
        return view;
    }
    private Button button(LinearLayout parent, String label) {
        Button view = new Button(this);
        view.setText(label);
        view.setTextSize(18);
        view.setAllCaps(false);
        view.setMinHeight(dp(64));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.setMargins(0, dp(4), 0, dp(4));
        parent.addView(view, layout);
        return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
