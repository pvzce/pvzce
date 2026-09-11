package com.pvzce.server.command;

import com.pvzce.server.PvzceServer;

/** Command source bound to the integrated server (and its client connection). */
public record PvzceCommandSource(PvzceServer server) {
    public void sendFeedback(String message) {
        server.sendMessage(message);
    }
}
