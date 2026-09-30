package com.gpsbt.receiver.boot;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.gpsbt.receiver.R;
import com.gpsbt.receiver.bt.ConnectionMode;
import com.gpsbt.receiver.service.ReceiverService;
import com.gpsbt.receiver.state.LogBus;
import com.gpsbt.receiver.util.Prefs;

/**
 * 开机自动接收的两个入口。
 *
 * 广播路径（{@link BootReceiver}）：大多数设备走这里。
 *
 * 界面冷启动兜底（{@link #tryStart}）：一些车机与深度定制的国产 ROM 不会向应用
 * 投递 {@code BOOT_COMPLETED}，而是直接把应用的 launcher 界面拉起来（现象就是
 * "软件自己打开了但不接收"）。这种情况下广播永远不会来，只能由界面启动时补一次
 * 自动开始接收。从正在显示的界面启动服务属于前台操作，比广播路径更可靠。
 */
public final class BootAutoStart {

    /** 每个进程最多补一次，避免界面被反复拉起时重复启动服务。 */
    private static volatile boolean attempted;

    private BootAutoStart() {
    }

    /**
     * 是否应当补一次自动开始接收。纯决策逻辑，便于单元测试。
     *
     * @param switchOn         「开机自动开始接收」开关是否打开
     * @param alreadyAttempted 本进程是否已经尝试过
     * @param serviceRunning   服务是否已在接收
     * @param prereqOk         启动前置条件（蓝牙权限、蓝牙硬件、目标设备）是否满足
     */
    static boolean shouldAutoStart(boolean switchOn, boolean alreadyAttempted,
                                   boolean serviceRunning, boolean prereqOk) {
        if (!switchOn) {
            return false;
        }
        if (alreadyAttempted) {
            return false;
        }
        if (!prereqOk) {
            return false;
        }
        return !serviceRunning;
    }

    /**
     * 界面冷启动时调用：满足条件则自动开始接收。
     *
     * @return true 表示本次触发了自动开始接收
     */
    public static boolean tryStart(Context context) {
        Prefs prefs = Prefs.get(context);
        // 开关关闭时不标记 attempted：用户随后可能在设置里把它打开
        if (!prefs.isAutoStartOnBoot()) {
            return false;
        }

        // 前置条件不满足时绝不能启动服务：个别系统缺权限就启动前台服务会直接闪退。
        // 此时给出明确反馈而不是崩
        String blocked = prerequisiteFailure(context);
        if (blocked != null) {
            BootNotifier.reportBlocked(context, blocked);
            return false;
        }

        if (!shouldAutoStart(true, attempted, ReceiverService.isRunning(), true)) {
            return false;
        }
        attempted = true;

        LogBus.get().log(LogBus.Level.INFO, "界面冷启动，自动开始接收");
        ReceiverService.startFromUi(context, prefs.getMode(), prefs.getLastDeviceMac());
        return true;
    }

    /**
     * 启动前置条件检查，广播路径与界面路径共用。
     *
     * 必须在真正启动前台服务之前先过这一关：条件不满足时启动前台服务在部分
     * 系统上会直接抛异常导致闪退，所以只能从源头避免。
     *
     * @return null 表示可以自动开始；否则返回不可启动的原因文案
     */
    public static String prerequisiteFailure(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return context.getString(R.string.boot_reason_no_permission);
        }
        if (BluetoothAdapter.getDefaultAdapter() == null) {
            return context.getString(R.string.boot_reason_no_bluetooth);
        }
        Prefs prefs = Prefs.get(context);
        if (prefs.getMode() == ConnectionMode.CLIENT
                && (prefs.getLastDeviceMac() == null || prefs.getLastDeviceMac().isEmpty())) {
            return context.getString(R.string.boot_reason_no_device);
        }
        return null;
    }
}
