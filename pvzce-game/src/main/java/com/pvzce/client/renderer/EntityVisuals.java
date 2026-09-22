package com.pvzce.client.renderer;

import com.pvzce.api.entity.EntityKind;
import com.pvzce.api.entity.EntityLayers;

/**
 * Per-kind visual constants for entities.
 *
 * <p>These numbers - the feet-to-anchor lift, the fallback sprite box and the
 * render layer - were written out in three separate per-kind switches: the
 * animation manager's {@code anchor}/{@code baseZ}, the in-game sprite fallback,
 * and the render order. Two of the five copies did not even agree on which kinds
 * exist (the animation binding lookup silently omitted resource drops), so a
 * resource drop could not use an animation override and any new kind meant editing
 * five switches with no compiler help. One table, one place to add a kind.
 */
public final class EntityVisuals {
    /**
     * @param anchorLift  feet-to-animation-anchor offset, in world cells
     * @param spriteWidth fallback sprite width, in world cells
     * @param spriteHeight fallback sprite height, in world cells
     * @param spriteOffsetX how far the sprite extends left of the entity centre
     * @param spriteOffsetY how far the sprite extends below the anchor
     * @param baseZ       render layer
     * @param sortBucket  coarse draw-order bucket ({@code Integer.MIN_VALUE} = normal)
     */
    public record Visuals(float anchorLift, float spriteWidth, float spriteHeight,
                          float spriteOffsetX, float spriteOffsetY, float baseZ, int sortBucket) {
    }

    private static final Visuals DEFAULT =
            new Visuals(0F, 0.7F, 0.7F, 0.35F, 0F, 0.2F, Integer.MIN_VALUE);

    private static final Visuals PLANT = new Visuals(0.38F, 0.76F, 0.76F, 0.38F, 0.38F, 0.2F, 0);
    private static final Visuals ZOMBIE = new Visuals(0.36F, 0.7F, 0.95F, 0.38F, 0.36F, 0.15F, 10);
    private static final Visuals PROJECTILE = new Visuals(0.10F, 0.24F, 0.24F, 0.12F, 0.10F, 0.3F, 20);
    /** Drops drawn from a flat sprite rather than an animation (no shipped drop is). */
    private static final Visuals RESOURCE = new Visuals(0.16F, 0.40F, 0.40F, 0.20F, 0.16F, 0.35F, 30);
    /**
     * How big a drop is drawn, whatever it is drawn from.
     *
     * <p>This is the number that mattered: the sun's own animation is authored at 0.56
     * cells, so it read as a speck in a 1-cell lawn square - and a speck the player has to
     * click. {@link com.pvzce.client.animation.AnimationManager} scales an animated drop by
     * {@code dropWidth / its own width}, so one number here resizes the art.
     *
     * <p>The factor is uniform in both axes, so this is the drop's real on-screen width
     * <em>and</em> height in cells: an 0.8-cell sun is a circle that covers four fifths of
     * a lawn square. It used to be applied to the width alone, which made the circle art
     * arrive as an ellipse 1.7x wider than tall; see
     * {@code AnimationManager#scalesFor} for the full story.
     *
     * <p>0.8 cells: the sun is the one thing on the board the player has to click, so it
     * should read as a distinct object rather than a speck. This is the size a drop is
     * drawn at when its definition declares no {@code render_scale}; the sun declares
     * {@code 1.2} on top of it, because it is the drop the player has to aim at.
     */
    private static final Visuals DROP = new Visuals(0.38F, 0.8F, 0.8F, 0.4F, 0.38F, 0.35F, 30);
    /**
     * The size, in cells, that a drop's own art is authored at.
     *
     * <p>A drop is drawn exactly as big as its model says (times
     * {@code render_scale}), so the converter has to author the art at some agreed size or
     * nothing lines up: {@code tools/reanim_to_pvzce_all.py} fits each drop's reanim to this
     * box, and {@link com.pvzce.client.animation.AnimationManager} then applies nothing but
     * {@code render_scale} on top. The two halves are one contract, which is why the number
     * is stated once, here, and read by the tooling's own test.
     */
    public static final float AUTHORED_DROP_CELLS = 0.34F;

    /**
     * The colour a drop is drawn in.
     *
     * <p>The number lives on the resource ({@link com.pvzce.api.content.ResourceDef.DropTint}),
     * because it is a property of that drop's art rather than of "drops": a sun's own animation
     * stacks two pale halos additively over a saturated yellow core, and the tint that keeps it
     * yellow is not the one a coin wants. This is only the lookup, with the definition's own
     * default standing in for a drop whose resource the client cannot resolve.
     */
    public static float[] dropTint(String defId) {
        com.pvzce.api.util.Identifier id = com.pvzce.api.util.Identifier.tryParse(defId);
        com.pvzce.api.content.ResourceDef def = id == null
                ? null : com.pvzce.common.core.BuiltInRegistries.RESOURCES.get(id);
        com.pvzce.api.content.ResourceDef.DropTint tint = def == null
                ? com.pvzce.api.content.ResourceDef.DropTint.DEFAULT : def.tint();
        return new float[]{tint.r(), tint.g(), tint.b()};
    }

    /** Underground zombies are drawn under the lawn rather than among the entities. */
    public static final int UNDERGROUND_SORT_BUCKET = -100;

    /**
     * The colour a slowed zombie is drawn in.
     *
     * <p>The original's frozen zombie is the same art under a cold wash, and a per-channel
     * multiply is the cheapest way to say that: red and green come down, blue goes up, and
     * the black outline and the white highlights survive because multiplying by a colour
     * cannot invent detail. It is deliberately not a white-blue blend - a blend would wash
     * the sprite towards flat blue and lose the shading that makes it read as a zombie.
     *
     * <p>Only {@code slow} wears it (see {@code ZombieEntity#chilled}), and the same tint
     * covers every slowed zombie however it was slowed.
     */
    public static final float CHILLED_TINT_R = 0.62F;
    public static final float CHILLED_TINT_G = 0.88F;
    public static final float CHILLED_TINT_B = 1.45F;

    /**
     * The colour a charmed zombie is drawn in.
     *
     * <p>The original turns one purple and leaves its art alone, which is also the only
     * affordable answer here: there is no second sprite set for a charmed Buckethead, and
     * there should not be - "whose side is this" is a state, and a state belongs in a tint
     * rather than in another copy of every model. Purple because it is what the original uses
     * and because it is unmistakably not the frozen blue.
     */
    public static final float CHARMED_TINT_R = 1.35F;
    public static final float CHARMED_TINT_G = 0.72F;
    public static final float CHARMED_TINT_B = 1.30F;

    /**
     * The ice the lawn freezes a held zombie in.
     *
     * <p>The original's own {@code icetrap} drawing - the same one its freeze particles use -
     * placed at the zombie's feet, so "this one cannot move" is visible from across the board
     * and not only from the tint. The size is the art's own: 54x30 pixels against the 80x100
     * pixel cell the original draws on, which is what makes it sit around a zombie rather than
     * over the whole lawn square.
     */
    public static final com.pvzce.api.util.Identifier FROZEN_SPIKES_TEXTURE =
            com.pvzce.api.util.Identifier.withDefaultNamespace("textures/entities/status/frozen_spikes");
    public static final float FROZEN_SPIKES_WIDTH = 0.675F;
    public static final float FROZEN_SPIKES_HEIGHT = 0.375F;
    /**
     * Where the ice is drawn, between the shadow (0.04) and the zombie itself (0.15).
     *
     * <p>Under the zombie on purpose: the spikes are around its feet, so its legs have to come
     * down into them.
     */
    public static final float FROZEN_SPIKES_Z = 0.14F;

    /**
     * How much of the night tint a plant or a zombie is allowed to cancel.
     *
     * <p>The scene tint is a multiply applied to everything the world shader draws, and it
     * has to say "night" - but it says it about the lawn, the street and the house, while the
     * plants and zombies are what the player is reading the board by. At full night strength
     * the lane turns into silhouettes.
     *
     * <p>This is a <em>fraction of the tint to undo</em> rather than a brightness multiplier,
     * which matters: undoing part of a blue tint means undoing more red than blue, so the
     * entity keeps its own colours and simply looks less night-lit than the grass it stands
     * on. Multiplying every channel by the same number would leave a zombie in a blue scene
     * looking blue as well - brighter, but no more legible.
     *
     * <p>Presentation only, and applied through the entity ink stack, so it never touches the
     * scene, the HUD or a drop (see {@link #liftsAtNight}).
     */
    public static final float NIGHT_LIFT = 0.55F;

    /**
     * Whether this kind is drawn brighter as the scene darkens.
     *
     * <p>Plants and zombies only. A drop is meant to glow on its own account - the sun is
     * the board's only light and is deliberately kept from blowing out (see
     * {@link #dropTint}) - and a projectile is small enough that lifting it would only make
     * it look like it belonged to a different scene.
     */
    public static boolean liftsAtNight(String kind) {
        return EntityKind.PLANT.equals(kind) || EntityKind.ZOMBIE.equals(kind);
    }

    public static Visuals of(String kind) {
        return switch (kind == null ? "" : kind) {
            case EntityKind.PLANT -> PLANT;
            case EntityKind.ZOMBIE -> ZOMBIE;
            case EntityKind.PROJECTILE -> PROJECTILE;
            case EntityKind.RESOURCE -> DROP;
            default -> DEFAULT;
        };
    }

    /** Vertical offset from an entity's feet to its animation anchor. */
    public static float anchorLift(String kind) {
        return of(kind).anchorLift();
    }

    /** Render layer for a kind. */
    public static float baseZ(String kind) {
        return of(kind).baseZ();
    }

    /** Coarse draw-order bucket, honouring the underground layer. */
    public static int sortBucket(String kind, int layer) {
        if (EntityLayers.UNDERGROUND == layer) {
            return UNDERGROUND_SORT_BUCKET;
        }
        int bucket = of(kind).sortBucket();
        return bucket == Integer.MIN_VALUE ? 5 : bucket;
    }

    /**
     * The single draw-order key for an entity: back rows first, then kind, then spawn order.
     *
     * <p>The lawn is drawn as 2D sprites with no depth test, so "in front" is entirely the
     * order they are submitted in - and the order used to be kind alone, which means every
     * plant on the board was painted before every zombie and a zombie in the front row drew
     * <em>under</em> a plant four rows behind it. Row is the first key because it is what the
     * projection means by depth: {@code y} is how far up the screen a cell sits, and the
     * further up, the further away.
     *
     * <p>The kind bucket stays as the second key, because two entities in one cell still have
     * a right answer: a zombie eating a plant covers it. Spawn id is last, so the order is
     * total and identical between frames - a tie broken by iteration order would make sprites
     * flicker against each other.
     *
     * <p>Underground entities sort before everything in their row rather than in a bucket of
     * their own: a buried zombie is drawn under the lawn, and the lawn is in every row.
     */
    public static long renderOrder(String kind, int layer, int row, int id) {
        // Back rows first, then kind, then spawn order - which is what this used to be, and it
        // was wrong for the one kind of entity that is not *on* the lawn. A row is depth on this
        // board, so leading with it means a sun falling in row 4 is painted after a peashooter
        // standing in row 2 and therefore drawn on top of it - fine - but the same rule puts a
        // sun falling in row 0 *under* a plant three rows nearer the player, and a drop the
        // player has to click must never be hidden by the scenery.
        //
        // So the leading key is not the row but whether the entity belongs to the lawn at all:
        // plants and zombies are placed in it and sort among themselves by row; a drop or a
        // projectile is above it and sorts after all of them. Within a band the row still leads,
        // because that is still what depth means.
        //
        // Bit budget: 1 band + 10 row + 10 bucket (it spans -100..30 today) + 32 id.
        int band = isAboveTheLawn(kind) ? 1 : 0;
        long bucket = sortBucket(kind, layer);
        return ((long) band << 58)
                | ((long) (row & 0x3FF) << 48)
                | ((bucket + 512L & 0x3FF) << 38)
                | (id & 0xFFFFFFFFL);
    }

    /**
     * Whether this kind is drawn above the board rather than standing on it.
     *
     * <p>Drops and projectiles are already given the highest {@code baseZ} in the table for the
     * same reason; this is that decision applied to the draw order, which is the one that
     * actually decides what covers what - the z a vertex carries is not read by anything while
     * depth testing is off.
     */
    private static boolean isAboveTheLawn(String kind) {
        return EntityKind.RESOURCE.equals(kind) || EntityKind.PROJECTILE.equals(kind);
    }

    private EntityVisuals() {
    }
}
