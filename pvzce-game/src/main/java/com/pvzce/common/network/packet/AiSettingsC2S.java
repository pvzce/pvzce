package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * "This is where my AIs are."
 *
 * <p>Six fields for two tiers: the tactical model (Jev, whose native decisions endpoint is not a
 * chat endpoint) and the commander model (a chat model that reads the board once a minute and writes
 * a strategy line). They travel together because they are one decision the player made on one page,
 * and because a server that has one of them and not the other is a server that cannot play the mode
 * the player configured.
 *
 * <p>The credential lives in the client's own settings file ({@code config/pvzce-client.toml},
 * under the settings page's AI 对战 rows) and the client hands it to the server for the session.
 * The server is the side that talks to Jev - it is the authority, and it is the side that must keep
 * working when there is no client at all (a smoke run, a test) - so the key has to cross this one
 * boundary. It travels on the same connection the player's own game is already on, it is never
 * written into a save, and it is never logged: {@code AiSettings.toString} prints {@code ***}.
 *
 * <p>Sent when the settings are saved and again before every level entry, so a server that started
 * after the client (or one that was restarted) has it before the run that needs it. An empty value
 * is a real statement - "use the built-in opponent" - and the server refuses to overwrite a
 * credential it was launched with (see {@code PvzceServer.setAiSettings}), which is how a headless
 * run's {@code -Dpvzce.jev.key} survives a client that has nothing configured.
 *
 * @param url   the full endpoint
 * @param model the provider's model name
 * @param key   the bearer token
 */
public record AiSettingsC2S(String url, String model, String key, String commanderUrl,
                           String commanderModel, String commanderKey) implements PvzcePacket {
    public AiSettingsC2S {
        url = trim(url);
        model = trim(model);
        key = trim(key);
        commanderUrl = trim(commanderUrl);
        commanderModel = trim(commanderModel);
        commanderKey = trim(commanderKey);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
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
        buf.writeString(commanderUrl);
        buf.writeString(commanderModel);
        buf.writeString(commanderKey);
    }

    public static AiSettingsC2S decode(PacketByteBuf buf) {
        return new AiSettingsC2S(buf.readString(), buf.readString(), buf.readString(),
                buf.readString(), buf.readString(), buf.readString());
    }

    /** Never prints a key: a packet's {@code toString} ends up in logs by accident. */
    @Override
    public String toString() {
        return "AiSettingsC2S[jev=" + url + "/" + model + keyPart(key)
                + ", commander=" + commanderUrl + "/" + commanderModel + keyPart(commanderKey) + "]";
    }

    private static String keyPart(String value) {
        return value == null || value.isEmpty() ? "/<none>" : "/***";
    }
}
