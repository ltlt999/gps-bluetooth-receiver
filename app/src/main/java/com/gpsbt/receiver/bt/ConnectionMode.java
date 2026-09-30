package com.gpsbt.receiver.bt;

/** 接收端连接方式：主动连接发送端，或等待发送端连入。 */
public enum ConnectionMode {
    /** 客户端：主动连接发送端（发送端为服务器模式时使用）。 */
    CLIENT,
    /** 服务器：等待发送端连入（发送端为客户端模式时使用）。 */
    SERVER
}
