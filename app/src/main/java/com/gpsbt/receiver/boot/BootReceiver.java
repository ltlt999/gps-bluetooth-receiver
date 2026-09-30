package com.gpsbt.receiver.boot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.gpsbt.receiver.service.ReceiverService;
import com.gpsbt.receiver.util.Prefs;

/**
 * 开机广播：开关默认关闭，用户在设置里开启后开机才会自动开始接收。
 * 额外监听 QUICKBOOT_POWERON，部分车机与"快速启动"设备投递的是这个 action。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            autoStart(context);
        }
    }

    private void autoStart(Context context) {
        if (!Prefs.get(context).isAutoStartOnBoot()) {
            return;
        }
        if (ReceiverService.isRunning()) {
            return;
        }
        String blocked = BootAutoStart.prerequisiteFailure(context);
        if (blocked != null) {
            BootNotifier.reportBlocked(context, blocked);
            return;
        }
        ReceiverService.startFromBoot(context);
    }
}
