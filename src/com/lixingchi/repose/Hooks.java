package com.lixingchi.repose;

import android.os.SystemClock;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.lang.reflect.Method;
import java.util.concurrent.*;
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
                try { types[0].getMethod("hasInvalidChars"); }
                catch (NoSuchMethodException unsupported) { continue; }
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
                            if (method.getReturnType() == boolean.class) call.setResult(false);
                            else call.setResult(XposedHelpers.getStaticObjectField(method.getReturnType(), "ERROR"));
                        } catch (Exception unavailable) { /* Preserve Android authentication when the engine cannot be reached. */ }
                    }
                });
                panic = true;
            }
        }
        capabilities.put("credential", credential).put("panic", panic);
        Class<?> monitor = XposedHelpers.findClassIfExists("com.android.keyguard.KeyguardUpdateMonitor", p.classLoader);
        for (String kind : new String[]{"fingerprint", "face"}) {
            String name = kind.equals("fingerprint") ? "Fingerprint" : "Face";
            boolean supported = false;
            if (monitor != null) {
                int success = XposedBridge.hookAllMethods(monitor, "on" + name + "Authenticated", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam call) {
                        try {
                            if (!call.hasThrowable() && (Boolean)XposedHelpers.callMethod(call.thisObject,
                                    "isUnlockingWithBiometricAllowed", call.args[1])) event("biometric", (Integer)call.args[0]);
                        } catch (Exception unsupported) { }
                    }
                }).size();
                int failure = XposedBridge.hookAllMethods(monitor, "handle" + name + "AuthFailed", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam call) {
                        try {
                            if (call.hasThrowable() || !(Boolean)XposedHelpers.callMethod(call.thisObject, "isKeyguardVisible")) return;
                            int user = (Integer)XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityManager"), "getCurrentUser");
                            event(kind, user);
                        } catch (Exception ignored) { }
                    }
                }).size();
                supported = success > 0 && failure > 0;
            }
            capabilities.put(kind, supported);
        }
        // Reannounce after engine/module service replay; no user-authentication event is synthesized.
        ScheduledExecutorService health = Executors.newSingleThreadScheduledExecutor();
        health.scheduleWithFixedDelay(() -> {
            try {
                boot = Wire.call(new JSONObject().put("op", "epoch")).getString("boot");
                Wire.call(new JSONObject(capabilities.toString()).put("op", "capabilities"));
            }
            catch (Exception ignored) { }
        }, 0, 30, TimeUnit.SECONDS);
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
