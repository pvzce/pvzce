package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

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
        Optional<Identifier> boxRight
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

    public static final Codec<DialogueCharacterDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(DialogueCharacterDef::id),
            Codec.STRING.optionalFieldOf("name", "").forGetter(DialogueCharacterDef::name),
            Identifier.CODEC.optionalFieldOf("portrait_dir").forGetter(DialogueCharacterDef::portraitDir),
            Codec.FLOAT.optionalFieldOf("scale", DEFAULT_SCALE).forGetter(DialogueCharacterDef::scale),
            Identifier.CODEC.optionalFieldOf("box_left").forGetter(DialogueCharacterDef::boxLeft),
            Identifier.CODEC.optionalFieldOf("box_right").forGetter(DialogueCharacterDef::boxRight)
    ).apply(i, DialogueCharacterDef::new));

    public DialogueCharacterDef {
        portraitDir = portraitDir == null ? Optional.empty() : portraitDir;
        boxLeft = boxLeft == null ? Optional.empty() : boxLeft;
        boxRight = boxRight == null ? Optional.empty() : boxRight;
        // A typo like 1000 would otherwise draw one character over the whole window.
        scale = Float.isNaN(scale) ? DEFAULT_SCALE : Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
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
