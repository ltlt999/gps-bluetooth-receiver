package com.gpsbt.receiver.boot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.gpsbt.receiver.MainActivity;
import com.gpsbt.receiver.R;
import com.gpsbt.receiver.state.LogBus;
import com.gpsbt.receiver.state.ReceiverState;

/** 开机自动接收未能启动时的提示。 */
public final class BootNotifier {

    private static final String CHANNEL_ID = "gps_receiver_boot";
    private static final int NOTIFICATION_ID = 4001;

    private BootNotifier() {
    }

    /**
     * 自动接收没能开始：写日志、更新界面状态文字、发通知说明原因。
     * 三处都写是因为通知在 Android 13+ 可能被用户关掉，界面状态永远是可见的。
     */
    public static void reportBlocked(Context context, String reason) {
        LogBus.get().log(LogBus.Level.ERROR, "自动接收未启动：" + reason);
        ReceiverState.get().setPhase(ReceiverState.Phase.IDLE);
        ReceiverState.get().setStatusText(reason);
        ReceiverState.get().notifyChanged();
        notifyBlocked(context, reason);
    }

    /** 发一条「自动接收未启动」通知，说明原因。 */
    public static void notifyBlocked(Context context, String reason) {
        createChannel(context);
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(context.getString(R.string.boot_blocked_title))
                .setContentText(reason)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(reason))
                .setAutoCancel(true)
                .setContentIntent(content)
                .build();
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }

    private static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.boot_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.boot_channel_desc));
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
