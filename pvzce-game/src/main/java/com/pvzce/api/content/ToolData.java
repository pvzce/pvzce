package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.api.util.Identifier;

import java.util.Optional;

/**
 * A tool a level hands the player on its own terms.
 *
 * <p>The block of the {@code pvzce:tool} level mechanic. A tool card's price and cooldown
 * belong to the tool (see {@link ToolDef}), but a level may need them to be different: the
 * original's mallet is free and has no recharge in Whack-a-Zombie, while the same mallet is a
 * shop item elsewhere. That is a property of the level rather than of the hammer, so it is
 * declared here instead of being baked into the tool definition - and a level can also hand the
 * tool to a player who has not unlocked the card, which is what 2-5 does with the mallet.
 *
 * <pre>
 * { "type": "pvzce:tool", "tool": "pvzce:hammer", "default": true,
 *   "cooldown": 0, "cost": { "resources": {} } }
 * </pre>
 *
 * <p><strong>{@code default}</strong> makes the tool the level's plain click: with no card in
 * hand, clicking a cell uses this tool instead of being ignored. It is not a card - it takes no
 * slot and no card cooldown - it is what the cursor is doing in this level when it is not doing
 * something else.
 *
 * <p>A level may declare several blocks. At most one may be the default; the validator says so,
 * because "what does an empty click use" has to have one answer.
 *
 * @param tool          which registered tool this block is about
 * @param isDefault     true = a click with no card selected uses it
 * @param cooldownTicks how long the tool waits before it can be used again, in ticks,
 *                      overriding the tool's own number; {@link #KEEP_TOOL_COOLDOWN} (the
 *                      unwritten value) keeps the tool's own
 * @param cost          the resource price of one use, overriding the tool's own; empty keeps
 *                      the tool's own, and a present-but-empty block means free
 */
public record ToolData(Identifier tool, boolean isDefault, int cooldownTicks,
                       Optional<ResourceCost> cost) implements MechanicData {
    /**
     * {@code cooldown} unwritten: use the tool's own number.
     *
     * <p>A sentinel rather than an {@link Optional} because the field is the cooldown of a
     * per-level default tool and a level that writes one always writes a number: -1 cannot
     * collide with a value an author would mean, while {@code "cooldown": 0} ("no recharge at
     * all") has to stay distinguishable from it.
     */
    public static final int KEEP_TOOL_COOLDOWN = -1;

    public static final MapCodec<ToolData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Identifier.CODEC.fieldOf("tool").forGetter(ToolData::tool),
            Codec.BOOL.optionalFieldOf("default", false).forGetter(ToolData::isDefault),
            Codec.INT.optionalFieldOf("cooldown", KEEP_TOOL_COOLDOWN).forGetter(ToolData::cooldownTicks),
            ResourceCost.CODEC.optionalFieldOf("cost").forGetter(ToolData::cost)
    ).apply(i, ToolData::new));

    public static final Codec<ToolData> CODEC = MAP_CODEC.codec();

    public ToolData {
        cooldownTicks = Math.max(KEEP_TOOL_COOLDOWN, cooldownTicks);
        cost = cost == null ? Optional.empty() : cost;
    }

    /** True when this block overrides the tool's own recharge. */
    public boolean overridesCooldown() {
        return cooldownTicks != KEEP_TOOL_COOLDOWN;
    }
}
