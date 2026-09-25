package com.pvzce.common.buff;

import com.pvzce.api.content.LevelBuff;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;

/**
 * The built-in level buffs.
 *
 * <p>An enum rather than two classes and a factory: each of these is a policy with no
 * parameters, and a singleton is exactly what that is. A buff that needs configuration would
 * be a class with a codec, registered beside these two - the registry is keyed by
 * {@link Identifier} and does not care which it gets.
 *
 * <p>Both are deliberately small. The larger a buff is, the more it is really a level
 * mechanic and belongs in {@code mechanics}; these two are "how this run is played" knobs the
 * player owns.
 */
public enum BuiltInBuffs implements LevelBuff {
    /**
     * Sun and coins are picked up on their own, shortly after they land.
     *
     * <p>A quality-of-life buff for the player who finds clicking each coin tedious. It does not
     * skip a level's collection rules (see {@link #autoCollectsResources()}), so in a level
     * whose sun needs its card it still needs its card.
     */
    AUTO_COLLECT(PvzceIds.BUFF_AUTO_COLLECT,
            Identifier.withDefaultNamespace("textures/gui/buff/auto_collect")) {
        @Override
        public boolean autoCollectsResources() {
            return true;
        }
    },

    /**
     * Spore-shooting mushrooms reach half again as far.
     *
     * <p>The scarecrow-shroom's reach is unlimited, and a multiplier on "the whole board" is
     * still the whole board - which is the correct answer, not a bug in the arithmetic.
     */
    MUSHROOM_RANGE(PvzceIds.BUFF_MUSHROOM_RANGE,
            Identifier.withDefaultNamespace("textures/gui/buff/mushroom_range")) {
        @Override
        public float mushroomRangeMultiplier() {
            return MUSHROOM_RANGE_FACTOR;
        }
    },

    /**
     * The fog is a column and a half shorter.
     *
     * <p>World 4's reward. The whole level is played behind a fog the player cannot shorten, so
     * the thing worth handing out at the end of it is the ability to shorten it - which is also
     * what makes the buff's value legible: the player has nine levels of "I wish I could see
     * further" behind them when this arrives.
     *
     * <p>A distance rather than a multiplier, and small on purpose: 1.5 columns is a third of the
     * visible span on a nine-column board. Enough to change how a lane is defended, not enough to
     * make the fog not exist.
     */
    FOG_RETREAT(PvzceIds.BUFF_FOG_RETREAT,
            Identifier.withDefaultNamespace("textures/gui/buff/fog_retreat")) {
        @Override
        public float fogRetreat() {
            return FOG_RETREAT_COLUMNS;
        }
    };

    /** What "1.5x" is, in one place: the codec's default and the buff's answer agree. */
    public static final float MUSHROOM_RANGE_FACTOR = 1.5F;
    /** How many columns the fog retreats while {@link #FOG_RETREAT} is on. */
    public static final float FOG_RETREAT_COLUMNS = 1.5F;

    private final Identifier id;
    private final LevelBuff.BuffIcon icon;

    BuiltInBuffs(Identifier id, Identifier texture) {
        this.id = id;
        // Whole-texture icons: each is a single 64x64 object drawn for its own buff, so there is
        // no atlas rectangle to name. They used to be borrowed - the resource directory's sun and
        // a puff-shroom card face - which meant the icon row said nothing about what either buff
        // did; `tools/gen_buff_icons.py` draws one per buff instead.
        this.icon = LevelBuff.BuffIcon.of(texture);
    }

    @Override
    public Identifier id() {
        return id;
    }

    @Override
    public LevelBuff.BuffIcon icon() {
        return icon;
    }
}
