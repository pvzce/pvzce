package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * "This is where Jev is for me."
 *
 * <p>The credential lives in the client's own settings file ({@code config/pvzce-client.toml},
 * under the settings page's AI 对战 rows) and the client hands it to the server for the session.
 * The server is the side that talks to Jev - it is the authority, and it is the side that must keep
 * working when there is no client at all (a smoke run, a test) - so the key has to cross this one
 * boundary. It travels on the same connection the player's own game is already on, it is never
 * written into a save, and it is never logged: {@code JevSettings.toString} prints {@code ***}.
 *
 * <p>Sent when the settings are saved and again before every level entry, so a server that started
 * after the client (or one that was restarted) has it before the run that needs it. An empty value
 * is a real statement - "use the built-in opponent" - and the server refuses to overwrite a
 * credential it was launched with (see {@code PvzceServer.setJevSettings}), which is how a headless
 * run's {@code -Dpvzce.jev.key} survives a client that has nothing configured.
 *
 * @param url   the full endpoint
 * @param model the provider's model name
 * @param key   the bearer token
 */
public record JevSettingsC2S(String url, String model, String key) implements PvzcePacket {
    public JevSettingsC2S {
        url = url == null ? "" : url.trim();
        model = model == null ? "" : model.trim();
        key = key == null ? "" : key.trim();
    }

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeString(url);
        buf.writeString(model);
        buf.writeString(key);
    }

    public static JevSettingsC2S decode(PacketByteBuf buf) {
        return new JevSettingsC2S(buf.readString(), buf.readString(), buf.readString());
    }

    /** Never prints the key: a packet's {@code toString} ends up in logs by accident. */
    @Override
    public String toString() {
        return "JevSettingsC2S[url=" + url + ", model=" + model + ", key="
                + (key.isEmpty() ? "<none>" : "***") + "]";
    }
}
