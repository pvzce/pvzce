package com.pvzce.common.network;

import com.pvzce.common.PvzceConstants;

/** Shared, non-resolving validation for a saved address and an actual connection. */
public record ServerAddress(String host, int port) {
    public ServerAddress {
        if (host.isBlank() || host.length() > 256 || host.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
                || host.contains("/") || host.contains("[") || host.contains("]"))
            throw new IllegalArgumentException("请输入 IP 或主机名，不要包含协议和路径");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口应为 1～65535");
    }

    public static ServerAddress parse(String input) {
        String address = input.strip();
        String host = address;
        int port = PvzceConstants.MULTIPLAYER_PORT;
        try {
            if (address.startsWith("[")) {
                int close = address.indexOf(']');
                if (close < 0) throw new IllegalArgumentException("IPv6 地址格式为 [地址]:端口");
                host = address.substring(1, close);
                String suffix = address.substring(close + 1);
                if (!suffix.isEmpty()) {
                    if (!suffix.startsWith(":")) throw new IllegalArgumentException("IPv6 地址格式为 [地址]:端口");
                    port = Integer.parseInt(suffix.substring(1));
                }
            } else if (address.indexOf(':') == address.lastIndexOf(':') && address.contains(":")) {
                int colon = address.indexOf(':');
                host = address.substring(0, colon);
                port = Integer.parseInt(address.substring(colon + 1));
            }
        } catch (NumberFormatException e) { throw new IllegalArgumentException("请输入有效的端口号"); }
        return new ServerAddress(host, port);
    }

    @Override public String toString() { return (host.contains(":") ? "[" + host + "]" : host) + ":" + port; }
}
