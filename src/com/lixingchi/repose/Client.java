package com.lixingchi.repose;

import android.content.Context;
import android.os.SystemClock;
import java.io.*;
import java.util.concurrent.*;
import org.json.JSONObject;

public final class Client {
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    public static JSONObject call(JSONObject request) throws Exception { return Wire.call(request); }
    static void start() throws Exception {
        Process process;
        try { process = new ProcessBuilder("su", "-c", "/system/bin/sh /data/adb/modules/repose/service.sh").redirectErrorStream(true).start(); }
        catch (IOException missing) {
            if (missing.getMessage() == null || !missing.getMessage().contains("error=2,")) throw missing;
            process = new ProcessBuilder("/system/bin/su", "-c", "/system/bin/sh /data/adb/modules/repose/service.sh").redirectErrorStream(true).start();
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("root_timeout"); }
        if (process.exitValue() != 0) throw new IOException("root_denied");
        long until = SystemClock.elapsedRealtime() + 10000;
        Exception last = new IOException("engine_unavailable");
        while (SystemClock.elapsedRealtime() < until) {
            try { call(new JSONObject().put("op", "status")); return; }
            catch (Exception error) { last = error; Thread.sleep(250); }
        }
        throw last;
    }
    static void failure(Context context, String code) {
        context.createDeviceProtectedStorageContext().getSharedPreferences("receiver_status", Context.MODE_PRIVATE)
                .edit().putString("error", code).apply();
    }
    private Client() { }
}
