package com.efran.buttons;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Map;

public final class ConfigProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://com.efran.buttons.config");
    private final ArrayList<String> events = new ArrayList<>();
    private volatile long heartbeat;
    private boolean packetHookReady;
    private SharedPreferences prefs;
    @Override public boolean onCreate() {
        prefs = getContext().getSharedPreferences("rules", 0);
        return true;
    }
    private boolean ownCaller() { return Binder.getCallingUid() == Process.myUid(); }
    private void enforceCaller() {
        if (ownCaller()) return;
        String[] packages = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages != null) for (String name : packages) if ("com.szchoiceway.eventcenter".equals(name)) return;
        throw new SecurityException("Only the app and EventCenter may access button configuration");
    }
    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        enforceCaller();
        Bundle out = new Bundle();
        if ("poll".equals(method)) {
            heartbeat = SystemClock.elapsedRealtime();
            if (extras != null) {
                packetHookReady = extras.getBoolean("packetHookReady", false);
                ArrayList<String> incoming = extras.getStringArrayList("events");
                if (incoming != null) for (String event : incoming) {
                    if (event != null && event.length() < 300) events.add(event);
                }
                while (events.size() > 100) events.remove(0);
            }
            out.putBoolean("enabled", prefs.getBoolean("enabled", false));
            out.putLong("rawUntil", prefs.getLong("rawUntil", 0));
            out.putString("voiceAction", prefs.getString("voice_20", null));
            Bundle mappings = new Bundle();
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                if (entry.getKey().startsWith("key_") && entry.getValue() instanceof String)
                    mappings.putString(entry.getKey().substring(4), (String) entry.getValue());
            }
            out.putBundle("mappings", mappings);
        } else if ("status".equals(method) && ownCaller()) {
            out.putBoolean("connected", heartbeat > 0 && SystemClock.elapsedRealtime() - heartbeat < Rules.LEASE_MS);
            out.putBoolean("packetHookReady", packetHookReady);
            out.putStringArrayList("events", new ArrayList<>(events));
        } else if ("clear".equals(method) && ownCaller()) {
            events.clear();
        } else throw new SecurityException("Unsupported operation");
        return out;
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
