package com.pvzce.common.network;

public enum ConnectionDirection {
    /** Client-to-server packet, decoded by the server connection. */
    SERVERBOUND,
    /** Server-to-client packet, decoded by the client connection. */
    CLIENTBOUND
}
