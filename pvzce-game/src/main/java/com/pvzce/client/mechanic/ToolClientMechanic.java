package com.pvzce.client.mechanic;

import com.pvzce.api.content.ToolData;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientLevel;
import com.pvzce.common.PvzceIds;

/**
 * The tools a level hands the player, on the client side.
 *
 * <p>One job: answer {@link #defaultTool} for the level that is running, so the in-game screen
 * can tell "a click with no card in hand does nothing" from "a click with no card in hand swings
 * the mallet". The tools are read from the blocks the server sent, in the order it sent them,
 * and the first one marked default wins - which is the same rule the server's own click handler
 * applies, so the two cannot pick different tools.
 *
 * <p>Nothing is drawn for this mechanic by itself. The cursor's own art while the mallet is held
 * belongs to the HUD, which is where the rest of the pointer lives.
 */
final class ToolClientMechanic implements ClientMechanic {
    @Override
    public Identifier id() {
        return PvzceIds.MECHANIC_TOOL;
    }

    /** The tool an empty click uses this level, or {@code null}. */
    ToolData defaultTool(ClientLevel level) {
        for (ToolData data : tools(level)) {
            if (data.isDefault()) {
                return data;
            }
        }
        return null;
    }

    /**
     * Every tool this run grants: the blocks the level declared, then whatever a mutation handed
     * over.
     *
     * <p>Read fresh rather than cached, because the second half changes while the level runs -
     * Whack-a-Zombie's mallet arrives with a mutation and leaves with it. The mechanic's own state
     * map is not used here for exactly that reason: a value computed once would keep the mallet in
     * the player's hand after the mutation that granted it was evicted.
     */
    private static java.util.List<ToolData> tools(ClientLevel level) {
        java.util.List<ToolData> found = new java.util.ArrayList<>();
        for (com.pvzce.api.content.mechanic.MechanicData data : level.mechanicBlocks(PvzceIds.MECHANIC_TOOL)) {
            if (data instanceof ToolData tool) {
                found.add(tool);
            }
        }
        com.pvzce.common.network.packet.MutationStateS2C mutations = level.mutations();
        if (mutations != null) {
            for (com.pvzce.common.network.packet.MutationStateS2C.ToolGrant grant : mutations.tools()) {
                Identifier toolId = Identifier.tryParse(grant.tool());
                if (toolId == null) {
                    continue;
                }
                boolean already = found.stream()
                        .anyMatch(existing -> toolId.equals(existing.tool()));
                if (!already) {
                    found.add(new ToolData(toolId, grant.isDefault(),
                            grant.free() ? 0 : grant.cooldownTicks(),
                            grant.free() ? java.util.Optional.of(
                                    com.pvzce.api.content.ResourceCost.FREE) : java.util.Optional.empty()));
                }
            }
        }
        return java.util.List.copyOf(found);
    }
}
