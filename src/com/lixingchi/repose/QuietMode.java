package com.lixingchi.repose;

import android.system.Os;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** A short-lived Binder caller: the android package belongs to UID 1000, not root. */
public final class QuietMode {
    static void request(String identity, String parent) throws Exception {
        Process child = new ProcessBuilder("/system/bin/app_process", "/system/bin", QuietMode.class.getName(), identity, parent)
                .redirectError(new File("/dev/null")).start();
        await(child);
    }
    static void await(Process child) throws Exception {
        try {
            if (!child.waitFor(10, TimeUnit.SECONDS)) throw new IllegalStateException("profile_pause_helper_timeout");
            if (child.exitValue() != 0) {
                String code = new String(child.getInputStream().readNBytes(128), StandardCharsets.US_ASCII);
                throw new IllegalStateException(code.matches("[a-z][a-z0-9_]{0,80}") ? code : "profile_pause_helper_failed");
            }
        } finally {
            child.destroyForcibly();
            child.getInputStream().close(); child.getErrorStream().close(); child.getOutputStream().close();
        }
    }
    @FunctionalInterface interface Identity { void becomeSystem() throws Exception; }
    static void enable(AndroidDevice device, String identity, String parent, Identity change) throws Exception {
        device.verifyProfile(identity, parent);
        change.becomeSystem();
        device.enableQuietMode(identity, parent);
    }
    public static void main(String[] args) {
        try {
            if (android.os.Process.myUid() != 0 || args.length != 2) throw new SecurityException("root_required");
            enable(new AndroidDevice(null), args[0], args[1], () -> { Os.setgid(1000); Os.setuid(1000); });
        } catch (Throwable error) {
            Throwable cause = error;
            while (cause.getCause() != null) cause = cause.getCause();
            String code = cause.getMessage();
            System.out.print(code != null && code.matches("[a-z][a-z0-9_]{0,80}") ? code : "profile_pause_helper_failed");
            System.exit(1);
        }
    }
}
