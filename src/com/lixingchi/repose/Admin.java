package com.lixingchi.repose;

import android.app.admin.*;
import android.content.*;
import android.os.*;
import java.nio.file.*;
import org.json.JSONObject;

public final class Admin extends DeviceAdminReceiver {
    @Override public void onPasswordFailed(Context context, Intent intent, UserHandle user) { report(context, user, "password_failure"); }
    @Override public void onPasswordSucceeded(Context context, Intent intent, UserHandle user) { report(context, user, "password_success"); }
    private void report(Context context, UserHandle user, String kind) {
        long stamp = SystemClock.elapsedRealtime(), sequence = SystemClock.elapsedRealtimeNanos();
        PendingResult pending = goAsync();
        Client.IO.execute(() -> {
            try {
                long serial = context.getSystemService(UserManager.class).getSerialNumberForUser(user);
                if (serial < 0) throw new IllegalStateException("unknown_user");
                int attempts = kind.equals("password_failure") ? context.getSystemService(DevicePolicyManager.class).getCurrentFailedPasswordAttempts() : 0;
                String boot = Client.call(new JSONObject().put("op", "epoch")).getString("boot");
                Client.call(new JSONObject().put("op", "admin").put("kind", kind).put("serial", serial)
                        .put("stamp", stamp).put("sequence", sequence).put("boot", boot).put("counter", attempts));
            } catch (Exception error) { Client.failure(context, "admin_" + error.getClass().getSimpleName()); }
            finally { pending.finish(); }
        });
    }
}
