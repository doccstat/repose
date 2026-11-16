package com.lixingchi.repose;

import android.content.*;
import android.content.pm.*;
import android.net.*;
import android.os.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;
import org.json.*;

public final class Daemon {
    static final File HOME = new File("/data/adb/repose");
    private static native int alarmOpen();
    private static native void alarmSet(int fd, long elapsedMillis);
    private static native void alarmWait(int fd);
    final Context context;
    final AndroidDevice device;
    final Engine engine;
    final int alarm;
    final String boot;
    final byte[] apkDigest;
    final ExecutorService actions = Executors.newSingleThreadExecutor();
    final ThreadPoolExecutor clients = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
    final ScheduledExecutorService inbox = Executors.newSingleThreadScheduledExecutor();
    final PowerManager.WakeLock wake;
    long nextPoll;
    boolean polling;
    int appUid = -1;
    long appUpdated = -1;
    int hookUid = -1;
    volatile JSONObject hooks = new JSONObject();
    volatile long hookSeen = -1;
    JSONObject hookStatus() {
        return hookSeen >= 0 && device.elapsed() - hookSeen <= 90000 ? hooks : new JSONObject();
    }
    Daemon(Context context, File apk) throws Exception {
        this.context = context; device = new AndroidDevice(context);
        boot = new String(Files.readAllBytes(new File("/proc/sys/kernel/random/boot_id").toPath()), StandardCharsets.US_ASCII).trim();
        apkDigest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(apk.toPath()));
        StateFile file = new StateFile(new File(HOME, "state.json").toPath());
        engine = new Engine(file.read(), boot, file, device, task -> actions.execute(() -> {
            wakeAcquire();
            try { task.run(); } finally { wakeRelease(); reschedule(); }
        }));
        alarm = alarmOpen();
        wake = context.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Repose:engine");
        wake.setReferenceCounted(true);
    }
    void wakeAcquire() { wake.acquire(40000); }
    void wakeRelease() { if (wake.isHeld()) wake.release(); }
    private synchronized boolean app(int uid) throws Exception {
        PackageInfo p = context.getPackageManager().getPackageInfo("com.lixingchi.repose", 0);
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (uid % 100000 != p.applicationInfo.uid % 100000 || packages == null
                || !Arrays.asList(packages).contains("com.lixingchi.repose")) return false;
        if (appUid != uid || appUpdated != p.lastUpdateTime) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(p.applicationInfo.sourceDir).toPath()));
            if (!MessageDigest.isEqual(apkDigest, digest)) throw new SecurityException("apk_module_mismatch");
            appUid = uid; appUpdated = p.lastUpdateTime;
        }
        return true;
    }
    private boolean hook(int uid) throws Exception {
        if (hookUid < 0) hookUid = context.getPackageManager().getApplicationInfo("com.android.systemui", 0).uid;
        return uid == hookUid || uid == 1000;
    }
    JSONObject handle(int uid, JSONObject request) throws Exception {
        String op = request.getString("op");
        boolean root = uid == 0;
        if (op.equals("epoch")) {
            if (!root && !hook(uid) && !app(uid)) throw new SecurityException("forbidden");
            return new JSONObject().put("ok", true).put("boot", boot);
        }
        if (op.equals("hook") || op.equals("panic") || op.equals("capabilities")) {
            if (!root && !hook(uid)) throw new SecurityException("forbidden");
            if (op.equals("capabilities")) {
                synchronized (this) {
                    JSONObject reported = new JSONObject();
                    for (String key : new String[]{"credential", "panic", "fingerprint", "face"}) reported.put(key, request.optBoolean(key));
                    hooks = reported;
                    hookSeen = device.elapsed();
                }
            } else if (op.equals("panic")) {
                return new JSONObject().put("ok", true).put("matched", engine.panic(request.getString("value"), request.getInt("user"), request.getString("type")));
            } else event(request, "hook");
        } else {
            if (!root && !app(uid)) throw new SecurityException("forbidden");
            switch (op) {
                case "status":
                    return engine.status().put("ok", true).put("users", device.inventory()).put("hooks", hookStatus())
                        .put("version", "1.0.0").put("sdk", Build.VERSION.SDK_INT);
                case "diagnostics":
                    JSONObject status = engine.status();
                    JSONObject c = status.getJSONObject("config");
                    c.put("device_id", "[redacted]").put("inbox_url", "[redacted]");
                    return status.put("ok", true).put("hooks", hookStatus()).put("version", "1.0.0")
                        .put("sdk", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS[0])
                        .put("android", Build.VERSION.RELEASE).put("security_patch", Build.VERSION.SECURITY_PATCH)
                        .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL);
                case "configure":
                    requireUnlocked(uid);
                    JSONObject config = request.getJSONObject("config");
                    Settings proposed = new Settings(config);
                    JSONObject hooks = hookStatus();
                    if ((proposed.b("panic_enabled") || !proposed.s("unlock_scope").equals("any")
                            || proposed.s("fail_source").equals("hook") && proposed.b("fail_enabled")) && !hooks.optBoolean("credential"))
                        throw new IllegalStateException("hook_unavailable");
                    if (proposed.b("panic_enabled") && !hooks.optBoolean("panic")) throw new IllegalStateException("hook_unavailable");
                    for (String kind : new String[]{"fingerprint", "face"})
                        if (proposed.b("fail_enabled") && Policy.biometricAccepted(proposed, kind) && !hooks.optBoolean(kind))
                            throw new IllegalStateException("hook_unavailable");
                    engine.configure(config);
                    synchronized (this) { nextPoll = 0; }
                    break;
                case "snooze": requireUnlocked(uid); engine.snooze(request.getInt("minutes")); break;
                case "skip": requireUnlocked(uid); engine.skip(); break;
                case "resume": requireUnlocked(uid); engine.resume(); break;
                case "acknowledge": requireUnlocked(uid); engine.acknowledge(); break;
                case "admin":
                    long serial = request.getLong("serial");
                    JSONArray users = device.inventory();
                    int user = -1;
                    for (int i = 0; i < users.length(); i++) {
                        int[] pair = Settings.identity(users.getJSONObject(i).getString("identity"));
                        if (pair[1] == serial) user = pair[0];
                    }
                    if (user < 0) throw new IllegalArgumentException("unknown_user");
                    event(request.put("user", user), "admin"); break;
                case "sms": engine.remote("sms", request.getString("message")); break;
                default: throw new IllegalArgumentException("unknown_operation");
            }
        }
        reschedule();
        return new JSONObject().put("ok", true);
    }
    void requireUnlocked(int uid) throws Exception {
        int user = uid / 100000;
        if (uid == 0) user = (Integer)Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null);
        Object info = device.info(user);
        if (info == null || device.locked(user + ":" + AndroidDevice.integer(info, "serialNumber"))) throw new SecurityException("unlock_required");
    }
    void event(JSONObject r, String provider) throws Exception {
        String kind = r.getString("kind");
        if (!Arrays.asList("password_success", "password_failure", "credential", "biometric", "fingerprint", "face").contains(kind))
            throw new IllegalArgumentException("invalid_event");
        engine.event(kind, r.getInt("user"), r.getLong("stamp"), r.getLong("sequence"), r.getString("boot"), provider, r.optInt("counter", -1));
    }
    synchronized void reschedule() {
        try {
            long now = device.elapsed(), next = engine.tick();
            if (!engine.config.s("inbox_mode").equals("off")) {
                if (now >= nextPoll && !polling) { polling = true; inbox.execute(this::poll); }
                if (!polling) next = Math.min(next, nextPoll);
            }
            alarmSet(alarm, next == Long.MAX_VALUE ? -1 : Math.max(now + 100, next));
        } catch (Exception error) {
            record("engine_" + error.getClass().getSimpleName());
            // A failed capability read never becomes an implicit reboot; retry without a tight loop.
            alarmSet(alarm, device.elapsed() + 60000);
        }
    }
    void poll() {
        wake.acquire(40000);
        HttpsURLConnection connection = null;
        try {
            String url, token;
            synchronized (engine) { url = engine.config.s("inbox_url"); token = engine.secret("inbox_token"); }
            connection = (HttpsURLConnection)new URL(url).openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "text/plain"); connection.setUseCaches(false);
            int status = connection.getResponseCode();
            if (status == 200) {
                try (InputStream input = connection.getInputStream()) {
                    byte[] bytes = input.readNBytes(4097);
                    if (bytes.length > 4096) throw new IOException("command_too_large");
                    engine.remote("inbox", new String(bytes, StandardCharsets.UTF_8));
                }
            } else if (status != 204) record("inbox_http_" + status);
        } catch (Exception error) { record("inbox_" + error.getClass().getSimpleName()); }
        finally {
            if (connection != null) connection.disconnect();
            if (wake.isHeld()) wake.release();
            synchronized (this) { polling = false; nextPoll = device.elapsed() + engine.config.n("inbox_interval") * 1000L; }
            reschedule();
        }
    }
    void record(String code) {
        try { engine.note(code); }
        catch (Exception error) { System.err.println("Repose: state_write_failed"); }
    }
    void serve() throws Exception {
        IntentFilter filter = new IntentFilter();
        for (String action : new String[]{Intent.ACTION_USER_PRESENT, Intent.ACTION_USER_UNLOCKED, Intent.ACTION_SCREEN_OFF, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED}) filter.addAction(action);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                try {
                    if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                        int user = (Integer)BroadcastReceiver.class.getMethod("getSendingUserId").invoke(this);
                        engine.presence(user);
                    }
                    reschedule();
                } catch (Exception error) { record("event_" + error.getClass().getSimpleName()); }
            }
        };
        Context.class.getMethod("registerReceiverAsUser", BroadcastReceiver.class, UserHandle.class, IntentFilter.class, String.class, Handler.class)
            .invoke(context, receiver, UserHandle.class.getField("ALL").get(null), filter, null, new Handler(Looper.getMainLooper()));
        IBinder activityBinder = (IBinder)Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "activity");
        activityBinder.linkToDeath(() -> System.exit(75), 0);
        new Thread(() -> {
            while (true) {
                alarmWait(alarm); wake.acquire(40000);
                try { reschedule(); } finally { if (wake.isHeld()) wake.release(); }
            }
        }, "Repose-alarm").start();
        new Thread(() -> {
            try (LocalServerSocket server = new LocalServerSocket(Wire.SOCKET)) {
                while (true) {
                    LocalSocket socket = server.accept();
                    try { clients.execute(() -> client(socket)); }
                    catch (RejectedExecutionException busy) { socket.close(); }
                }
            } catch (Exception error) { System.err.println("Repose: socket_unavailable"); System.exit(1); }
        }, "Repose-ipc").start();
        record("engine_started"); reschedule();
    }
    void client(LocalSocket socket) {
        try (LocalSocket accepted = socket) {
            accepted.setSoTimeout(5000);
            int uid = accepted.getPeerCredentials().getUid();
            JSONObject reply;
            try {
                if (uid != 0 && !hook(uid) && !app(uid)) return;
                reply = handle(uid, Wire.read(accepted.getInputStream()));
            } catch (Exception error) {
                String code = error.getMessage();
                if (code == null || !code.matches("[a-z][a-z0-9_]{0,80}")) code = "engine_" + error.getClass().getSimpleName();
                record("ipc_" + code);
                reply = new JSONObject().put("ok", false).put("error", code);
            }
            Wire.write(accepted.getOutputStream(), reply);
        } catch (Exception ignored) { /* Untrusted or disconnected peers cannot modify state. */ }
    }
    public static void main(String[] args) {
        try {
            if (android.os.Process.myUid() != 0 || args.length != 2) throw new SecurityException("root_required");
            System.load(args[1]);
            try (RandomAccessFile lockFile = new RandomAccessFile(new File(HOME, "lock"), "rw"); FileChannel channel = lockFile.getChannel()) {
                FileLock lock = channel.tryLock();
                if (lock == null) return;
                Looper.prepareMainLooper();
                Class<?> activity = Class.forName("android.app.ActivityThread");
                Object thread = activity.getMethod("systemMain").invoke(null);
                Context context = (Context)activity.getMethod("getSystemContext").invoke(thread);
                new Daemon(context, new File(args[0])).serve();
                Looper.loop();
                lock.release();
            }
        } catch (Throwable error) {
            Throwable cause = error;
            while (cause.getCause() != null) cause = cause.getCause();
            String code = cause.getMessage();
            if (code == null || !code.matches("[a-z][a-z0-9_]{0,80}")) code = cause.getClass().getSimpleName();
            System.err.println("Repose startup: " + code); System.exit(1);
        }
    }
}
