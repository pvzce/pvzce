package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

import java.util.List;
import java.util.Optional;

/**
 * A character who can speak in a level's opening dialogue.
 *
 * <p>Data-driven for the same reason plants and zombies are: a level only ever names a
 * character by id, so adding one is a file plus a folder of portraits, not a code change.
 * Everything a line does <em>not</em> say lives here instead of being repeated on every
 * line - the display name, where this character's portraits live, how large they are
 * drawn, and which speech bubble they talk through.
 *
 * <p>Authored as:
 * <pre>{@code
 * // data/<ns>/dialogue_characters/pea_chan.json
 * {
 *   "id": "pvzce:pea_chan",
 *   "name": "豌豆酱",
 *   "portrait_dir": "pvzce:textures/gui/dialogue/pea_chan",
 *   "scale": 1.0,
 *   "box_left": "pvzce:textures/gui/dialogue/box_left",
 *   "box_right": "pvzce:textures/gui/dialogue/box_right"
 * }
 * }</pre>
 *
 * <p>{@code portrait_dir} defaults to {@code textures/gui/dialogue/<path>} in this
 * character's own namespace, so the conventional layout needs no field at all.
 */
public record DialogueCharacterDef(
        Identifier id,
        String name,
        Optional<Identifier> portraitDir,
        float scale,
        Optional<Identifier> boxLeft,
        Optional<Identifier> boxRight,
        List<PortraitInset> portraitInsets
) {
    /** Drawn at the layout's own size. */
    public static final float DEFAULT_SCALE = 1F;
    /** Mirrors {@code ContentDefs.RENDER_SCALE_CODEC}'s range: a presentation knob, not a game rule. */
    public static final float MIN_SCALE = 0.2F;
    public static final float MAX_SCALE = 3F;

    private static final Identifier DEFAULT_BOX_LEFT =
            Identifier.withDefaultNamespace("textures/gui/dialogue/box_left");
    private static final Identifier DEFAULT_BOX_RIGHT =
            Identifier.withDefaultNamespace("textures/gui/dialogue/box_right");

    /**
     * How much of one portrait texture is empty margin.
     *
     * <p>Portraits arrive at wildly different framings: {@code entang}'s art fills her 1024x1536
     * canvas edge to edge, while {@code pea_chan}'s figure occupies the middle 79% of hers. Drawn at
     * one rectangle height - which is what makes them the same size on screen, since their figures
     * are all as tall as their canvases - the two then stand at different distances from the window
     * edge, and the bubble that hangs off the speaker's visible edge is off by the difference. A
     * third of a window of nothing between a character and her own speech is what this measures: the
     * four numbers are the fractions of the canvas before the art starts, and
     * {@code DialogueOverlay} places the frame so the <em>art</em> lands where the layout says.
     *
     * <p>Measured once per file with a threshold on the alpha channel (a column or row counts as art
     * once 5% of its pixels are opaque, which ignores an antialiased edge and still finds a vine),
     * and written down rather than recomputed at runtime: an artist can correct a number, and a
     * frame of dialogue does not decode a PNG to find out where the character is.
     *
     * <p>An expression the table does not mention has no margin - the common case, and the same
     * answer as a pack that never measured its art at all.
     */
    public record PortraitInsets(float left, float top, float right, float bottom) {
        /** Art that fills its canvas. */
        public static final PortraitInsets NONE = new PortraitInsets(0F, 0F, 0F, 0F);

        public static final Codec<PortraitInsets> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.optionalFieldOf("left", 0F).forGetter(PortraitInsets::left),
                Codec.FLOAT.optionalFieldOf("top", 0F).forGetter(PortraitInsets::top),
                Codec.FLOAT.optionalFieldOf("right", 0F).forGetter(PortraitInsets::right),
                Codec.FLOAT.optionalFieldOf("bottom", 0F).forGetter(PortraitInsets::bottom)
        ).apply(i, PortraitInsets::new));

        public PortraitInsets {
            left = clamp(left);
            top = clamp(top);
            right = clamp(right);
            bottom = clamp(bottom);
        }

        /** A margin is a fraction of the frame; a typo like 40 would leave nothing to draw. */
        private static float clamp(float value) {
            if (Float.isNaN(value)) {
                return 0F;
            }
            return Math.max(0F, Math.min(0.45F, value));
        }

        /** True when the art starts at the frame's own edge, on every side. */
        public boolean isNone() {
            return left == 0F && top == 0F && right == 0F && bottom == 0F;
        }
    }

    /**
     * One expression's margins, with the name it belongs to.
     *
     * <p>A list rather than a map keyed by expression, because the name is then a field a reader can
     * see and the file reads in the same shape as everything else in this content format.
     */
    public record PortraitInset(String portrait, PortraitInsets insets) {
        public static final Codec<PortraitInset> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("portrait").forGetter(PortraitInset::portrait),
                PortraitInsets.CODEC.fieldOf("insets").forGetter(PortraitInset::insets)
        ).apply(i, PortraitInset::new));
    }

    public static final Codec<DialogueCharacterDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(DialogueCharacterDef::id),
            Codec.STRING.optionalFieldOf("name", "").forGetter(DialogueCharacterDef::name),
            Identifier.CODEC.optionalFieldOf("portrait_dir").forGetter(DialogueCharacterDef::portraitDir),
            Codec.FLOAT.optionalFieldOf("scale", DEFAULT_SCALE).forGetter(DialogueCharacterDef::scale),
            Identifier.CODEC.optionalFieldOf("box_left").forGetter(DialogueCharacterDef::boxLeft),
            Identifier.CODEC.optionalFieldOf("box_right").forGetter(DialogueCharacterDef::boxRight),
            PortraitInset.CODEC.listOf().optionalFieldOf("portrait_insets", List.of())
                    .forGetter(DialogueCharacterDef::portraitInsets)
    ).apply(i, DialogueCharacterDef::new));

    public DialogueCharacterDef {
        portraitDir = portraitDir == null ? Optional.empty() : portraitDir;
        boxLeft = boxLeft == null ? Optional.empty() : boxLeft;
        boxRight = boxRight == null ? Optional.empty() : boxRight;
        portraitInsets = portraitInsets == null ? List.of() : List.copyOf(portraitInsets);
        // A typo like 1000 would otherwise draw one character over the whole window.
        scale = Float.isNaN(scale) ? DEFAULT_SCALE : Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    /**
     * Where the art sits inside one expression's texture.
     *
     * <p>Falls back to the entry named after the character - the convention for "their own
     * portrait", the same one {@code DialogueOverlay} uses when a named look is missing - and then
     * to no margin at all.
     */
    public PortraitInsets insets(String portrait) {
        PortraitInsets own = null;
        for (PortraitInset entry : portraitInsets) {
            if (entry.portrait().equals(portrait)) {
                return entry.insets();
            }
            if (entry.portrait().equals(id.path())) {
                own = entry.insets();
            }
        }
        return own == null ? PortraitInsets.NONE : own;
    }

    /** The name to show above the speech bubble; falls back to the id's path. */
    public String displayName() {
        return name == null || name.isBlank() ? id.path() : name;
    }

    /** The directory this character's portraits live in, conventional layout included. */
    public Identifier resolvedPortraitDir() {
        return portraitDir.orElseGet(() -> Identifier.of(id.namespace(), "textures/gui/dialogue/" + id.path()));
    }

    /**
     * The texture for one expression, or {@code null} when the line names none.
     *
     * <p>The expression is a file name inside {@link #resolvedPortraitDir()}: a line says
     * {@code "portrait": "smile"} and gets {@code .../pea_chan/smile.png}. It has to be a
     * name, not a path: identifier paths allow {@code /} and {@code .}, so
     * {@code "../other_character/smile"} would otherwise resolve out of this character's
     * directory and quietly use someone else's art.
     */
    public Identifier portraitTexture(String portrait) {
        if (portrait == null || portrait.isBlank() || !Identifier.isValidPath(portrait)
                || portrait.indexOf('/') >= 0 || portrait.contains("..")) {
            return null;
        }
        Identifier dir = resolvedPortraitDir();
        return dir.withPath(dir.path() + "/" + portrait);
    }

    /** The bubble texture for a speaker on the given side. */
    public Identifier box(boolean left) {
        Identifier configured = left ? boxLeft.orElse(null) : boxRight.orElse(null);
        if (configured != null) {
            return configured;
        }
        return left ? DEFAULT_BOX_LEFT : DEFAULT_BOX_RIGHT;
    }
}
