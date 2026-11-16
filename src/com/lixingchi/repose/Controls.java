package com.lixingchi.repose;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.service.quicksettings.*;

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
    static void notification(Context context) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("controls", context.getString(R.string.controls), NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(context, "controls")
            .setSmallIcon(R.drawable.ic_repose).setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.controls)).setContentIntent(open(context, "", 0))
            .setVisibility(Notification.VISIBILITY_SECRET).setOngoing(true)
            .addAction(new Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_repose), context.getString(R.string.snooze), open(context, "snooze", 1)).build())
            .addAction(new Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_repose), context.getString(R.string.skip), open(context, "skip", 2)).build()).build();
        manager.notify(1, notification);
    }
}
