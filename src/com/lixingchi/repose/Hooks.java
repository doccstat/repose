package com.lixingchi.repose;

import android.os.SystemClock;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import android.os.Handler;
import android.os.Looper;
import dalvik.system.InMemoryDexClassLoader;
import org.json.JSONObject;

/** SystemUI only: no hooks in arbitrary apps' credential or biometric prompts. */
public final class Hooks implements IXposedHookLoadPackage {
    private final ExecutorService events = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128));
    private volatile String boot;
    private final JSONObject capabilities = new JSONObject();
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) throws Throwable {
        if (!p.packageName.equals("com.android.systemui") || !p.processName.equals("com.android.systemui")) return;
        Class<?> utils = XposedHelpers.findClassIfExists("com.android.internal.widget.LockPatternUtils", p.classLoader);
        boolean credential = false, panic = false;
        if (utils != null) {
            int success = XposedBridge.hookAllMethods(utils, "reportSuccessfulPasswordAttempt", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    if (call.hasThrowable()) return;
                    int user = (Integer)call.args[0];
                    event("credential", user); event("password_success", user);
                }
            }).size();
            int failure = XposedBridge.hookAllMethods(utils, "reportFailedPasswordAttempt", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    if (!call.hasThrowable()) event("password_failure", (Integer)call.args[0]);
                }
            }).size();
            credential = success > 0 && failure > 0;
            for (Method method : utils.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!(method.getName().equals("checkCredential") || method.getName().equals("verifyCredential"))
                        || types.length != 3 || !types[0].getName().equals("com.android.internal.widget.LockscreenCredential") || types[1] != int.class) continue;
                final Object denied;
                try {
                    types[0].getMethod("hasInvalidChars");
                    denied = credentialFailure(method.getReturnType());
                } catch (ReflectiveOperationException unsupported) { continue; }
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam call) {
                        try {
                            Object credential = call.args[0];
                            if ((Boolean)XposedHelpers.callMethod(credential, "hasInvalidChars")) return;
                            boolean pin = (Boolean)XposedHelpers.callMethod(credential, "isPin");
                            boolean password = (Boolean)XposedHelpers.callMethod(credential, "isPassword");
                            if (!pin && !password) return;
                            byte[] raw = (byte[])XposedHelpers.callMethod(credential, "getCredential");
                            JSONObject request = new JSONObject().put("op", "panic").put("user", call.args[1])
                                    .put("type", pin ? "pin" : "password").put("value", new String(raw, StandardCharsets.UTF_8));
                            if (!Wire.call(request).optBoolean("matched")) return;
                            call.setResult(denied);
                        } catch (Exception unavailable) { /* Preserve Android authentication when the engine cannot be reached. */ }
                    }
                });
                panic = true;
            }
        }
        capabilities.put("credential", credential).put("panic", panic);
        capabilities.put("fingerprint", false).put("face", false);
        installBiometrics(p.classLoader);
        // Reannounce after engine/module service replay; no user-authentication event is synthesized.
        ScheduledExecutorService health = Executors.newSingleThreadScheduledExecutor();
        health.scheduleWithFixedDelay(() -> {
            try {
                boot = Wire.call(new JSONObject().put("op", "epoch")).getString("boot");
                JSONObject report;
                synchronized (capabilities) { report = new JSONObject(capabilities.toString()); }
                Wire.call(report.put("op", "capabilities"));
            }
            catch (Exception ignored) { }
        }, 0, 30, TimeUnit.SECONDS);
    }
    static Object credentialFailure(Class<?> result) throws ReflectiveOperationException {
        return result == boolean.class ? false : result.getMethod("fromError").invoke(null);
    }
    private void installBiometrics(ClassLoader loader) {
        try {
            Class<?> monitor = Class.forName("com.android.keyguard.KeyguardUpdateMonitor", false, loader);
            Class<?> callback = Class.forName("com.android.keyguard.KeyguardUpdateMonitorCallback", false, loader);
            Class<?> source = Class.forName("android.hardware.biometrics.BiometricSourceType", false, loader);
            Method failed = callback.getMethod("onBiometricAuthFailed", source);
            if (Modifier.isFinal(callback.getModifiers()) || Modifier.isFinal(failed.getModifiers())
                    || Modifier.isStatic(failed.getModifiers()) || failed.getReturnType() != void.class) return;
            for (Method method : callback.getMethods())
                if (Modifier.isAbstract(method.getModifiers()) && !method.equals(failed)) return;
            Method register = monitor.getMethod("registerCallback", callback);
            Method visible = monitor.getMethod("isKeyguardVisible");
            Method allowed = monitor.getMethod("isUnlockingWithBiometricAllowed", boolean.class);
            Map<String, Boolean> supported = new HashMap<>();
            for (String kind : new String[]{"fingerprint", "face"}) {
                try {
                    String name = kind.equals("fingerprint") ? "Fingerprint" : "Face";
                    XposedBridge.hookMethod(monitor.getMethod("on" + name + "Authenticated", int.class, boolean.class), new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam call) {
                            try {
                                if (!call.hasThrowable() && (Boolean)allowed.invoke(call.thisObject, call.args[1])) event("biometric", (Integer)call.args[0]);
                            } catch (Exception unsupported) { }
                        }
                    });
                    supported.put(kind, true);
                } catch (NoSuchMethodException unsupported) { supported.put(kind, false); }
            }
            Class<?> observer;
            try (InputStream dex = Hooks.class.getClassLoader().getResourceAsStream("assets/keyguard-observer/classes.dex")) {
                if (dex == null) return;
                ClassLoader bridge = new InMemoryDexClassLoader(ByteBuffer.wrap(dex.readAllBytes()), loader);
                observer = bridge.loadClass("com.lixingchi.repose.observer.KeyguardObserver");
            }
            Map<Object, Object> observers = new WeakHashMap<>();
            Handler main = new Handler(Looper.getMainLooper());
            XposedBridge.hookAllConstructors(monitor, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    if (call.hasThrowable()) return;
                    Object instance = call.thisObject;
                    main.post(() -> {
                        if (observers.containsKey(instance)) return;
                        WeakReference<Object> owner = new WeakReference<>(instance);
                        Consumer<String> failure = kind -> {
                            try {
                                Object live = owner.get();
                                if (live == null || !(Boolean)visible.invoke(live)) return;
                                String eventKind = switch (kind) { case "FINGERPRINT" -> "fingerprint"; case "FACE" -> "face"; default -> ""; };
                                if (eventKind.isEmpty()) return;
                                int user = (Integer)XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityManager"), "getCurrentUser");
                                event(eventKind, user);
                            } catch (Exception unavailable) { }
                        };
                        try {
                            Object listener = ObserverFactory.create(observer, failure);
                            register.invoke(instance, listener);
                            observers.put(instance, listener);
                            synchronized (capabilities) {
                                for (String kind : supported.keySet()) capabilities.put(kind, supported.get(kind));
                            }
                        } catch (Throwable unsupported) { /* Keep an unsupported observer out of SystemUI. */ }
                    });
                }
            });
        } catch (Throwable unsupported) { /* Missing callback contracts leave the capabilities disabled. */ }
    }
    private void event(String kind, int user) {
        if (boot == null) return;
        final String eventBoot = boot;
        final long stamp = SystemClock.elapsedRealtime(), sequence = SystemClock.elapsedRealtimeNanos();
        try {
            events.execute(() -> {
                try {
                    Wire.call(new JSONObject().put("op", "hook").put("kind", kind).put("user", user)
                        .put("stamp", stamp).put("sequence", sequence).put("boot", eventBoot));
                } catch (Exception ignored) { }
            });
        } catch (RejectedExecutionException ignored) { }
    }
}
