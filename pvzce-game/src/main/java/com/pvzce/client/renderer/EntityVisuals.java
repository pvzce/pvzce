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
     * {@code AnimationManager.xScaleFor} for the full story.
     *
     * <p>0.8 cells: the sun is the one thing on the board the player has to click, so it
     * should read as a distinct object rather than a speck. This is the size a drop is
     * drawn at when its definition declares no {@code render_scale}; the sun declares
     * {@code 1.2} on top of it, because it is the drop the player has to aim at.
     */
    private static final Visuals DROP = new Visuals(0.38F, 0.8F, 0.8F, 0.4F, 0.38F, 0.35F, 30);
    /**
     * Drops are drawn dimmer than their art.
     *
     * <p>The sun's animation stacks several additive glow layers, and at the size above
     * they clipped to flat white - the sun read as a bright blob with no shape to it, which
     * is worse than small. Scaling the draw colour back keeps the shape readable; the alpha
     * is untouched, so it is not "more transparent", just less blown out.
     */
    public static final float DROP_TINT = 0.72F;
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
     * {@link #DROP_TINT}) - and a projectile is small enough that lifting it would only make
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

    private EntityVisuals() {
    }
}
