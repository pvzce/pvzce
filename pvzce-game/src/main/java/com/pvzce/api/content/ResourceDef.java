package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

import java.util.Optional;

/**
 * A resource type (sun, redstone, energy bean, ...).
 *
 * <p>{@code icon} is the flat sprite the cards, the banks and the pickup animation
 * use. A resource that is a world drop may be drawn by an animation resource
 * instead, declared through {@code animation_dir} inside {@link AnimationBindings};
 * {@code texture} is the sprite fallback for that case, exactly as it is for the
 * other entity definitions.
 *
 * <p>{@code drop_motion} says how a drop arrives in the world, because the resources
 * do not all arrive the same way: sun falls from the sky, a sunflower's sun pops out
 * of the flower and settles, and coins burst out of the zombie that dropped them and
 * scatter. {@code rise_height} and {@code rise_scatter} tune that burst - how high it
 * goes and how far sideways it may be thrown.
 *
 * <p>{@code render_scale} is presentation only: the client draws this resource's art
 * {@code render_scale} times bigger (or smaller) than the size the art itself
 * declares. It changes nothing about hit boxes, collection radius or anything the
 * server simulates. See {@link ContentDefs#renderScale}.
 *
 * <p>{@code pickup_effect} is the effect this resource leaves behind when the player
 * picks it up, and it is per resource because a sun and a coin are not the same
 * pickup: the sun is the resource the whole game is played with and gets a flash to
 * match, while a coin is small change that should not wash the lawn in light. A
 * resource that declares nothing gets no effect at all - the collect sound and the
 * fly-to-bank animation are already the feedback.
 *
 * <p>{@code pickup_sound} is the same idea for the ear, and it exists for the same
 * reason: collecting a coin used to play the sun's chime, so a bowling combo - which
 * drops a coin per ricochet - sounded like a shower of sun. Coins ring; the sun keeps
 * the collect sound it always had.
 */
public record ResourceDef(
        Identifier id,
        int defaultValue,
        boolean collectible,
        Identifier icon,
        Identifier dropAnim,
        int maxStack,
        boolean collectibleWithoutCard,
        AnimationBindings animations,
        Optional<Identifier> texture,
        DropMotion dropMotion,
        float renderScale,
        Optional<Identifier> pickupEffect,
        Identifier pickupSound,
        float riseHeight,
        float riseScatter
) {
    /** The chime a collected resource plays unless it names its own. */
    public static final Identifier DEFAULT_PICKUP_SOUND =
            com.pvzce.common.PvzceSounds.UI_COLLECT;
    /**
     * How a drop gets from "spawned" to "on the ground".
     *
     * <p>All three used to fall from {@code START_HEIGHT}, so a coin punched out of a
     * zombie floated down from above the lawn and a sunflower's sun appeared two cells
     * over the flower that had just made it.
     */
    public enum DropMotion {
        /** Drops in from above and falls to the ground (the sky's sun). */
        FALL,
        /** Pops out of the plant that made it, rises, and settles back (a sunflower's sun). */
        RISE,
        /** Already on the ground when it appears (coins, pickups). */
        LANDED
    }

    /** How far a {@link DropMotion#RISE} drop floats up before settling, in cells. */
    public static final float RISE_HEIGHT = 0.5F;
    /**
     * How far sideways a {@link DropMotion#RISE} drop may be thrown, in cells.
     *
     * <p>Zero by default: a sunflower's sun belongs to the flower it came out of and settles
     * back onto it. A coin is the opposite case - several of them burst out of one zombie at
     * once, and a stack of identical arcs reads as one object copying itself, so they scatter.
     */
    public static final float DEFAULT_RISE_SCATTER = 0F;
    /** How long a {@link DropMotion#RISE} drop takes to the top, in ticks. */
    public static final int RISE_TICKS = 24;
    /** A resource that is only ever an icon: no animation, no separate sprite. */
    public ResourceDef(Identifier id, int defaultValue, boolean collectible, Identifier icon,
                       Identifier dropAnim, int maxStack, boolean collectibleWithoutCard) {
        this(id, defaultValue, collectible, icon, dropAnim, maxStack, collectibleWithoutCard,
                AnimationBindings.EMPTY, Optional.empty(), DropMotion.FALL, ContentDefs.DEFAULT_RENDER_SCALE,
                Optional.empty(), DEFAULT_PICKUP_SOUND, RISE_HEIGHT, DEFAULT_RISE_SCATTER);
    }

    public static final Codec<ResourceDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(ResourceDef::id),
            Codec.INT.optionalFieldOf("default_value", 25).forGetter(ResourceDef::defaultValue),
            Codec.BOOL.optionalFieldOf("collectible", true).forGetter(ResourceDef::collectible),
            Identifier.CODEC.optionalFieldOf("icon", Identifier.withDefaultNamespace("textures/resource/generic")).forGetter(ResourceDef::icon),
            Identifier.CODEC.optionalFieldOf("drop_anim", Identifier.withDefaultNamespace("sun_fall")).forGetter(ResourceDef::dropAnim),
            Codec.INT.optionalFieldOf("max_stack", 9990).forGetter(ResourceDef::maxStack),
            Codec.BOOL.optionalFieldOf("collectible_without_card", false).forGetter(ResourceDef::collectibleWithoutCard),
            AnimationBindings.MAP_CODEC.forGetter(ResourceDef::animations),
            Identifier.CODEC.optionalFieldOf("texture").forGetter(ResourceDef::texture),
            Codec.STRING.optionalFieldOf("drop_motion", "fall")
                    .xmap(ResourceDef::parseMotion, motion -> motion.name().toLowerCase(java.util.Locale.ROOT))
                    .forGetter(ResourceDef::dropMotion),
            ContentDefs.RENDER_SCALE_CODEC.forGetter(ResourceDef::renderScale),
            Identifier.CODEC.optionalFieldOf("pickup_effect").forGetter(ResourceDef::pickupEffect),
            Identifier.CODEC.optionalFieldOf("pickup_sound", DEFAULT_PICKUP_SOUND)
                    .forGetter(ResourceDef::pickupSound),
            Codec.FLOAT.optionalFieldOf("rise_height", RISE_HEIGHT).forGetter(ResourceDef::riseHeight),
            Codec.FLOAT.optionalFieldOf("rise_scatter", DEFAULT_RISE_SCATTER)
                    .forGetter(ResourceDef::riseScatter)
    ).apply(i, ResourceDef::new));

    /**
     * Reads {@code drop_motion}, falling back to {@code FALL} for anything unrecognised.
     *
     * <p>Silent on purpose: {@link com.pvzce.server.level.LevelValidator} reports the typo,
     * and until then the drop behaves like the classic sky sun rather than not spawning.
     */
    private static DropMotion parseMotion(String name) {
        if (name == null) {
            return DropMotion.FALL;
        }
        for (DropMotion motion : DropMotion.values()) {
            if (motion.name().equalsIgnoreCase(name.trim())) {
                return motion;
            }
        }
        return DropMotion.FALL;
    }

    /** True when this resource's sprite is doubled in size, for readability. */
    public static final float DROP_SPRITE_SCALE = 2F;
}
