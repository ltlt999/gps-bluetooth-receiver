package com.gpsbt.receiver.bt;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 蓝牙 SPP（RFCOMM）接收链路：支持主动连接发送端（客户端）与等待发送端连入（服务器）。
 * 连接建立后由内部线程持续读取数据，通过 {@link Listener#onData} 上抛（读线程回调）。
 */
public final class BluetoothLinkManager {

    /** 主线程回调（onData 除外，它在读线程回调，避免高频数据切换线程）。 */
    public interface Listener {
        void onState(BtState state, String message);

        void onConnected(String remoteName, String remoteMac);

        void onDisconnected(String message);

        /** 读线程回调。buffer 仅在本次调用内有效。 */
        void onData(byte[] buffer, int length);
    }

    /** 标准 SPP UUID，与发送端一致。 */
    public static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static final int BUFFER_SIZE = 4096;

    private final BluetoothAdapter adapter;
    private final String serviceName;
    private final UUID uuid;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean stopping;
    private AcceptThread acceptThread;
    private ConnectThread connectThread;
    private ReadThread readThread;
    private BluetoothSocket socket;

    public BluetoothLinkManager(BluetoothAdapter adapter, String serviceName,
                                UUID uuid, Listener listener) {
        this.adapter = adapter;
        this.serviceName = serviceName == null || serviceName.trim().isEmpty()
                ? "GPS接收器" : serviceName;
        this.uuid = uuid == null ? SPP_UUID : uuid;
        this.listener = listener;
    }

    /** 读取已配对设备列表所需的权限是否已授予。 */
    public static boolean hasBondedListPermission(Context context) {
        String required = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? Manifest.permission.BLUETOOTH_CONNECT
                : Manifest.permission.BLUETOOTH;
        return ContextCompat.checkSelfPermission(context, required)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean isBluetoothAvailable() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled();
    }

    /** 已配对设备，按名称排序。缺权限或蓝牙未开启时返回空列表。 */
    public static List<BluetoothDevice> bondedDevices(Context context) {
        String required = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? Manifest.permission.BLUETOOTH_CONNECT
                : Manifest.permission.BLUETOOTH;
        if (ContextCompat.checkSelfPermission(context, required) != PackageManager.PERMISSION_GRANTED) {
            return Collections.emptyList();
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            return Collections.emptyList();
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        List<BluetoothDevice> result = new ArrayList<BluetoothDevice>(bonded);
        Collections.sort(result, new Comparator<BluetoothDevice>() {
            @Override
            public int compare(BluetoothDevice left, BluetoothDevice right) {
                return displayName(left).compareToIgnoreCase(displayName(right));
            }
        });
        return result;
    }

    public static String displayName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.isEmpty() ? device.getAddress() : name;
        } catch (SecurityException e) {
            return device.getAddress();
        }
    }

    public boolean isConnected() {
        BluetoothSocket current = socket;
        return current != null && current.isConnected() && readThread != null;
    }

    // ---------- 客户端：主动连接发送端 ----------

    public void startClient(BluetoothDevice device) {
        if (adapter == null || device == null) {
            postState(BtState.FAILED, "设备不支持蓝牙或目标设备无效");
            return;
        }
        if (!adapter.isEnabled()) {
            postState(BtState.FAILED, "蓝牙未开启，请在系统设置中开启");
            return;
        }
        stopping = false;
        postState(BtState.CONNECTING, "正在连接 " + displayName(device));
        connectThread = new ConnectThread(device);
        connectThread.start();
    }

    // ---------- 服务器：等待发送端连入 ----------

    public void startServer() {
        if (adapter == null) {
            postState(BtState.FAILED, "设备不支持蓝牙");
            return;
        }
        if (!adapter.isEnabled()) {
            postState(BtState.FAILED, "蓝牙未开启，请在系统设置中开启");
            return;
        }
        stopping = false;
        acceptThread = new AcceptThread();
        acceptThread.start();
    }

    // ---------- 停止 ----------

    public synchronized void stop() {
        stopping = true;
        closeSocket();
        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
    }

    private synchronized void closeSocket() {
        if (readThread != null) {
            readThread.shutdown();
            readThread = null;
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
            socket = null;
        }
    }

    private synchronized void attach(BluetoothSocket accepted) {
        if (stopping) {
            closeQuietly(accepted);
            return;
        }
        socket = accepted;
        InputStream in;
        try {
            in = accepted.getInputStream();
        } catch (IOException e) {
            closeSocket();
            postState(BtState.FAILED, "获取输入流失败：" + e.getMessage());
            return;
        }
        BluetoothDevice device = accepted.getRemoteDevice();
        String name = device == null ? "未知设备" : displayName(device);
        String mac = device == null ? "" : device.getAddress();
        readThread = new ReadThread(in);
        readThread.start();
        postState(BtState.CONNECTED, "已连接 " + name);
        // attach 可能运行在连接/监听线程，回调统一切到主线程
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onConnected(name, mac);
            }
        });
    }

    private void postState(final BtState state, final String message) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onState(state, message);
            }
        });
    }

    private static void closeQuietly(BluetoothSocket target) {
        if (target == null) {
            return;
        }
        try {
            target.close();
        } catch (IOException ignored) {
            // 忽略
        }
    }

    private final class AcceptThread extends Thread {
        private BluetoothServerSocket server;

        @Override
        public void run() {
            try {
                server = adapter.listenUsingInsecureRfcommWithServiceRecord(serviceName, uuid);
            } catch (SecurityException e) {
                postState(BtState.FAILED, "缺少蓝牙连接权限");
                return;
            } catch (IOException e) {
                postState(BtState.FAILED, "创建蓝牙服务失败：" + e.getMessage());
                return;
            }
            postState(BtState.WAITING, "等待发送端连接");
            try {
                BluetoothSocket accepted = server.accept();
                if (stopping || accepted == null) {
                    closeQuietly(accepted);
                    return;
                }
                attach(accepted);
            } catch (IOException e) {
                if (!stopping) {
                    postState(BtState.FAILED, "监听中断：" + e.getMessage());
                }
            } finally {
                closeServer();
            }
        }

        void cancel() {
            closeServer();
        }

        private void closeServer() {
            if (server != null) {
                try {
                    server.close();
                } catch (IOException ignored) {
                    // 忽略
                }
                server = null;
            }
        }
    }

    private final class ConnectThread extends Thread {
        private final BluetoothDevice device;
        private BluetoothSocket target;

        ConnectThread(BluetoothDevice device) {
            this.device = device;
        }

        @Override
        public void run() {
            BluetoothSocket socket;
            try {
                socket = createSocket(device);
            } catch (IOException e) {
                postState(BtState.FAILED, "创建套接字失败：" + e.getMessage());
                return;
            }
            target = socket;
            // createSocket 返回时：反射路径成功则 socket 已连接（跳过 connect），
            // SDP 路径则未连接（需要 connect 走 SDP 查询）。
            try {
                if (!socket.isConnected()) {
                    socket.connect();
                }
            } catch (SecurityException e) {
                postState(BtState.FAILED, "缺少蓝牙连接权限");
                return;
            } catch (IOException e) {
                postState(BtState.FAILED, "连接失败：" + e.getMessage());
                closeQuietly();
                return;
            }
            if (stopping) {
                closeQuietly();
                return;
            }
            attach(target);
        }

        /**
         * 创建 SPP 套接字：
         * 1. 优先 SDP 查询 —— 用 UUID 过滤，确保连到的是发送端注册的 SPP 服务（最准确，
         *    避免反射直连撞上其它占用相同 RFCOMM 通道的蓝牙服务导致「连接成功但无数据」）
         * 2. 回退反射直连通道 1-5 —— SDP 偶发拿不到结果时快速尝试常见通道
         * 两路都失败抛 IOException 让上层判定为失败重连。
         */
        private BluetoothSocket createSocket(BluetoothDevice device) throws IOException {
            BluetoothSocket sdpSocket = null;
            try {
                sdpSocket = device.createInsecureRfcommSocketToServiceRecord(uuid);
                sdpSocket.connect();
                return sdpSocket;
            } catch (Throwable ignored) {
                if (sdpSocket != null) {
                    try { sdpSocket.close(); } catch (Throwable x) {
                    }
                }
            }
            try {
                java.lang.reflect.Method m = device.getClass()
                        .getMethod("createInsecureRfcommSocket", int.class);
                for (int channel : new int[]{1, 2, 3, 4, 5}) {
                    BluetoothSocket socket = null;
                    try {
                        socket = (BluetoothSocket) m.invoke(device, channel);
                        socket.connect();
                        return socket;
                    } catch (Throwable ignored) {
                        if (socket != null) {
                            try { socket.close(); } catch (Throwable x) {
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            throw new IOException("SDP 查询失败且反射通道 1-5 均不可达");
        }

        void cancel() {
            closeQuietly();
        }

        private void closeQuietly() {
            if (target != null) {
                try {
                    target.close();
                } catch (IOException ignored) {
                    // 忽略
                }
                target = null;
            }
        }
    }

    /** 持续读取蓝牙流；read() 返回 -1 或抛异常时视为断开。 */
    private final class ReadThread extends Thread {
        private final InputStream in;
        private volatile boolean running = true;

        ReadThread(InputStream in) {
            this.in = in;
        }

        void shutdown() {
            running = false;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[BUFFER_SIZE];
            while (running && !stopping) {
                int length;
                try {
                    length = in.read(buffer);
                } catch (IOException e) {
                    if (running && !stopping) {
                        postDisconnected("数据读取中断：" + e.getMessage());
                    }
                    return;
                }
                if (length < 0) {
                    if (running && !stopping) {
                        postDisconnected("发送端已断开连接");
                    }
                    return;
                }
                if (length > 0) {
                    listener.onData(buffer, length);
                }
            }
        }

        private void postDisconnected(final String message) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (!stopping) {
                        listener.onDisconnected(message);
                    }
                }
            });
        }
    }
}
