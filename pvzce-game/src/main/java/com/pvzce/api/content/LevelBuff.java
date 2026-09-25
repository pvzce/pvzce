package com.pvzce.api.content;

import com.pvzce.api.util.Identifier;

/**
 * A level buff: one opt-in modifier the player switches on before a run, drawn as a small
 * icon in the corner of the board.
 *
 * <p>Modelled after {@link com.pvzce.common.level.mechanic.LevelMechanic} in shape
 * (code-registered behaviour, referenced from data by id) but not in scope: a mechanic is the
 * level deciding what kind of level it is, a buff is the <em>player</em> deciding how this run
 * is played. The level still has the last word - it may hand out a buff nobody can refuse
 * ({@code LevelDef.buffs}) and it may cap how many may be switched on - but the list a player
 * fills is theirs.
 *
 * <p>Code-registered rather than data-driven for the same reason the capability registries
 * are: a buff is behaviour, and a JSON block describing one could only ever describe a
 * parameter set the code already understands. {@code common.LevelBuffs} holds the two built-in
 * implementations and registers them; a mod registers its own against
 * {@code PvzceRegistries.LEVEL_BUFFS} before level data loads.
 *
 * <p>The interface is deliberately tiny. A buff that needs per-tick behaviour, a range
 * multiplier or a collection rule implements exactly the hook it uses, and everything else
 * stays out of the hot path: {@link #mushroomRangeMultiplier()} is read once per shot,
 * {@link #autoCollectsResources()} once per second.
 */
public interface LevelBuff {
    /** True for the two hooks below; the registered implementations are enums. */
    default Identifier id() {
        return null;
    }

    /**
     * The sprite the chooser and the in-game icon draw, plus what hovering it says.
     *
     * <p>Part of the buff rather than of a client table because it is the buff's own face: a
     * mod's buff draws a mod's texture, and the client never has to be told about it. The
     * <em>name</em> is not here - it goes through {@code GuiLang} like every other display
     * name in the project, keyed {@code <namespace>.<path>} off {@link #id()}.
     */
    default BuffIcon icon() {
        return BuffIcon.NONE;
    }

    /**
     * Multiplies the travel range of every shot fired by a spore-shooting mushroom.
     *
     * <p>Applies to the two halves of the same fact - when the plant thinks a target is worth
     * firing at, and when the shot expires - because they are one number in
     * {@link ProjectileRef#range}. A shot that outranges its own plant's reach is the bug this
     * exists to avoid.
     *
     * <p>Read per shot rather than baked into the plant, so a buff switched on mid-run (a
     * command, a mod) takes effect on the next shot instead of only on the next plant.
     */
    default float mushroomRangeMultiplier() {
        return 1F;
    }

    /**
     * True when resource drops are collected without being clicked.
     *
     * <p>The level's own collection rules still apply in full - a drop whose definition says
     * it needs a card still needs one - because the buff changes <em>who reaches for it</em>,
     * not what the level hands out.
     */
    default boolean autoCollectsResources() {
        return false;
    }

    /**
     * How many columns further right the fog's boundary sits while this buff is on.
     *
     * <p>Zero for every buff that has nothing to say about fog, which is all of them but the one
     * the world-4 reward hands out. A distance in columns rather than a multiplier: "the fog is a
     * column and a half shorter" is what the player was promised, and a multiplier would mean
     * something different on a nine-column board than on a six-row one.
     *
     * <p>Read by {@code LevelServer.fogData()}, which is the single place the fog's final span is
     * decided - so a mutation, a lamp and this buff all end up in one answer rather than three
     * that have to be reconciled by the renderer.
     */
    default float fogRetreat() {
        return 0F;
    }

    /** A texture and, optionally, the sub-rectangle of it this icon occupies. */
    record BuffIcon(Identifier texture, float u0, float v0, float u1, float v1) {
        /** No icon: the chooser draws the level's placeholder and the tooltip still names it. */
        public static final BuffIcon NONE = new BuffIcon(null, 0F, 0F, 1F, 1F);

        public BuffIcon {
            u0 = clamp01(u0);
            v0 = clamp01(v0);
            u1 = clamp01(u1);
            v1 = clamp01(v1);
            if (u1 <= u0) {
                u1 = Math.min(1F, u0 + 1F);
            }
            if (v1 <= v0) {
                v1 = Math.min(1F, v0 + 1F);
            }
        }

        /** The whole texture. */
        public static BuffIcon of(Identifier texture) {
            return new BuffIcon(texture, 0F, 0F, 1F, 1F);
        }

        public boolean isEmpty() {
            return texture == null;
        }

        private static float clamp01(float value) {
            return value < 0F ? 0F : Math.min(value, 1F);
        }
    }
}
