package com.pvzce.client.mechanic;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.hud.cardbar.CardBar;
import com.pvzce.common.network.packet.MechanicSyncS2C;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The client's half of the level mechanic system: one registration per mechanic that has
 * something to draw or to react to.
 *
 * <p>Built-in mechanics register from {@link #bootstrap()}, which {@code PvzceClient} calls
 * on construction; a mod registers its own from a {@code ClientModInitializer}. An unknown
 * mechanic is not an error here - the level runs, it just has no client-side extra - so a
 * server with a mod the client lacks degrades instead of refusing to play.
 */
public final class ClientMechanics {
    private static final Map<Identifier, ClientMechanic> REGISTRY = new HashMap<>();
    private static final java.util.Set<Identifier> REPORTED_MISSING = new java.util.HashSet<>();
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("PVZCE/ClientMechanics");
    private static volatile boolean bootstrapped;

    /** Registers every built-in client mechanic; idempotent. */
    public static void bootstrap() {
        if (bootstrapped) {
            return;
        }
        bootstrapped = true;
        register(new ConveyorClientMechanic());
        register(new PlacementZoneClientMechanic());
        register(new MowerClientMechanic());
        register(new ToolClientMechanic());
    }

    public static void register(ClientMechanic mechanic) {
        REGISTRY.put(mechanic.id(), mechanic);
    }

    public static ClientMechanic get(Identifier id) {
        return REGISTRY.get(id);
    }

    /** Every mechanic this client knows, in registration order. */
    public static List<ClientMechanic> values() {
        return List.copyOf(REGISTRY.values());
    }

    /** Routes one state update to the mechanic that owns it. */
    public static void applySync(ClientLevel level, MechanicSyncS2C packet) {
        ClientMechanic mechanic = REGISTRY.get(packet.mechanic());
        if (mechanic == null) {
            reportMissing(packet.mechanic(), "state update");
            return;
        }
        mechanic.applySync(level, packet.payloadBuffer());
    }

    /** The card bar for a level: the first mechanic that offers one, else the seed bar. */
    public static CardBar cardBar(ClientLevel level, CardBar.Host host) {
        for (Identifier id : level.mechanicIds()) {
            ClientMechanic mechanic = REGISTRY.get(id);
            if (mechanic != null) {
                CardBar bar = mechanic.createCardBar(host);
                if (bar != null) {
                    return bar;
                }
            }
        }
        return null;
    }

    /**
     * The tool a click with no card in hand uses, or {@code null}.
     *
     * <p>Forwarded to the tool mechanic's own registration so the answer comes from the same
     * place that read the level's blocks: the client must not re-derive "which tool is the
     * default" from the payload, or a level whose blocks arrived in a different order would
     * behave differently on the two sides.
     */
    public static com.pvzce.api.content.ToolData defaultTool(ClientLevel level) {
        ClientMechanic mechanic = REGISTRY.get(com.pvzce.common.PvzceIds.MECHANIC_TOOL);
        return mechanic instanceof ToolClientMechanic tools ? tools.defaultTool(level) : null;
    }

    /** World-space overlays for a level, in the order the level declares its mechanics. */
    public static List<ClientMechanic.WorldOverlay> worldOverlays(ClientLevel level) {
        List<ClientMechanic.WorldOverlay> overlays = new java.util.ArrayList<>();
        for (Identifier id : level.mechanicIds()) {
            ClientMechanic mechanic = REGISTRY.get(id);
            if (mechanic != null) {
                ClientMechanic.WorldOverlay overlay = mechanic.createWorldOverlay(level);
                if (overlay != null) {
                    overlays.add(overlay);
                }
            }
        }
        return overlays;
    }

    /** True when this level's bar is dealt by the level rather than chosen by the player. */
    public static boolean dealsItsOwnCards(String levelId) {
        Identifier id = Identifier.tryParse(levelId);
        com.pvzce.api.content.LevelDef def = id == null
                ? null : com.pvzce.common.core.BuiltInRegistries.LEVELS.get(id);
        return def != null && com.pvzce.common.level.mechanic.LevelMechanics.dealsItsOwnCards(def);
    }

    /**
     * The lawn mowers this level still has parked, as the client last heard.
     *
     * <p>Read by the victory payout: the rows that were never needed are worth coins, and
     * the coins leave from where those mowers stand. Empty for a level with no mowers and
     * for one whose rig the client never heard about.
     */
    public static List<com.pvzce.common.level.mechanic.MowerMechanic.Row> parkedMowers(ClientLevel level) {
        return MowerClientMechanic.parkedMowers(level);
    }

    /**
     * Pays the parked mowers out: answers where they were and stops drawing them.
     *
     * <p>Separate from {@link #parkedMowers} because a mower that has been turned into a
     * coin must not still be standing on the lawn, and reading the rows alone cannot say
     * that. See {@code MowerClientMechanic.consumeParkedMowers}.
     */
    public static List<com.pvzce.common.level.mechanic.MowerMechanic.Row> consumeParkedMowers(
            ClientLevel level) {
        return MowerClientMechanic.consumeParkedMowers(level);
    }

    /**
     * The parked mower nearest a world point, or {@code null}.
     *
     * <p>The HUD's long-press target. An unknown-id or mower-less level answers null, so the
     * caller needs no second "does this level have mowers" question.
     */
    public static com.pvzce.common.level.mechanic.MowerMechanic.Row parkedMowerAt(
            ClientLevel level, float worldX, float worldY) {
        return MowerClientMechanic.parkedMowerAt(level, worldX, worldY);
    }

    /** Reports an unknown mechanic once per id rather than once per packet. */
    private static void reportMissing(Identifier id, String what) {
        synchronized (REPORTED_MISSING) {
            if (!REPORTED_MISSING.add(id)) {
                return;
            }
        }
        LOGGER.warn("[mechanics] no client implementation for {}: its {} cannot be shown. "
                + "The level still plays; install the mod that provides it.", id, what);
    }

    private ClientMechanics() {
    }
}
