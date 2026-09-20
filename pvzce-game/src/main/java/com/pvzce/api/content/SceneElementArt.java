package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

import java.util.Optional;

/**
 * How a scene element is drawn, when the id-derived texture is not the whole answer.
 *
 * <p>Most elements need none of this: {@code pvzce:grave} is drawn from
 * {@code textures/scene/grave.png} at exactly one cell, and the convention says so without a
 * line of data. Two things the convention cannot say are here:
 *
 * <ul>
 *   <li><b>a sprite that is not one cell big.</b> The original's crater is 90x61 pixels - wider
 *       than the 80x100 cell it belongs to and shorter than it - so drawing it into a cell
 *       squashes it. {@code width}/{@code height} are in cells and the art is centred on the
 *       cell, which is what a decal like a hole in the lawn wants;</li>
 *   <li><b>a tile to sit on.</b> A scene cell holds one element, so an element that is drawn
 *       *over* another one has to say which: a gravestone stands on the lawn, and the crater
 *       is a hole in it. {@code underlay} names that element, drawn at one cell first. It is
 *       an element id rather than a texture so the same rules apply to it as to the cell:
 *       a resource pack that restyles the lawn restyles what is under a tombstone too, and a
 *       level that hides the lawn (see {@code LevelDef.hiddenSceneElements}) hides it there as
 *       well - which is the whole point for a level whose backdrop already has one;</li>
 *   <li><b>a variant per time of day.</b> The original draws the crater twice, once for a lawn
 *       in daylight and once for one after dark, and the hole filling back in as a second such
 *       pair. A level's sky decides which is drawn, so the choice is not the element's (see
 *       {@code EntityTextures}). An element that is only ever one of the two - the fading crater
 *       - declares its own art as {@code texture} and its night art as {@code night_texture}, so
 *       "which state is this" never has to be said twice.</li>
 * </ul>
 *
 * <p>Every field is optional and an element that declares none is drawn by the convention, so
 * this costs nothing until something needs it.
 *
 * @param texture      the sprite for a lawn in daylight, or empty for the id-derived path
 * @param nightTexture the same drawing for a lawn after dark, or empty to reuse {@code texture}
 * @param underlay     the scene element this one is drawn on top of, or empty when it fills the
 *                     cell by itself
 * @param width        how wide the art is drawn, in cells, centred on the cell
 * @param height       and how tall
 */
public record SceneElementArt(
        Optional<Identifier> texture,
        Optional<Identifier> nightTexture,
        Optional<Identifier> underlay,
        float width,
        float height
) {
    /** The convention: one cell, one texture derived from the element's id. */
    public static final SceneElementArt NONE =
            new SceneElementArt(Optional.empty(), Optional.empty(), Optional.empty(), 1F, 1F);

    public static final Codec<SceneElementArt> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.optionalFieldOf("texture").forGetter(SceneElementArt::texture),
            Identifier.CODEC.optionalFieldOf("night_texture").forGetter(SceneElementArt::nightTexture),
            Identifier.CODEC.optionalFieldOf("underlay").forGetter(SceneElementArt::underlay),
            Codec.FLOAT.optionalFieldOf("width", 1F).forGetter(SceneElementArt::width),
            Codec.FLOAT.optionalFieldOf("height", 1F).forGetter(SceneElementArt::height)
    ).apply(i, SceneElementArt::new));

    public SceneElementArt {
        width = width <= 0F ? 1F : width;
        height = height <= 0F ? 1F : height;
    }

    /**
     * The texture to draw, falling back to the day one when there is no night art.
     *
     * <p>An absent variant is a pack's incomplete art, not a statement that the element is
     * invisible after dark: a crater whose night drawing was never made stays a crater. Empty
     * means "ask the convention", which is what an element with no declared texture answers.
     */
    public Optional<Identifier> textureFor(boolean night) {
        if (!night) {
            return texture;
        }
        return nightTexture.isPresent() ? nightTexture : texture;
    }
}
