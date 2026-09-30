package com.gpsbt.receiver;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import com.gpsbt.receiver.bt.BluetoothLinkManager;
import com.gpsbt.receiver.bt.ConnectionMode;
import com.gpsbt.receiver.boot.BootAutoStart;
import com.gpsbt.receiver.nmea.NmeaParser;
import com.gpsbt.receiver.service.ReceiverService;
import com.gpsbt.receiver.state.LogBus;
import com.gpsbt.receiver.state.ReceiverState;
import com.gpsbt.receiver.ui.adapter.LogAdapter;
import com.gpsbt.receiver.util.Formatters;
import com.gpsbt.receiver.util.Prefs;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 主界面：连接控制、定位显示、统计与日志。 */
public class MainActivity extends AppCompatActivity
        implements ReceiverState.Listener, LogBus.Listener {

    private static final SimpleDateFormat UTC_FORMAT =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    private static final int REQUEST_PERMISSIONS = 1;

    private static final int COLOR_CONNECTED = 0xFF00E5C7;
    private static final int COLOR_WAITING = 0xFF3D8BFF;
    private static final int COLOR_FAILED = 0xFFFF5C6C;
    private static final int COLOR_IDLE = 0xFF8A97B0;

    private final Handler main = new Handler(Looper.getMainLooper());

    private Prefs prefs;
    private ReceiverState state;

    private TextView statusPill;
    private TextView tvStatus;
    private MaterialButton btnModeClient;
    private MaterialButton btnModeServer;
    private LinearLayout deviceSection;
    private TextView tvDevicesEmpty;
    private TextView tvSelectedDevice;
    private MaterialButton btnChooseDevice;
    private MaterialButton btnToggle;
    private TextView tvFixBadge;
    private TextView tvLatitude;
    private TextView tvLongitude;
    private TextView tvAltitude;
    private TextView tvSpeed;
    private TextView tvCourse;
    private TextView tvSatellites;
    private TextView tvUtc;
    private TextView tvUpdated;
    private TextView tvSentences;
    private TextView tvBytes;
    private TextView tvRate;
    private TextView tvBadChecksum;
    private RecyclerView rvLog;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshDynamic();
            main.postDelayed(this, 1000L);
        }
    };

    private final LogAdapter log = new LogAdapter();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = Prefs.get(this);
        state = ReceiverState.get();

        statusPill = findViewById(R.id.statusPill);
        tvStatus = findViewById(R.id.tvStatus);
        btnModeClient = findViewById(R.id.btnModeClient);
        btnModeServer = findViewById(R.id.btnModeServer);
        deviceSection = findViewById(R.id.deviceSection);
        tvDevicesEmpty = findViewById(R.id.tvDevicesEmpty);
        btnToggle = findViewById(R.id.btnToggle);
        tvFixBadge = findViewById(R.id.tvFixBadge);
        tvLatitude = findViewById(R.id.tvLatitude);
        tvLongitude = findViewById(R.id.tvLongitude);
        tvAltitude = findViewById(R.id.tvAltitude);
        tvSpeed = findViewById(R.id.tvSpeed);
        tvCourse = findViewById(R.id.tvCourse);
        tvSatellites = findViewById(R.id.tvSatellites);
        tvUtc = findViewById(R.id.tvUtc);
        tvUpdated = findViewById(R.id.tvUpdated);
        tvSentences = findViewById(R.id.tvSentences);
        tvBytes = findViewById(R.id.tvBytes);
        tvRate = findViewById(R.id.tvRate);
        tvBadChecksum = findViewById(R.id.tvBadChecksum);
        rvLog = findViewById(R.id.rvLog);
        tvSelectedDevice = findViewById(R.id.tvSelectedDevice);
        btnChooseDevice = findViewById(R.id.btnChooseDevice);

        rvLog.setLayoutManager(new LinearLayoutManager(this));
        rvLog.setAdapter(log);

        btnChooseDevice.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDeviceDialog();
            }
        });

        btnModeClient.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode(ConnectionMode.CLIENT);
            }
        });
        btnModeServer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode(ConnectionMode.SERVER);
            }
        });
        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleReceive();
            }
        });
        findViewById(R.id.btnClearLog).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogBus.get().clear();
                log.clear();
            }
        });

        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettingsDialog();
            }
        });

        // 开机自启兜底：部分 ROM 不投递开机广播而是直接拉起界面，
        // 此时由界面冷启动补一次自动开始接收（开关关闭时什么都不做）
        BootAutoStart.tryStart(this);

        log.reload(LogBus.get().entries());
    }

    @Override
    protected void onResume() {
        super.onResume();
        state.addListener(this);
        LogBus.get().addListener(this);
        main.post(ticker);
        ensurePermissions();
        refreshAll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(ticker);
        state.removeListener(this);
        LogBus.get().removeListener(this);
    }

    // ---------- 权限 ----------

    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void ensurePermissions() {
        if (hasBluetoothPermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.POST_NOTIFICATIONS}, REQUEST_PERMISSIONS);
            }
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_PERMISSIONS);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH}, REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (!hasBluetoothPermission()) {
                Toast.makeText(this, R.string.permission_bluetooth_needed, Toast.LENGTH_LONG).show();
            }
            refreshAll();
        }
    }

    // ---------- 模式与启动 ----------

    private void setMode(ConnectionMode mode) {
        if (ReceiverService.isRunning()) {
            Toast.makeText(this, R.string.status_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.setMode(mode);
        refreshAll();
    }

    private void toggleReceive() {
        if (ReceiverService.isRunning()) {
            ReceiverService.stop(this);
            return;
        }
        if (!BluetoothLinkManager.isBluetoothAvailable()) {
            Toast.makeText(this, R.string.bluetooth_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        if (!hasBluetoothPermission()) {
            ensurePermissions();
            return;
        }
        ConnectionMode mode = prefs.getMode();
        String mac = prefs.getLastDeviceMac();
        if (mode == ConnectionMode.CLIENT && (mac == null || mac.isEmpty())) {
            Toast.makeText(this, R.string.selected_none, Toast.LENGTH_SHORT).show();
            return;
        }
        ReceiverService.start(this, mode, mac);
    }

    /** 弹窗选择发送端设备：选择后立即保存；正在接收时切换到新设备重连。 */
    /** 设置弹窗：开机自动开始接收开关。 */
    private void showSettingsDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        final SwitchMaterial switchAutoStart = content.findViewById(R.id.switchAutoStart);
        switchAutoStart.setChecked(prefs.isAutoStartOnBoot());
        switchAutoStart.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setAutoStartOnBoot(isChecked);
            }
        });
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_title)
                .setView(content)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showDeviceDialog() {
        if (!hasBluetoothPermission()) {
            ensurePermissions();
            return;
        }
        final List<BluetoothDevice> devices = BluetoothLinkManager.bondedDevices(this);
        if (devices.isEmpty()) {
            Toast.makeText(this, R.string.paired_devices_empty, Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[devices.size()];
        int checkedIndex = -1;
        String currentMac = prefs.getLastDeviceMac();
        for (int i = 0; i < devices.size(); i++) {
            BluetoothDevice device = devices.get(i);
            labels[i] = BluetoothLinkManager.displayName(device) + "\n" + device.getAddress();
            if (device.getAddress() != null && device.getAddress().equals(currentMac)) {
                checkedIndex = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_choose_device)
                .setSingleChoiceItems(labels, checkedIndex, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        BluetoothDevice device = devices.get(which);
                        prefs.setMode(ConnectionMode.CLIENT);
                        prefs.setLastDeviceMac(device.getAddress());
                        dialog.dismiss();
                        refreshAll();
                        // 正在接收时切换设备：服务会用新地址重启连接（失败则自动重连）
                        if (ReceiverService.isRunning()) {
                            ReceiverService.start(MainActivity.this,
                                    ConnectionMode.CLIENT, device.getAddress());
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---------- 状态刷新 ----------

    @Override
    public void onChanged() {
        refreshAll();
    }

    @Override
    public void onLog(LogBus.Entry entry) {
        log.append(entry);
        rvLog.scrollToPosition(Math.max(0, log.getItemCount() - 1));
    }

    private void refreshAll() {
        refreshHeader();
        refreshConnection();
        refreshFix();
        refreshStats();
        refreshDynamic();
    }

    private void refreshHeader() {
        ReceiverState.Phase phase = state.phase();
        int color;
        String text;
        switch (phase) {
            case CONNECTED:
                color = COLOR_CONNECTED;
                text = getString(R.string.status_connected);
                break;
            case WAITING:
                color = COLOR_WAITING;
                text = getString(R.string.status_waiting);
                break;
            case CONNECTING:
                color = COLOR_WAITING;
                text = getString(R.string.status_connecting);
                break;
            case FAILED:
                color = COLOR_FAILED;
                text = getString(R.string.status_failed);
                break;
            default:
                color = COLOR_IDLE;
                text = ReceiverService.isRunning()
                        ? getString(R.string.status_waiting) : getString(R.string.status_idle);
                break;
        }
        statusPill.setText(text);
        statusPill.setTextColor(color);
    }

    private void refreshConnection() {
        ConnectionMode mode = prefs.getMode();
        boolean client = mode == ConnectionMode.CLIENT;
        btnModeClient.setBackgroundColor(client ? COLOR_CONNECTED : 0x00000000);
        btnModeClient.setTextColor(client ? 0xFF06231E : 0xFF8A97B0);
        btnModeServer.setBackgroundColor(!client ? COLOR_CONNECTED : 0x00000000);
        btnModeServer.setTextColor(!client ? 0xFF06231E : 0xFF8A97B0);

        deviceSection.setVisibility(client ? View.VISIBLE : View.GONE);
        btnToggle.setText(ReceiverService.isRunning()
                ? R.string.btn_stop : R.string.btn_start);

        String status = state.statusText();
        if (status == null || status.isEmpty()) {
            status = getString(R.string.status_idle);
        }
        ReceiverState.Device device = state.connectedDevice();
        if (device != null) {
            status = getString(R.string.notif_connected_to, device.name) + "　" + status;
        }
        tvStatus.setText(status);

        if (client) {
            List<BluetoothDevice> devices = hasBluetoothPermission()
                    ? BluetoothLinkManager.bondedDevices(this)
                    : java.util.Collections.<BluetoothDevice>emptyList();
            tvDevicesEmpty.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
            refreshSelectedDevice(devices);
        } else {
            tvDevicesEmpty.setVisibility(View.GONE);
        }
    }

    /** 当前选中的发送端设备：名称优先，拿不到名称时显示 MAC 地址。 */
    private void refreshSelectedDevice(List<BluetoothDevice> devices) {
        String mac = prefs.getLastDeviceMac();
        if (mac == null || mac.isEmpty()) {
            tvSelectedDevice.setText(R.string.selected_none);
            tvSelectedDevice.setTextColor(COLOR_IDLE);
            return;
        }
        String name = null;
        for (BluetoothDevice device : devices) {
            if (device.getAddress() != null && device.getAddress().equals(mac)) {
                name = BluetoothLinkManager.displayName(device);
                break;
            }
        }
        tvSelectedDevice.setText(name != null ? name : mac);
        tvSelectedDevice.setTextColor(COLOR_CONNECTED);
    }

    private void refreshFix() {
        NmeaParser.Fix fix = state.fix();
        if (fix == null) {
            tvFixBadge.setText(R.string.fix_none);
            tvFixBadge.setTextColor(COLOR_IDLE);
            tvLatitude.setText(R.string.label_latitude);
            tvLongitude.setText(R.string.label_longitude);
            tvAltitude.setText("--");
            tvSpeed.setText("--");
            tvCourse.setText("--");
            tvSatellites.setText("--");
            tvUtc.setText("--");
            tvUpdated.setText("--");
            return;
        }
        if (fix.valid) {
            tvFixBadge.setText(R.string.fix_valid);
            tvFixBadge.setTextColor(COLOR_CONNECTED);
        } else {
            tvFixBadge.setText(R.string.fix_invalid);
            tvFixBadge.setTextColor(COLOR_FAILED);
        }
        tvLatitude.setText(Formatters.latitude(fix.latitude));
        tvLongitude.setText(Formatters.longitude(fix.longitude));
        tvAltitude.setText(Double.isNaN(fix.altitudeM) ? "--"
                : getString(R.string.unit_altitude, Formatters.oneDecimal(fix.altitudeM)));
        tvSpeed.setText(Double.isNaN(fix.speedMps) ? "--"
                : getString(R.string.unit_speed, Formatters.oneDecimal(fix.speedMps)));
        tvCourse.setText(Double.isNaN(fix.courseDeg) ? "--"
                : getString(R.string.unit_course, Formatters.oneDecimal(fix.courseDeg)));
        int used = Math.max(fix.satellitesUsed, 0);
        int inView = Math.max(state.satellitesInView(), fix.satellitesInView);
        tvSatellites.setText(inView > 0 || used > 0
                ? getString(R.string.sat_summary, used, inView) : "--");
        tvUtc.setText(fix.utcMillis > 0 ? UTC_FORMAT.format(new Date(fix.utcMillis)) + " UTC" : "--");
    }

    private void refreshStats() {
        tvSentences.setText(String.format(Locale.US, "%d", state.sentences()));
        tvBytes.setText(Formatters.bytes(state.bytes()));
        tvBadChecksum.setText(String.format(Locale.US, "%d", state.badChecksum()));
    }

    /** 每秒变化的部分：更新时间与速率。 */
    private void refreshDynamic() {
        NmeaParser.Fix fix = state.fix();
        long now = System.currentTimeMillis();
        if (fix != null) {
            tvUpdated.setText(Formatters.ago(state.lastFixAt(), now));
        }
        long startedAt = state.startedAt();
        if (startedAt > 0 && state.sentences() > 0) {
            double rate = state.sentences() * 1000.0 / Math.max(1L, now - startedAt);
            tvRate.setText(getString(R.string.unit_rate, Formatters.oneDecimal(rate)));
        } else {
            tvRate.setText("--");
        }
    }
}
