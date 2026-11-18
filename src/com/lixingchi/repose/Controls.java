package com.lixingchi.repose;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.service.quicksettings.*;
import org.json.JSONObject;

public final class Controls extends TileService {
    @Override public void onStartListening() {
        Tile tile = getQsTile();
        if (tile != null) { tile.setState(Tile.STATE_INACTIVE); tile.setLabel(getString(R.string.app_name)); tile.updateTile(); }
    }
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // API 33 has only the Intent overload.
    @Override public void onClick() {
        unlockAndRun(() -> {
            Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("control", "snooze");
            if (android.os.Build.VERSION.SDK_INT >= 34)
                startActivityAndCollapse(PendingIntent.getActivity(this, 20, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            else startActivityAndCollapse(intent);
        });
    }
    static PendingIntent open(Context context, String control, int id) {
        return PendingIntent.getActivity(context, id, new Intent(context, MainActivity.class).putExtra("control", control),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    static boolean timers(JSONObject config) { return config.optBoolean("timed_enabled") || config.optBoolean("window_enabled"); }
    static boolean security(JSONObject config) {
        return config.optBoolean("fail_enabled") || config.optBoolean("panic_enabled") || !config.optString("sms_mode", "off").equals("off") || !config.optString("inbox_mode", "off").equals("off");
    }
    static String summary(Context context, JSONObject status) {
        JSONObject config = status.optJSONObject("config");
        String action = status.optString("action", "idle");
        if (!action.equals("idle")) {
            int id = context.getResources().getIdentifier("state_" + action, "string", context.getPackageName());
            return context.getString(id == 0 ? R.string.state_failed : id);
        }
        return context.getString(!timers(config) && !security(config) ? R.string.protection_off
            : timers(config) && !security(config) && status.optBoolean("snoozed") ? R.string.snoozed
            : timers(config) && !security(config) && !status.optBoolean("armed") ? R.string.waiting_unlock : R.string.protection_on);
    }
    static void notification(Context context, JSONObject status) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        JSONObject config = status.optJSONObject("config");
        if (!timers(config) && !security(config) && status.optString("action", "idle").equals("idle")) { manager.cancel(1); return; }
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        manager.createNotificationChannel(new NotificationChannel("controls", context.getString(R.string.controls), NotificationManager.IMPORTANCE_LOW));
        Notification.Builder notification = new Notification.Builder(context, "controls")
            .setSmallIcon(R.drawable.ic_repose).setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.controls)).setContentIntent(open(context, "", 0))
            .setVisibility(Notification.VISIBILITY_SECRET).setOngoing(true);
        if (timers(config) && status.optString("action", "idle").equals("idle")) {
            boolean paused = status.optBoolean("snoozed") || status.optBoolean("skipped");
            notification.addAction(new Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_repose), context.getString(paused ? R.string.resume : R.string.snooze), open(context, paused ? "resume" : "snooze", 1)).build());
            if (config.optBoolean("window_enabled") && !status.optBoolean("skipped"))
                notification.addAction(new Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_repose), context.getString(R.string.skip), open(context, "skip", 2)).build());
        }
        manager.notify(1, notification.build());
    }
}
