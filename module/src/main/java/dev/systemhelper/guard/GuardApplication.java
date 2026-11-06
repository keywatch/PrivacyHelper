package dev.systemhelper.guard;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** Own-app service connection, separate from injected-process entry code. */
public final class GuardApplication extends Application {
    static volatile XposedService service;
    static volatile SharedPreferences preferences;
    static Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    @Override public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override public void onServiceBind(XposedService value) {
                service = value; preferences = value.getRemotePreferences("policies"); notifyActivity();
            }
            @Override public void onServiceDied(XposedService value) {
                if (service == value) { service = null; preferences = null; notifyActivity(); }
            }
        });
    }
    private void notifyActivity() { main.post(() -> { if (changed != null) changed.run(); }); }
}
