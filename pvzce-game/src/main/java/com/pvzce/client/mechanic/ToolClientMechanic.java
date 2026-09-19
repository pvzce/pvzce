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
    /** Where the level's tool blocks are kept between init and the first click. */
    private static final Identifier STATE_KEY = PvzceIds.id("tool_blocks");

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

    /** Every tool block this level declared, in the order the server sent them. */
    @SuppressWarnings("unchecked")
    private static java.util.List<ToolData> tools(ClientLevel level) {
        return level.mechanicState(STATE_KEY, () -> {
            java.util.List<ToolData> found = new java.util.ArrayList<>();
            for (com.pvzce.api.content.mechanic.MechanicData data : level.mechanicBlocks(PvzceIds.MECHANIC_TOOL)) {
                if (data instanceof ToolData tool) {
                    found.add(tool);
                }
            }
            return java.util.List.copyOf(found);
        });
    }
}
