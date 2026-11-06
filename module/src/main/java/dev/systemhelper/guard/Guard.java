package dev.systemhelper.guard;

import android.app.Application;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface;

public final class Guard extends XposedModule {
    private String processName;
    private static volatile boolean nativeReady;
    private static int nativeCapabilities;
    private static int previousSoft = -1, previousDeny = -1;
    private static native void nativeConfigure(int soft, int deny);
    private static native int nativeStart(int soft, int deny);
    private static native int nativeNetworkFilter();

    static synchronized void syncNative(Policy policy) {
        if (!nativeReady) return;
        int soft = nativeMask(policy, Policy.SOFT), deny = nativeMask(policy, Policy.DENY);
        if (soft != previousSoft || deny != previousDeny) {
            try {
                // Configuration updates publish data only; never patch running code.
                nativeConfigure(soft, deny);
                int missing = (soft | deny) & ~nativeCapabilities;
                if (missing != 0) Log.w("SystemHelperGuard", "native-capabilities-unavailable-or-restart-required mask=" + missing);
                previousSoft = soft; previousDeny = deny;
            } catch (LinkageError | RuntimeException error) {
                nativeReady = false; Log.e("SystemHelperGuard", "native-policy-update-failed", error);
            }
        }
    }
    private static int nativeMask(Policy policy, int mode) {
        int mask = 0;
        String[] keys = {"sensors", "audio", "network"};
        for (int i = 0; i < keys.length; ++i) {
            if (policy.mode(keys[i]) == mode) mask |= 1 << i;
        }
        return mask;
    }
    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        Log.i("SystemHelperGuard", "module-loaded modern-api=" + getApiVersion() + " process=" + processName);
    }
    @Override public void onPackageLoaded(PackageLoadedParam param) {
        String name = param.getPackageName();
        if (!param.isFirstPackage() || name.equals("dev.systemhelper.guard") ||
                name.equals("android") || name.equals("com.android.systemui") || name.equals("com.android.shell")) return;
        PolicyReader reader = new PolicyReader(getRemotePreferences("policies"), name);
        Policy initial = reader.get();
        if (!initial.enabled) { Log.i("SystemHelperGuard", "policy-inactive package=" + name); return; }
        new PrivacyHooks(this, reader, name).install();
        boolean needsNative = (nativeMask(initial, Policy.SOFT) | nativeMask(initial, Policy.DENY)) != 0 || initial.kernelNetwork;
        if (needsNative) try {
            java.lang.reflect.Method create = android.app.Instrumentation.class.getDeclaredMethod("callApplicationOnCreate", Application.class);
            hook(create).setPriority(1000).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(new XposedInterface.Hooker() {
                private boolean applied;
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (!applied) {
                        applied = true;
                        Policy startup = reader.get();
                        if (!startup.enabled) return chain.proceed();
                        try {
                            System.loadLibrary("systemhelperguard");
                            int result = nativeStart(nativeMask(startup, Policy.SOFT), nativeMask(startup, Policy.DENY));
                            if (result < 0) throw new IllegalStateException("native startup failed: " + result);
                            nativeCapabilities = result; nativeReady = true; syncNative(reader.get());
                            Log.i("SystemHelperGuard", "native-ready stage=before-application-onCreate package=" + name);
                        } catch (LinkageError | RuntimeException error) { Log.e("SystemHelperGuard", "native-unavailable package=" + name, error); }
                        if (nativeReady && reader.get().enabled && reader.get().kernelNetwork) {
                            int result = nativeNetworkFilter();
                            Log.println(result == 0 ? Log.INFO : Log.ERROR, "SystemHelperGuard", "kernel-network package=" + name + " result=" + result);
                        }
                    }
                    return chain.proceed();
                }
            });
        } catch (ReflectiveOperationException error) { Log.e("SystemHelperGuard", "kernel-startup-adapter-unavailable", error); }
        Log.i("SystemHelperGuard", "policy-active package=" + name + " process=" + processName + " kernel-next-start=" + initial.kernelNetwork);
    }
}
