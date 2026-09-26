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
    },

    /**
     * Digging a plant up gives a fifth of its sun back.
     *
     * <p>The shop's third item, and the only buff in the game that no level hands out: the other
     * three are rewards for reaching somewhere, and this one is bought. It exists because the
     * shovel is the one tool with no upside at all - it is how a player fixes a mistake, and
     * before this buff the fix cost the whole plant.
     *
     * <p>A fifth rather than a half: a generous refund turns "I misplanted" into "I am
     * rearranging my lawn for free", and the point of the buff is to take the sting out of a
     * mistake rather than to remove the cost of a decision.
     *
     * <p>Its icon is the original shovel with the sun bank's own disc on it, composed by
     * {@code tools/gen_sun_shovel_icon.py}. The project drew its own illustration for this one for
     * a while - a photorealistic shovel in a style nothing else in the game uses - which is the
     * reported "阳光铲的贴图错误".
     */
    SUN_SHOVEL(PvzceIds.BUFF_SUN_SHOVEL,
            Identifier.withDefaultNamespace("textures/gui/buff/sun_shovel")) {
        @Override
        public float shovelRefundFraction() {
            return SUN_SHOVEL_REFUND;
        }
    },

    /**
     * Planting a tangle kelp grows another one beside it.
     *
     * <p>The 3-9 reward. The pool's two rows are the part of a level the player has the least room
     * to answer - every water cell costs a lily pad and a plant - and this is the one reward that
     * gives the water back: one kelp becomes a patch of it, for as long as there is water next to
     * the last one and nothing already growing there.
     *
     * <p>The same rule the mutation of the same name applies on a timer; the two share
     * {@code KelpSpread.spreadFrom}, so "next to" has one definition.
     */
    KELP_SPREAD(PvzceIds.BUFF_KELP_SPREAD,
            Identifier.withDefaultNamespace("textures/gui/buff/kelp_spread")) {
        @Override
        public boolean spreadsKelp() {
            return true;
        }
    };

    /** What "1.5x" is, in one place: the codec's default and the buff's answer agree. */
    public static final float MUSHROOM_RANGE_FACTOR = 1.5F;
    /** How much of a plant's price digging it up returns while {@link #SUN_SHOVEL} is on. */
    public static final float SUN_SHOVEL_REFUND = 0.20F;
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
