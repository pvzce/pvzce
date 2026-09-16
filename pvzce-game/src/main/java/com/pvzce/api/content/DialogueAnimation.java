package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Locale;

/**
 * What a single line does to the portrait while it is spoken.
 *
 * <p>Absent means {@link #TYPE_NONE}: a conversation is read, not performed, and an author
 * adds a beat to the one line that needs it. Three kinds, all of them one-shot at the start
 * of the line - a shake for a shout, a size for a close-up:
 *
 * <pre>{@code
 * { "character": "pvzce:pea_chan", "text": "诶诶诶！", "animation": { "type": "shake" } }
 * { "character": "pvzce:pea_chan", "text": "才不是！", "animation": { "type": "shake", "amount": 2 } }
 * { "character": "pvzce:pea_chan", "text": "看招！",   "animation": { "type": "scale", "scale": 1.25 } }
 * }</pre>
 *
 * <p>The type is kept as the author wrote it rather than folded onto an enum, so a typo or a
 * future kind survives an editor round trip; {@link #isKnown()} is what the validator and the
 * overlay ask. An unknown kind is drawn as {@link #TYPE_NONE}.
 *
 * @param type   {@code none} / {@code shake} / {@code scale}, as written
 * @param amount how hard {@code shake} shakes, as a multiple of the built-in amplitude
 * @param scale  how large {@code scale} draws the portrait, as a multiple of its layout size
 */
public record DialogueAnimation(String type, float amount, float scale) {
    public static final String TYPE_NONE = "none";
    public static final String TYPE_SHAKE = "shake";
    public static final String TYPE_SCALE = "scale";

    /** The amplitude a shake shakes at when the line does not say. */
    public static final float DEFAULT_AMOUNT = 1F;
    /** What {@code {"type":"scale"}} means without a number: noticeably bigger, not a jump cut. */
    public static final float DEFAULT_SCALE = 1.25F;
    /** A shake past this is a screen effect, not a sentence. */
    public static final float MAX_AMOUNT = 4F;
    /**
     * How far the portrait may be resized.
     *
     * <p>The ceiling is deliberately low: the portrait is drawn at 90% of the window height
     * and scales about its own floor, so anything past {@code ~1.11} starts cropping the top
     * of the art. 1.6 is "as close as this staging can go", not a round number.
     */
    public static final float MIN_SCALE = 0.25F;
    public static final float MAX_SCALE = 1.6F;

    /** No animation: the line is spoken with the portrait exactly where it is. */
    public static final DialogueAnimation NONE = new DialogueAnimation(TYPE_NONE, DEFAULT_AMOUNT, DEFAULT_SCALE);

    public static final Codec<DialogueAnimation> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("type", TYPE_NONE).forGetter(DialogueAnimation::type),
            Codec.FLOAT.optionalFieldOf("amount", DEFAULT_AMOUNT).forGetter(DialogueAnimation::amount),
            Codec.FLOAT.optionalFieldOf("scale", DEFAULT_SCALE).forGetter(DialogueAnimation::scale)
    ).apply(i, DialogueAnimation::new));

    public DialogueAnimation {
        type = type == null || type.isBlank() ? TYPE_NONE : type;
        amount = Math.max(0F, Math.min(MAX_AMOUNT, amount));
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    /** True for the three kinds this version knows how to draw. */
    public boolean isKnown() {
        return isNone() || isShake() || isScale();
    }

    public boolean isNone() {
        return TYPE_NONE.equalsIgnoreCase(type);
    }

    public boolean isShake() {
        return TYPE_SHAKE.equalsIgnoreCase(type);
    }

    public boolean isScale() {
        return TYPE_SCALE.equalsIgnoreCase(type);
    }

    /** The size multiplier the portrait settles at while this line is spoken. */
    public float targetScale() {
        return isScale() ? scale : 1F;
    }

    /** The kind as a short label, for the editor's own summary line. */
    public String shortName() {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case TYPE_SHAKE -> "抖动";
            case TYPE_SCALE -> "缩放";
            default -> "无";
        };
    }
}
