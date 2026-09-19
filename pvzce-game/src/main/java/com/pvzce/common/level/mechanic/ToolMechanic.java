package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.ResourceCost;
import com.pvzce.api.content.ToolData;
import com.pvzce.api.content.ToolDef;
import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Tools a level hands the player on its own terms.
 *
 * <p>Two things this exists for, both of them the original's:
 *
 * <ul>
 *   <li><strong>Whack-a-Zombie's mallet.</strong> In 2-5 the mallet is not a card at all: the
 *       cursor <em>is</em> the mallet, so clicking a zombie kills it with no seed packet, no
 *       sun and no recharge. That is {@code "default": true}.</li>
 *   <li><strong>A tool's numbers as the level's business.</strong> The same hammer card is a
 *       30-second, 50-sun purchase elsewhere, and a level may reprice it without a second tool
 *       definition - which is what {@code cooldown} and {@code cost} are for.</li>
 * </ul>
 *
 * <p>Nothing here is a card source: a level that declares this mechanic still has its ordinary
 * deck (or belt) beside it. The default tool is deliberately not a slot - it has no cooldown
 * bar, no price printed on a card and no place in the bar's order, because it is not one of the
 * things the player is choosing between.
 */
public final class ToolMechanic implements LevelMechanic<ToolData> {
    @Override
    public MapCodec<ToolData> codec() {
        return ToolData.MAP_CODEC;
    }

    @Override
    public List<String> validate(LevelDef def, ToolData data) {
        List<String> errors = new ArrayList<>();
        if (data.tool() == null || BuiltInRegistries.TOOLS.get(data.tool()) == null) {
            errors.add("tool mechanic names unknown tool '" + data.tool()
                    + "': the tool registry has no entry with that id, so nothing would be used");
        }
        return errors;
    }

    @Override
    public List<FieldSpec> editorFields() {
        // Written out rather than derived from ToolData: the labels are GuiLang keys, and the
        // editor draws the block it is handed.
        return List.of(
                FieldSpec.text("tool", "pvzce.mechanic.tool.field.tool"),
                FieldSpec.bool("default", "pvzce.mechanic.tool.field.default"),
                FieldSpec.integer("cooldown", "pvzce.mechanic.tool.field.cooldown",
                        ToolData.KEEP_TOOL_COOLDOWN, 12000));
    }

    /**
     * Every tool block this level declares, in order.
     *
     * <p>The read API for everything that asks "what tools does this level grant", so the
     * server's click handler, the client's click handler and the validator all walk the same
     * list instead of each scanning {@code def.mechanics()} themselves.
     */
    public static List<ToolData> declared(LevelDef def) {
        List<ToolData> tools = new ArrayList<>();
        for (TypedMechanic typed : def.mechanics()) {
            if (typed.is(PvzceIds.MECHANIC_TOOL) && typed.value() instanceof ToolData data) {
                tools.add(data);
            }
        }
        return List.copyOf(tools);
    }

    /**
     * The tool a click with no card in hand uses, or empty when this level has none.
     *
     * <p>The first default block wins if a level declares two; a level that says it twice still
     * plays, and "which one" cannot be a toss-up, so it is the declaration order.
     */
    public static Optional<ToolData> defaultTool(LevelDef def) {
        for (ToolData data : declared(def)) {
            if (data.isDefault()) {
                return Optional.of(data);
            }
        }
        return Optional.empty();
    }

    /** The tool definition a block names, or {@code null} when it names nothing registered. */
    public static ToolDef defOf(ToolData data) {
        return data == null || data.tool() == null ? null : BuiltInRegistries.TOOLS.get(data.tool());
    }

    /**
     * The recharge this level gives a tool, in ticks.
     *
     * <p>The block's own number when it wrote one, the tool's otherwise. Zero is a real answer -
     * Whack-a-Zombie's mallet never recharges - so this cannot be "the tool's unless the block's
     * is missing or zero": {@code overridesCooldown()} is what tells the two apart.
     */
    public static int cooldownTicks(ToolData data) {
        if (data == null) {
            return 0;
        }
        if (data.overridesCooldown()) {
            return Math.max(0, data.cooldownTicks());
        }
        ToolDef tool = defOf(data);
        return tool == null ? 0 : Math.max(0, tool.cooldownTicks());
    }

    /** What one use of a level-declared tool costs in sun; 0 when it is free. */
    public static int sunCost(ToolData data) {
        if (data == null) {
            return 0;
        }
        ResourceCost cost = data.cost().orElseGet(() -> {
            ToolDef tool = defOf(data);
            return tool == null ? ResourceCost.FREE : tool.useCost();
        });
        return cost.amountOf(PvzceIds.SUN);
    }
}
