package com.lixingchi.repose;

import android.content.Context;
import android.os.*;
import java.lang.reflect.*;
import java.time.Instant;
import java.util.List;
import org.json.*;

/** Named Binder contracts, not ROM-specific shell-output parsing or transaction numbers. */
public class AndroidDevice implements Engine.Device {
    final Context context;
    AndroidDevice(Context context) { this.context = context; }
    static Object service(String name, String api) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
        if (binder == null || !binder.isBinderAlive()) throw new IllegalStateException("service_unavailable");
        return Class.forName(api + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
    }
    static Object call(Object target, String api, String name, Class<?>[] types, Object... args) throws Exception {
        return Class.forName(api).getMethod(name, types).invoke(target, args);
    }
    Object users() throws Exception { return service("user", "android.os.IUserManager"); }
    Object userCall(String name, Class<?>[] types, Object... args) throws Exception { return call(users(), "android.os.IUserManager", name, types, args); }
    Object info(int id) throws Exception { return userCall("getUserInfo", new Class<?>[]{int.class}, id); }
    List<?> allUsers() throws Exception {
        try { return (List<?>)userCall("getUsers", new Class<?>[]{boolean.class, boolean.class, boolean.class}, true, true, true); }
        catch (NoSuchMethodException newerContract) {
            return (List<?>)userCall("getUsers", new Class<?>[]{boolean.class}, true);
        }
    }
    static int integer(Object info, String field) throws Exception { return info.getClass().getField(field).getInt(info); }
    static String type(Object info) throws Exception { return (String)info.getClass().getField("userType").get(info); }
    static boolean flag(Object info, String name) throws Exception { return (Boolean)info.getClass().getMethod(name).invoke(info); }
    Object verify(String identity) throws Exception {
        int[] pair = Settings.identity(identity);
        Object info = info(pair[0]);
        if (info == null || integer(info, "id") != pair[0] || integer(info, "serialNumber") != pair[1]
                || flag(info, "isEphemeral")) throw new IllegalStateException("identity_changed");
        return info;
    }
    void verifyProfile(String profile, String parent) throws Exception {
        Object info = verify(profile); verify(parent);
        int id = Settings.identity(profile)[0];
        Object actualParent = userCall("getProfileParent", new Class<?>[]{int.class}, id);
        if (!type(info).equals("android.os.usertype.profile.MANAGED") || actualParent == null
                || integer(actualParent, "id") != Settings.identity(parent)[0]
                || integer(actualParent, "serialNumber") != Settings.identity(parent)[1])
            throw new IllegalStateException("invalid_profile");
    }
    @Override public void validate(Settings c) throws Exception {
        Object user = verify(c.s("user"));
        if (type(user).startsWith("android.os.usertype.profile.")) throw new IllegalArgumentException("invalid_user");
        verify(c.s("failure_user"));
        if (!c.s("profile").equals("none")) verifyProfile(c.s("profile"), c.s("user"));
        if (c.b("fail_enabled") && c.s("fail_source").equals("admin")) {
            String api = "android.app.admin.IDevicePolicyManager";
            android.content.ComponentName admin = new android.content.ComponentName("com.lixingchi.repose", "com.lixingchi.repose.Admin");
            boolean active = (Boolean)call(service("device_policy", api), api, "isAdminActive",
                    new Class<?>[]{android.content.ComponentName.class, int.class}, admin, Settings.identity(c.s("failure_user"))[0]);
            if (!active) throw new IllegalStateException("admin_required");
        }
    }
    @Override public long elapsed() { return SystemClock.elapsedRealtime(); }
    @Override public Instant wall() { return Instant.now(); }
    @Override public boolean unlocked(String identity) throws Exception {
        verify(identity);
        return (Boolean)userCall("isUserUnlocked", new Class<?>[]{int.class}, Settings.identity(identity)[0]);
    }
    @Override public boolean bfu() throws Exception {
        for (Object user : allUsers())
            if ((Boolean)userCall("isUserUnlocked", new Class<?>[]{int.class}, integer(user, "id"))) return false;
        return true;
    }
    @Override public boolean locked(String identity) throws Exception {
        verify(identity);
        String api = "android.app.trust.ITrustManager";
        Object trust = service("trust", api);
        try { return (Boolean)call(trust, api, "isDeviceLocked", new Class<?>[]{int.class, int.class}, Settings.identity(identity)[0], 0); }
        catch (NoSuchMethodException oldApi) {
            return (Boolean)call(trust, api, "isDeviceLocked", new Class<?>[]{int.class}, Settings.identity(identity)[0]);
        }
    }
    void enableQuietMode(String identity, String parent) throws Exception {
        verifyProfile(identity, parent);
        Object result = userCall("requestQuietModeEnabled", new Class<?>[]{String.class, boolean.class, int.class, android.content.IntentSender.class, int.class},
                "android", true, Settings.identity(identity)[0], null, 0);
        if (!Boolean.TRUE.equals(result)) throw new IllegalStateException("profile_pause_rejected");
    }
    void requestQuietMode(String identity, String parent) throws Exception { QuietMode.request(identity, parent); }
    @Override public void pause(String identity, String parent) throws Exception {
        verifyProfile(identity, parent);
        int id = Settings.identity(identity)[0];
        boolean quiet = (Boolean)userCall("isQuietModeEnabled", new Class<?>[]{int.class}, id);
        if (!quiet) requestQuietMode(identity, parent);
        long deadline = elapsed() + 20000;
        while (elapsed() < deadline) {
            verifyProfile(identity, parent);
            boolean enabled = (Boolean)userCall("isQuietModeEnabled", new Class<?>[]{int.class}, id);
            String api = "android.app.IActivityManager";
            boolean running = (Boolean)call(service("activity", api), api, "isUserRunning", new Class<?>[]{int.class, int.class}, id, 0);
            if (enabled && !running) return;
            Thread.sleep(200);
        }
        throw new IllegalStateException("profile_pause_timeout");
    }
    // This entry runs in the UID-0 daemon, never in the ordinary application process.
    @android.annotation.SuppressLint("MissingPermission")
    @Override public void reboot() {
        context.getSystemService(PowerManager.class).reboot(null);
    }
    JSONArray inventory() throws Exception {
        JSONArray result = new JSONArray();
        for (Object user : allUsers()) {
            if (flag(user, "isEphemeral")) continue;
            int id = integer(user, "id");
            Object parent = userCall("getProfileParent", new Class<?>[]{int.class}, id);
            result.put(new JSONObject().put("identity", id + ":" + integer(user, "serialNumber"))
                    .put("type", type(user)).put("name", user.getClass().getField("name").get(user))
                    .put("parent", parent == null ? "none" : integer(parent, "id") + ":" + integer(parent, "serialNumber")));
        }
        return result;
    }
}
