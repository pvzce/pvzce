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
