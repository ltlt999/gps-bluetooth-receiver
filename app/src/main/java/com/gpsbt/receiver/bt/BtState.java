package com.gpsbt.receiver.bt;

/** 蓝牙链路状态。 */
public enum BtState {
    IDLE,
    WAITING,
    CONNECTING,
    CONNECTED,
    FAILED,
    DISCONNECTED
}
