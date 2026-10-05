package com.gpsbt.receiver.boot;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.gpsbt.receiver.service.ReceiverService;
import com.gpsbt.receiver.util.BootLog;
import com.gpsbt.receiver.util.Prefs;

/**
 * 开机广播：开关默认关闭，用户在设置里开启后开机才会自动开始接收。
 * 额外监听 QUICKBOOT_POWERON，部分车机与"快速启动"设备投递的是这个 action。
 *
 * 开机后另外安排 3/10/30 分钟三次「补启动」：部分 ROM 会在开机后不久杀掉
 * 后台服务、或开机时蓝牙/对端尚未就绪，补启动能在用户不打开应用的情况下恢复接收。
 */
public class BootReceiver extends BroadcastReceiver {

    /** 补启动（定时自查）action，仅本应用内部使用。 */
    public static final String ACTION_BOOT_RETRY = "com.gpsbt.receiver.action.BOOT_RETRY";

    /** 补启动时间点（毫秒）。 */
    private static final long[] RETRY_DELAYS = {3 * 60_000L, 10 * 60_000L, 30 * 60_000L};

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_BOOT_RETRY.equals(action)) {
            retry(context);
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            autoStart(context, action);
        }
    }

    private void autoStart(Context context, String action) {
        if (!Prefs.get(context).isAutoStartOnBoot()) {
            return;
        }
        BootLog.record(context, "收到开机广播（" + action + "）");
        if (ReceiverService.isRunning()) {
            BootLog.record(context, "接收已在运行，跳过");
            return;
        }
        String blocked = BootAutoStart.prerequisiteFailure(context);
        if (blocked != null) {
            BootNotifier.reportBlocked(context, blocked);
            return;
        }
        ReceiverService.startFromBoot(context);
        scheduleRetries(context);
    }

    /** 定时补启动：服务未运行时再拉一次（用户不打开应用也能恢复）。 */
    private void retry(Context context) {
        if (!Prefs.get(context).isAutoStartOnBoot()) {
            return;
        }
        if (ReceiverService.isRunning()) {
            return;
        }
        String blocked = BootAutoStart.prerequisiteFailure(context);
        if (blocked != null) {
            BootLog.record(context, "补启动被阻止：" + blocked);
            return;
        }
        BootLog.record(context, "定时补启动：重新拉起接收服务");
        ReceiverService.startFromBoot(context);
    }

    private void scheduleRetries(Context context) {
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        for (int i = 0; i < RETRY_DELAYS.length; i++) {
            Intent intent = new Intent(context, BootReceiver.class)
                    .setAction(ACTION_BOOT_RETRY);
            PendingIntent pending = PendingIntent.getBroadcast(context, 100 + i, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long triggerAt = System.currentTimeMillis() + RETRY_DELAYS[i];
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Doze 下也能触发；非精确闹钟，误差几分钟可接受
                alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAt, pending);
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending);
            }
        }
    }
}
