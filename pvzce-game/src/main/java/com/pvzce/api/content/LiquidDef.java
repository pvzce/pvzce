package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.util.MathUtil;

import java.util.Optional;

/**
 * A rendered liquid surface (water, and - through the same code path - lava,
 * swamp water, acid...).
 *
 * <p>This record holds only the numbers the liquid shader needs. It deliberately
 * does NOT reference the scene element that uses it: a scene element names a
 * liquid id through {@link SceneElementDef#liquid()}, so several elements can
 * share one look and a resource pack can restyle a liquid without touching the
 * scene data, and a mod can add a liquid without writing code.
 *
 * <p>Every field has a default, so a data file only lists what it wants to
 * change:
 *
 * <pre>{@code
 * {
 *   "id": "pvzce:water",
 *   "shallow_color": "#7FB6BE80",
 *   "deep_color": "#12405C",
 *   "wave_speed": 0.06,
 *   "caustics": 0.35
 * }
 * }</pre>
 *
 * <p>Colours are authored as {@code #RGB}, {@code #RRGGBB} or {@code #RRGGBBAA}
 * because a JSON author reads a hex colour far more easily than four floats, and
 * they are converted once here rather than at every draw.
 *
 * @param id          registry id
 * @param baseTexture texture id for the sea floor, or empty to use flat colours
 *                    (a liquid whose {@code shallow_color} alone carries the look)
 * @param shallowColor water colour near the shore; the texture shows through
 * @param deepColor   water colour in the middle of the body
 * @param opacity     how much of the water colour covers the texture, 0..1
 * @param depthScale  world cells over which shallow fades into deep
 * @param foam        shoreline foam: colour and band width
 * @param wave        surface motion: scroll speed, strength and spatial density
 * @param caustics    caustics strength
 * @param reflectColor ambient / sky colour mixed in by the fresnel term
 * @param fresnel     how strongly the horizon reflection takes over at grazing angles
 * @param specular    sun and moon glitter strength
 * @param specularPower highlight tightness
 * @param staticFrames how many baked frames the non-shader fallback cycles through
 */
public record LiquidDef(
        Identifier id,
        Optional<Identifier> baseTexture,
        float[] shallowColor,
        float[] deepColor,
        float opacity,
        float depthScale,
        float baseScale,
        FoamStyle foam,
        WaveShape wave,
        float caustics,
        float[] reflectColor,
        float fresnel,
        float specular,
        float specularPower,
        int staticFrames
) {
    /**
     * Texture used when {@link #baseTexture} is empty: a tiling pool floor.
     *
     * <p>The shipped water names {@link #WATER_SURFACE_TEXTURE} instead - the original's own
     * surface drawing - so this is what a liquid that says nothing about its base gets, and what
     * the built-in fallback used before the surface art arrived.
     */
    public static final Identifier DEFAULT_BASE_TEXTURE =
            Identifier.withDefaultNamespace("textures/scene/water_base");

    /**
     * The original's pool surface, padded into a square tile by
     * {@code tools/gen_water_surface.py}.
     *
     * <p>It is the whole basin in one drawing: a 705 px strip of soft cyan clouds drawn once
     * across the water, which {@link #WATER_SURFACE_SCALE} turns into "one tile per nine cells"
     * so a cell at row 2 samples exactly the strip the original puts there. See that tool for
     * the arithmetic and for why the rip's own black rim is cropped away.
     */
    public static final Identifier WATER_SURFACE_TEXTURE =
            Identifier.withDefaultNamespace("textures/scene/water_surface");

    /** One tile per nine cells: the strip's width in cells. See {@link #WATER_SURFACE_TEXTURE}. */
    public static final float WATER_SURFACE_SCALE = 1F / 9F;

    /**
     * How many texture tiles fit in one world cell, i.e. the base texture's scale.
     *
     * <p>The shipped default is deliberately denser than one tile per lawn. A tile that
     * spans five cells is magnified on screen, and a tile is never perfectly uniform -
     * the source's broad light and dark patches come with it, and at this magnification
     * one of them is several cells wide and reads as a band lying on the water. Repeating
     * the tile more often shrinks every feature with it, so the same patch becomes fine
     * texture instead of a stripe. See {@code tools/gen_water_base.py}, which also
     * flattens the tile's low-frequency luminance for the same reason.
     */
    public static final float DEFAULT_BASE_SCALE = 1F / 3.5F;

    /** Fallback frames baked by {@code tools/gen_water_frames.py}. */
    public static final String STATIC_FRAME_PREFIX = "textures/scene/water_frames/";

    private static final Codec<float[]> COLOR = Codec.STRING.xmap(
            LiquidDef::parseColor, LiquidDef::formatColor);

    public static final Codec<LiquidDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(LiquidDef::id),
            Identifier.CODEC.optionalFieldOf("base_texture").forGetter(LiquidDef::baseTexture),
            COLOR.optionalFieldOf("shallow_color", parseColor("#7FB6BE80")).forGetter(LiquidDef::shallowColor),
            COLOR.optionalFieldOf("deep_color", parseColor("#12405C")).forGetter(LiquidDef::deepColor),
            Codec.floatRange(0F, 1F).optionalFieldOf("opacity", 0.82F).forGetter(LiquidDef::opacity),
            Codec.floatRange(0.05F, 64F).optionalFieldOf("depth_scale", 2F).forGetter(LiquidDef::depthScale),
            Codec.floatRange(1F / 64F, 4F).optionalFieldOf("base_scale", DEFAULT_BASE_SCALE)
                    .forGetter(LiquidDef::baseScale),
            FoamStyle.CODEC.optionalFieldOf("foam", FoamStyle.DEFAULT).forGetter(LiquidDef::foam),
            WaveShape.CODEC.optionalFieldOf("wave", WaveShape.DEFAULT).forGetter(LiquidDef::wave),
            Codec.floatRange(0F, 2F).optionalFieldOf("caustics", 0.45F).forGetter(LiquidDef::caustics),
            COLOR.optionalFieldOf("reflect_color", parseColor("#9FC7E8")).forGetter(LiquidDef::reflectColor),
            Codec.floatRange(0F, 1F).optionalFieldOf("fresnel", 0.35F).forGetter(LiquidDef::fresnel),
            Codec.floatRange(0F, 4F).optionalFieldOf("specular", 1.0F).forGetter(LiquidDef::specular),
            Codec.floatRange(1F, 256F).optionalFieldOf("specular_power", 48F).forGetter(LiquidDef::specularPower),
            Codec.intRange(1, 16).optionalFieldOf("static_frames", 4).forGetter(LiquidDef::staticFrames)
    ).apply(i, LiquidDef::new));

    public LiquidDef {
        shallowColor = clampColor(shallowColor);
        deepColor = clampColor(deepColor);
        reflectColor = clampColor(reflectColor);
    }

    /**
     * Shoreline foam.
     *
     * <p>The two values travel together - a wider band wants a paler colour - and
     * grouping them keeps {@link LiquidDef} inside {@code RecordCodecBuilder}'s
     * sixteen-field limit without flattening the JSON into numbered names.
     */
    public record FoamStyle(float[] color, float width) {
        public static final FoamStyle DEFAULT = new FoamStyle(parseColor("#EAF7F2"), 0.15F);

        public static final Codec<FoamStyle> CODEC = RecordCodecBuilder.create(i -> i.group(
                COLOR.optionalFieldOf("color", parseColor("#EAF7F2")).forGetter(FoamStyle::color),
                Codec.floatRange(0F, 0.5F).optionalFieldOf("width", 0.15F).forGetter(FoamStyle::width)
        ).apply(i, FoamStyle::new));

        public FoamStyle {
            color = clampColor(color);
        }
    }

    /** Surface motion. */
    public record WaveShape(float speed, float amplitude, float density) {
        public static final WaveShape DEFAULT = new WaveShape(0.055F, 0.55F, 2F);

        public static final Codec<WaveShape> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.floatRange(0F, 4F).optionalFieldOf("speed", 0.055F).forGetter(WaveShape::speed),
                Codec.floatRange(0F, 2F).optionalFieldOf("amplitude", 0.55F).forGetter(WaveShape::amplitude),
                Codec.floatRange(0.1F, 64F).optionalFieldOf("density", 2F).forGetter(WaveShape::density)
        ).apply(i, WaveShape::new));
    }

    /**
     * Parses {@code #RGB}, {@code #RRGGBB} or {@code #RRGGBBAA} into r,g,b,a.
     *
     * <p>Falls back to opaque white on a malformed value rather than throwing: a
     * typo in a resource pack should not take the renderer down, and the colour is
     * visible enough that a wrong value is obvious immediately.
     */
    public static float[] parseColor(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("#")) {
            text = text.substring(1);
        }
        try {
            int r;
            int g;
            int b;
            int a = 255;
            switch (text.length()) {
                case 3 -> {
                    r = Integer.parseInt(text.substring(0, 1), 16) * 17;
                    g = Integer.parseInt(text.substring(1, 2), 16) * 17;
                    b = Integer.parseInt(text.substring(2, 3), 16) * 17;
                }
                case 6, 8 -> {
                    r = Integer.parseInt(text.substring(0, 2), 16);
                    g = Integer.parseInt(text.substring(2, 4), 16);
                    b = Integer.parseInt(text.substring(4, 6), 16);
                    if (text.length() == 8) {
                        a = Integer.parseInt(text.substring(6, 8), 16);
                    }
                }
                default -> {
                    return new float[]{1F, 1F, 1F, 1F};
                }
            }
            return new float[]{r / 255F, g / 255F, b / 255F, a / 255F};
        } catch (NumberFormatException e) {
            return new float[]{1F, 1F, 1F, 1F};
        }
    }

    private static String formatColor(float[] color) {
        StringBuilder text = new StringBuilder("#");
        for (int index = 0; index < color.length; index++) {
            int channel = Math.round(MathUtil.clamp01(color[index]) * 255F);
            text.append(String.format("%02X", channel));
        }
        return text.toString();
    }

    private static float[] clampColor(float[] color) {
        if (color == null || color.length != 4) {
            return new float[]{1F, 1F, 1F, 1F};
        }
        return new float[]{
                MathUtil.clamp01(color[0]), MathUtil.clamp01(color[1]),
                MathUtil.clamp01(color[2]), MathUtil.clamp01(color[3])};
    }

    /** The texture this liquid draws, defaulting when the data file names none. */
    public Identifier resolvedBaseTexture() {
        return baseTexture.orElse(DEFAULT_BASE_TEXTURE);
    }

    /** Texture id of one baked fallback frame ({@code frame} is wrapped by the caller). */
    public Identifier staticFrameTexture(int frame) {
        int count = Math.max(1, staticFrames);
        return Identifier.withDefaultNamespace(STATIC_FRAME_PREFIX + id.path() + "_" + Math.floorMod(frame, count));
    }
}
