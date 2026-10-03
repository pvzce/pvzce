package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

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
 * <h2>Who it happens to</h2>
 *
 * <p>{@code target} names the character the beat belongs to, and an animation written without one
 * happens to the speaker - which is every animation written before a line needed to move somebody
 * else. Two values say the other half of it: {@link #TARGET_ALL} is everyone on stage at once, and
 * a character id is that one character wherever they stand. A scene that stages two people can
 * therefore shrink the listener while the other one talks, or make the whole picture jump.
 *
 * <p>A {@code scale} is a <em>standing</em> size, not a pose: it becomes the size the character
 * keeps, on this line and on every line after it until something changes it again (see
 * {@code DialogueScript}). {@code shake} is the opposite - a beat that is over when it is over.
 * That is why a scene can say "紫夜白 is 0.33 from here on" once, on the line where she shrinks.
 *
 * @param type   {@code none} / {@code shake} / {@code scale}, as written
 * @param amount how hard {@code shake} shakes, as a multiple of the built-in amplitude
 * @param scale  how large {@code scale} draws the portrait, as a multiple of its layout size
 * @param target who it happens to: blank for the speaker, {@link #TARGET_ALL}, or a character id
 * @param duration how long it takes, in seconds; 0 means the kind's own default
 */
public record DialogueAnimation(String type, float amount, float scale, String target, float duration) {
    public static final String TYPE_NONE = "none";
    public static final String TYPE_SHAKE = "shake";
    public static final String TYPE_SCALE = "scale";

    /** The target that names everyone standing there rather than one character. */
    public static final String TARGET_ALL = "all";

    /** The length a written animation takes when the line does not say. */
    public static final float DEFAULT_DURATION = 0F;
    /** Past this a beat stops being a beat and becomes a scene of its own. */
    public static final float MAX_DURATION = 5F;

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
     * of the art. 1.6 is "as close as this staging can go", not a round number. The floor is
     * where "one third of a person" lives - small enough to be a different creature on screen,
     * large enough to still be art rather than a smudge.
     */
    public static final float MIN_SCALE = 0.25F;
    public static final float MAX_SCALE = 1.6F;

    /** No animation: the line is spoken with the portrait exactly where it is. */
    public static final DialogueAnimation NONE = new DialogueAnimation(TYPE_NONE, DEFAULT_AMOUNT, DEFAULT_SCALE);

    /** An animation of the speaker, written with only the fields that kind uses. */
    public DialogueAnimation(String type, float amount, float scale) {
        this(type, amount, scale, "");
    }

    /** The same, of a named target. */
    public DialogueAnimation(String type, float amount, float scale, String target) {
        this(type, amount, scale, target, DEFAULT_DURATION);
    }

    public static final Codec<DialogueAnimation> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("type", TYPE_NONE).forGetter(DialogueAnimation::type),
            Codec.FLOAT.optionalFieldOf("amount", DEFAULT_AMOUNT).forGetter(DialogueAnimation::amount),
            Codec.FLOAT.optionalFieldOf("scale", DEFAULT_SCALE).forGetter(DialogueAnimation::scale),
            Codec.STRING.optionalFieldOf("target", "").forGetter(DialogueAnimation::target),
            Codec.FLOAT.optionalFieldOf("duration", DEFAULT_DURATION).forGetter(DialogueAnimation::duration)
    ).apply(i, DialogueAnimation::new));

    public DialogueAnimation {
        type = type == null || type.isBlank() ? TYPE_NONE : type;
        amount = Math.max(0F, Math.min(MAX_AMOUNT, amount));
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
        target = target == null ? "" : target.trim();
        duration = Float.isNaN(duration) ? DEFAULT_DURATION : Math.max(0F, Math.min(MAX_DURATION, duration));
    }

    /**
     * True when this animation happens to {@code character} while {@code speaker} is talking.
     *
     * <p>The speaker answers for a blank target, and only the speaker: a line that did not say who
     * it is about must not start moving the other character on stage. {@code null} is "no
     * character" (the player's own line, or nobody on stage), which no written target can match.
     */
    public boolean hits(Identifier character, Identifier speaker) {
        if (character == null) {
            return false;
        }
        if (target.isBlank()) {
            return character.equals(speaker);
        }
        if (TARGET_ALL.equalsIgnoreCase(target)) {
            return true;
        }
        Identifier named = Identifier.tryParse(target);
        return named != null && named.equals(character);
    }

    /** The target as written: blank when the speaker is the one it happens to. */
    public String targetName() {
        return target;
    }

    /** True when the line moves somebody rather than only deciding how it is spoken. */
    public boolean hasTarget() {
        return !target.isBlank();
    }

    /** True when the animation happens to everybody standing there. */
    public boolean targetsEveryone() {
        return TARGET_ALL.equalsIgnoreCase(target);
    }

    /**
     * How long the animation takes in <em>milliseconds</em>, or 0 when the line did not say.
     *
     * <p>0 is "the kind's own default" rather than "no time at all", so a line that never heard of
     * this field keeps the beat it always had (see {@code DialogueMotion}). Milliseconds and not
     * nanos because the field is a small decimal an author types: {@code 0.7f} is 0.699999988 as a
     * double, and a duration of 699_999_988ns is a number nobody wrote.
     */
    public long durationMillis() {
        return duration <= 0F ? 0L : Math.round(duration * 1000D);
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
