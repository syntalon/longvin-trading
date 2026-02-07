package com.longvin.dasgateway.cmd;

public interface CmdEventListener {
    default void onConnected() {
    }

    default void onDisconnected() {
    }

    default void onInboundLine(String line) {
    }

    default void onOutboundLine(String line) {
    }
}
