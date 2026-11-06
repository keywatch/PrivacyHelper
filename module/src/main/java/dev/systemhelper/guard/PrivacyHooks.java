package dev.systemhelper.guard;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.location.Location;
import android.media.AudioRecord;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.concurrent.locks.LockSupport;

/** Capability adapters implemented directly with modern interceptor chains. */
final class PrivacyHooks {
    private final XposedInterface module;
    private final PolicyReader reader;
    private final String packageName;
    private final RegistrationLedger receivers = new RegistrationLedger();
    private final RegistrationLedger phoneCallbacks = new RegistrationLedger();
    private final Set<String> hits = Collections.synchronizedSet(new HashSet<String>());
    private int installed;
    private static final Set<String> ACTIONS = new HashSet<>(Arrays.asList(
        "android.intent.action.PHONE_STATE", "android.intent.action.NEW_OUTGOING_CALL", "android.intent.action.SIM_STATE_CHANGED",
        "android.intent.action.SCREEN_ON", "android.intent.action.SCREEN_OFF", "android.intent.action.USER_PRESENT",
        "android.intent.action.AIRPLANE_MODE", "android.intent.action.SERVICE_STATE"));
    PrivacyHooks(XposedInterface module, PolicyReader reader, String packageName) { this.module = module; this.reader = reader; this.packageName = packageName; }
    private void hit(String name) { if (hits.add(name)) Log.i("SystemHelperGuard", "HIT package=" + packageName + " adapter=" + name); }
    private int mode(String key) { return reader.get().mode(key); }
    private void deny(String key) { hit(key + ":deny"); throw new SecurityException("Operation unavailable"); }
    private static Object field(Object object, String name) throws ReflectiveOperationException {
        for (Class<?> cls = object.getClass(); cls != null; cls = cls.getSuperclass()) {
            try { Field field = cls.getDeclaredField(name); field.setAccessible(true); return field.get(object); }
            catch (NoSuchFieldException next) { /* Search the actual hierarchy. */ }
        }
        throw new NoSuchFieldException(name);
    }
    private void hook(String type, String name, XposedInterface.Hooker adapter) {
        Class<?> cls;
        try { cls = Class.forName(type, false, null); } catch (ClassNotFoundException absent) { return; }
        for (Method method : cls.getDeclaredMethods()) if (method.getName().equals(name) && !Modifier.isAbstract(method.getModifiers())) {
            try {
                module.hook(method).setPriority(1000).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(adapter);
                ++installed;
            } catch (Throwable error) { Log.e("SystemHelperGuard", "adapter-unavailable " + type + "." + name, error); }
        }
    }
    private XposedInterface.Hooker empty(String key, Object result) {
        return chain -> {
            int value = mode(key); if (value == Policy.DENY) deny(key);
            if (value == Policy.SOFT) { hit(key + ":empty"); return result; }
            return chain.proceed();
        };
    }
    private XposedInterface.Hooker reject(String key) {
        return chain -> { if (mode(key) == Policy.DENY) deny(key); return chain.proceed(); };
    }
    void install() {
        // Only capabilities selected at process start install code hooks. New
        // capabilities require restart; existing hooks read later mode changes.
        if (mode("events") != Policy.OFF) { broadcasts(); phoneListeners(); }
        if (mode("location") != Policy.OFF) location();
        if (mode("identifiers") != Policy.OFF) identifiers();
        if (mode("records") != Policy.OFF) records();
        if (mode("audio") != Policy.OFF) audio();
        if (mode("clipboard") != Policy.OFF) {
        for (String name : new String[]{"getPrimaryClip", "getPrimaryClipDescription", "getText", "addPrimaryClipChangedListener"}) hook("android.content.ClipboardManager", name, empty("clipboard", null));
        for (String name : new String[]{"hasPrimaryClip", "hasText"}) hook("android.content.ClipboardManager", name, empty("clipboard", false));
        }
        if (mode("sensors") != Policy.OFF) {
        hook("android.hardware.SystemSensorManager", "registerListenerImpl", empty("sensors", true));
        hook("android.hardware.SystemSensorManager", "requestTriggerSensorImpl", empty("sensors", true));
        }
        // Cleanup APIs always proceed so earlier real subscriptions can stop.
        if (mode("screen") != Policy.OFF) for (String type : new String[]{"android.media.projection.MediaProjectionManager", "android.media.projection.MediaProjection", "android.window.ScreenCapture", "android.view.SurfaceControl"})
            for (String name : new String[]{"getMediaProjection", "createVirtualDisplay", "captureDisplay", "captureLayers", "screenshot"}) hook(type, name, reject("screen"));
        if (mode("network") != Policy.OFF) for (String type : new String[]{"java.net.Socket", "java.net.DatagramSocket"})
            for (String name : new String[]{"connect", "send"}) hook(type, name, reject("network"));
        Log.i("SystemHelperGuard", "adapters-installed api=modern package=" + packageName + " hooks=" + installed);
    }
    private void broadcasts() {
        hook("android.app.ContextImpl", "registerReceiverInternal", chain -> {
            Object[] args = chain.getArgs().toArray();
            Object receiver = null;
            for (Object argument : args) if (argument instanceof BroadcastReceiver) receiver = argument;
            int value = mode("events");
            if (value == Policy.OFF) { Object result = chain.proceed(); receivers.real(chain.getThisObject(), receiver); return result; }
            for (int i = 0; i < args.length; ++i) if (args[i] instanceof IntentFilter) {
                IntentFilter original = (IntentFilter) args[i]; boolean sensitive = false;
                for (String action : ACTIONS) sensitive |= original.hasAction(action);
                if (!sensitive) continue;
                if (value == Policy.DENY) deny("events");
                IntentFilter copy = BroadcastActions.without(original, ACTIONS);
                hit("events:filtered-registration");
                if (copy.countActions() == 0) {
                    receivers.virtual(chain.getThisObject(), receiver);
                    return null;
                }
                args[i] = copy; Object result = chain.proceed(args); receivers.real(chain.getThisObject(), receiver); return result;
            }
            Object result = chain.proceed(); receivers.real(chain.getThisObject(), receiver); return result;
        });
        hook("android.app.ContextImpl", "unregisterReceiver", chain -> {
            if (receivers.skipCleanup(chain.getThisObject(), chain.getArg(0))) return null;
            return chain.proceed();
        });
        hook("android.app.ActivityThread", "handleReceiver", chain -> {
            if (mode("events") != Policy.OFF) {
                Object data = chain.getArg(0), intent = field(data, "intent");
                if (intent instanceof Intent && ACTIONS.contains(((Intent) intent).getAction()) && data instanceof BroadcastReceiver.PendingResult) {
                    ((BroadcastReceiver.PendingResult) data).finish(); hit("events:static-receiver"); return null;
                }
            }
            return chain.proceed();
        });
    }
    private void phoneListeners() {
        hook("android.telephony.TelephonyManager", "listen", chain -> {
            if ((Integer) chain.getArg(1) != 0) {
                int value = mode("events"); if (value == Policy.DENY) deny("events");
                if (value == Policy.SOFT) { hit("events:phone-listener"); return null; }
            }
            return chain.proceed();
        });
        hook("android.telephony.TelephonyManager", "registerTelephonyCallback", chain -> {
            int value = mode("events"); if (value == Policy.DENY) deny("events");
            Object callback = null;
            for (Object argument : chain.getArgs()) if (argument instanceof android.telephony.TelephonyCallback) callback = argument;
            if (value == Policy.SOFT && callback != null) {
                phoneCallbacks.virtual(phoneCallbacks, callback); hit("events:phone-callback"); return null;
            }
            Object result = chain.proceed(); phoneCallbacks.real(phoneCallbacks, callback); return result;
        });
        hook("android.telephony.TelephonyManager", "unregisterTelephonyCallback", chain -> {
            if (phoneCallbacks.skipCleanup(phoneCallbacks, chain.getArg(0))) return null;
            return chain.proceed();
        });
    }
    private void location() {
        for (String name : new String[]{"getLastKnownLocation", "requestLocationUpdates", "requestSingleUpdate"}) hook("android.location.LocationManager", name, empty("location", null));
        hook("android.location.LocationManager", "getCurrentLocation", chain -> {
            int value = mode("location"); if (value == Policy.OFF) return chain.proceed(); if (value == Policy.DENY) deny("location");
            Executor executor = null; Consumer<Location> consumer = null; CancellationSignal cancellation = null;
            for (Object argument : chain.getArgs()) {
                if (argument instanceof Executor) executor = (Executor) argument;
                if (argument instanceof Consumer) consumer = (Consumer<Location>) argument;
                if (argument instanceof CancellationSignal) cancellation = (CancellationSignal) argument;
            }
            if (executor == null || consumer == null) { Log.w("SystemHelperGuard", "unsupported current-location callback"); return chain.proceed(); }
            final Executor deliverOn = executor; final Consumer<Location> result = consumer; final CancellationSignal signal = cancellation;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (signal != null && signal.isCanceled()) return;
                try { deliverOn.execute(() -> { if (signal == null || !signal.isCanceled()) result.accept(null); }); }
                catch (java.util.concurrent.RejectedExecutionException rejected) { Log.w("SystemHelperGuard", "location callback executor unavailable"); }
            });
            hit("location:empty-callback"); return null;
        });
    }
    private String alias(Policy policy, String kind, List<Object> arguments) throws Exception {
        String slot = !arguments.isEmpty() && arguments.get(0) instanceof Integer ? arguments.get(0).toString() : "0";
        byte[] hash = MessageDigest.getInstance("SHA-256").digest((policy.seed + ":" + packageName + ":" + kind + ":" + slot).getBytes("UTF-8"));
        if (kind.equals("android_id")) { StringBuilder result = new StringBuilder(); for (int i = 0; i < 8; ++i) result.append(String.format(java.util.Locale.ROOT, "%02x", hash[i] & 255)); return result.toString(); }
        StringBuilder result = new StringBuilder(); int sum = 0;
        for (int i = 0; i < 14; ++i) { int digit = (hash[i] & 255) % 10; result.append(digit); int weighted = i % 2 == 1 ? digit * 2 : digit; sum += weighted / 10 + weighted % 10; }
        return result.append((10 - sum % 10) % 10).toString();
    }
    private void identifiers() {
        for (String name : new String[]{"getDeviceId", "getImei", "getMeid", "getSubscriberId", "getSimSerialNumber", "getLine1Number"}) hook("android.telephony.TelephonyManager", name, chain -> {
            int value = mode("identifiers"); if (value == Policy.DENY) deny("identifiers");
            if (value == Policy.SOFT) { hit("identifiers:substitute"); return name.equals("getImei") || name.equals("getDeviceId") ? alias(reader.get(), "imei", chain.getArgs()) : null; }
            return chain.proceed();
        });
        hook("android.provider.Settings$Secure", "getString", chain -> {
            if ("android_id".equals(chain.getArg(1))) {
                int value = mode("identifiers"); if (value == Policy.DENY) deny("identifiers");
                if (value == Policy.SOFT) { hit("identifiers:android-id"); return alias(reader.get(), "android_id", Collections.emptyList()); }
            }
            return chain.proceed();
        });
    }
    private void records() {
        hook("android.content.ContentResolver", "query", chain -> {
            if (!(chain.getArg(0) instanceof Uri) || !Arrays.asList("com.android.contacts", "call_log", "sms", "mms", "mms-sms").contains(((Uri) chain.getArg(0)).getAuthority())) return chain.proceed();
            int value = mode("records"); if (value == Policy.OFF) return chain.proceed(); if (value == Policy.DENY) deny("records");
            Object[] args = chain.getArgs().toArray();
            for (Object argument : args) if (argument instanceof CancellationSignal) ((CancellationSignal) argument).throwIfCanceled();
            if (args[1] instanceof String[]) { hit("records:empty-cursor"); return new MatrixCursor((String[]) args[1]); }
            // Preserve the provider's default schema: recognized SQL providers
            // receive a false selection, and the returned cursor is replaced.
            if (args.length >= 4 && (args[2] == null || args[2] instanceof String) && (args[3] == null || args[3] instanceof String[])) { args[2] = "0"; args[3] = null; }
            else if (args.length >= 3 && (args[2] == null || args[2] instanceof Bundle)) {
                Bundle query = args[2] == null ? new Bundle() : new Bundle((Bundle) args[2]);
                query.putString("android:query-arg-sql-selection", "0"); query.remove("android:query-arg-sql-selection-args"); args[2] = query;
            } else { Log.w("SystemHelperGuard", "unsupported query signature"); return chain.proceed(); }
            Object response = chain.proceed(args);
            if (!(response instanceof Cursor)) return response;
            Cursor cursor = (Cursor) response; String[] columns;
            try { columns = cursor.getColumnNames(); } finally { cursor.close(); }
            hit("records:empty-default-schema"); return new MatrixCursor(columns);
        });
    }
    private void audio() {
        hook("android.media.AudioRecord", "startRecording", reject("audio"));
        for (String name : new String[]{"prepare", "start"}) hook("android.media.MediaRecorder", name, reject("audio"));
        hook("android.media.AudioRecord", "read", chain -> {
            int value = mode("audio"); if (value == Policy.OFF) return chain.proceed(); if (value == Policy.DENY) deny("audio");
            AudioRecord record = (AudioRecord) chain.getThisObject();
            if (record.getState() != AudioRecord.STATE_INITIALIZED || record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) return chain.proceed();
            List<Object> args = chain.getArgs(); Object buffer = args.get(0); if (buffer == null) return chain.proceed();
            int modeIndex = buffer instanceof ByteBuffer ? 2 : 3;
            if (args.size() > modeIndex && (Integer) args.get(modeIndex) != 0 && (Integer) args.get(modeIndex) != 1) return chain.proceed();
            int count, bytes;
            if (buffer instanceof ByteBuffer) {
                ByteBuffer data = (ByteBuffer) buffer; if (!data.isDirect()) return 0;
                int requested = (Integer) args.get(1); if (requested < 0) return chain.proceed();
                count = Math.min(requested, data.capacity()); ByteBuffer target = data.duplicate(); target.clear();
                for (int i = 0; i < count; ++i) target.put(i, (byte) 0); bytes = count;
            } else {
                int offset = (Integer) args.get(1); count = (Integer) args.get(2);
                if (offset < 0 || count < 0 || count > java.lang.reflect.Array.getLength(buffer) - offset) return chain.proceed();
                if (buffer instanceof byte[]) { Arrays.fill((byte[]) buffer, offset, offset + count, (byte) 0); bytes = count; }
                else if (buffer instanceof short[]) { Arrays.fill((short[]) buffer, offset, offset + count, (short) 0); bytes = count * 2; }
                else if (buffer instanceof float[]) { Arrays.fill((float[]) buffer, offset, offset + count, 0f); bytes = count * 4; }
                else return chain.proceed();
            }
            if (args.size() <= modeIndex || (Integer) args.get(modeIndex) == 0) LockSupport.parkNanos(Math.min(100_000_000L, bytes * 1_000_000_000L / Math.max(1, record.getSampleRate() * record.getChannelCount() * 2)));
            hit("audio:silent-read"); return count;
        });
    }
}
