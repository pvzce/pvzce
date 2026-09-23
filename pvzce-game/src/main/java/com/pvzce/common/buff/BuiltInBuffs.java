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
            Identifier.withDefaultNamespace("textures/resource/sun")) {
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
            Identifier.withDefaultNamespace("textures/gui/cards/puff_shroom")) {
        @Override
        public float mushroomRangeMultiplier() {
            return MUSHROOM_RANGE_FACTOR;
        }
    };

    /** What "1.5x" is, in one place: the codec's default and the buff's answer agree. */
    public static final float MUSHROOM_RANGE_FACTOR = 1.5F;

    private final Identifier id;
    private final LevelBuff.BuffIcon icon;

    BuiltInBuffs(Identifier id, Identifier texture) {
        this.id = id;
        // Whole-texture icons: the two placeholder sprites are already a single object each, and
        // a sub-rectangle would be a guess about art that is going to be replaced anyway.
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
