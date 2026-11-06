package dev.systemhelper.guard;

import android.content.SharedPreferences;
import android.util.Log;

/** Framework-owned remote preferences; no filesystem polling or world-readable files. */
final class PolicyReader {
    private final SharedPreferences preferences;
    private final String key;
    private volatile Policy current = new Policy();
    private final SharedPreferences.OnSharedPreferenceChangeListener listener;
    PolicyReader(SharedPreferences preferences, String packageName) {
        this.preferences = preferences; key = "app:" + packageName;
        listener = (store, changed) -> { if (changed == null || changed.equals(key)) refresh(); };
        preferences.registerOnSharedPreferenceChangeListener(listener); refresh();
    }
    private void refresh() {
        try { current = Policy.parse(preferences.getString(key, "{}")); }
        catch (RuntimeException error) { current = new Policy(); Log.e("SystemHelperGuard", "remote-policy-unavailable", error); }
        Guard.syncNative(current);
    }
    Policy get() { return current; }
}
