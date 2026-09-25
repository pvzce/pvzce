package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

import java.util.Locale;
import java.util.Optional;

/**
 * One on-screen hint a level teaches with: the original's grey box at the bottom of
 * the board.
 *
 * <p>The original uses that box for two different jobs, and so does this:
 *
 * <ul>
 *   <li><b>tutorial text</b> - "click a sun to collect it", "you need sun to plant" -
 *       which is content, because it is about <em>this</em> level. That is what a level
 *       writes in its {@code hints} block.</li>
 *   <li><b>refusals</b> - "still recharging", "not enough sun" - which are about the
 *       game's own rules and are the same sentence in every level. Those are built in
 *       (see {@code client/gui/HintBox}); a level only opts into <em>having</em> them
 *       with a {@code on_card_refused} entry, so a sandbox level that does not want to
 *       be nagged can leave it out.</li>
 * </ul>
 *
 * <p>Written as one line of JSON:
 *
 * <pre>{@code
 * { "trigger": "on_resource", "resource": "pvzce:sun", "text": "点击阳光可以收集" }
 * }</pre>
 *
 * @param trigger     what makes this hint appear; see {@link Trigger}
 * @param resource    the resource an {@link Trigger#ON_RESOURCE} hint waits for
 * @param text        the line to show; ignored by {@link Trigger#ON_CARD_REFUSED}, which
 *                    has its own two sentences
 * @param durationTicks how long the line stays up. {@link #PERSISTENT} (or anything
 *                    below 1) keeps it until the level ends or another hint replaces it,
 *                    which is what the original does with its tutorial text.
 */
public record LevelHint(Trigger trigger, Optional<Identifier> resource, String text,
                        int durationTicks) {
    /** Two to three seconds, the original's own beat for the box. */
    public static final int DEFAULT_DURATION_TICKS = 160;
    /** "Stay up until something happens" - a newer hint, or the player's first pickup. */
    public static final int PERSISTENT = 0;
    /**
     * The longest any hint stays up, in ticks: fifteen seconds.
     *
     * <p>A ceiling rather than a default, and enforced in the client's {@code HintBox} rather than
     * validated per level, so no content can produce a line that never goes away. That is the
     * failure a ceiling is here to prevent: a level with a single {@link #PERSISTENT} line and no
     * follow-up hint had nothing left to replace it, so the box covered a third of the lawn for the
     * whole level. Fifteen seconds is long enough to read two short sentences and short enough that
     * a player who has moved on is not still being told about it.
     */
    public static final int MAX_DURATION_TICKS = 15 * com.pvzce.common.PvzceConstants.TICKS_PER_SECOND;

    /**
     * When a hint shows.
     *
     * <p>Deliberately a closed set of the moments the client can see by itself. Every
     * one of these is already a thing the client knows - the level started, a drop was
     * clicked, a card was refused - so a hint needs no packet and no server round trip,
     * and a level reloaded from a save does not replay the ones it already showed.
     *
     * <p>The stored form is the lower-case name ({@code "on_start"}), spelled out rather
     * than derived from {@code ordinal()} so inserting a trigger cannot silently
     * reinterpret existing level files.
     */
    public enum Trigger {
        /** Once, when the board comes up - the tutorial's opening line. */
        ON_START,
        /** Once, the first time the player collects the resource this hint names. */
        ON_RESOURCE,
        /** Every time a card is clicked while it cannot be played. */
        ON_CARD_REFUSED;

        public static final Codec<Trigger> CODEC = Codec.STRING.xmap(
                Trigger::parse,
                trigger -> trigger.name().toLowerCase(Locale.ROOT));

        /**
         * Reads a trigger name, falling back to {@link #ON_START}.
         *
         * <p>Silent on purpose, like {@code ResourceDef.parseMotion}: an unrecognised
         * trigger shows a hint that is merely early rather than nothing at all, and
         * {@link com.pvzce.server.level.LevelValidator} is what names the typo.
         */
        public static Trigger parse(String name) {
            if (name == null) {
                return ON_START;
            }
            for (Trigger trigger : values()) {
                if (trigger.name().equalsIgnoreCase(name.trim())) {
                    return trigger;
                }
            }
            return ON_START;
        }
    }

    public static final Codec<LevelHint> CODEC = RecordCodecBuilder.create(i -> i.group(
            Trigger.CODEC.optionalFieldOf("trigger", Trigger.ON_START).forGetter(LevelHint::trigger),
            Identifier.CODEC.optionalFieldOf("resource").forGetter(LevelHint::resource),
            Codec.STRING.optionalFieldOf("text", "").forGetter(LevelHint::text),
            Codec.INT.optionalFieldOf("duration_ticks", DEFAULT_DURATION_TICKS)
                    .forGetter(LevelHint::durationTicks)
    ).apply(i, LevelHint::new));

    public LevelHint {
        resource = resource == null ? Optional.empty() : resource;
        text = text == null ? "" : text;
    }

    /** True when this line stays up until something else takes it down. */
    public boolean persistent() {
        return durationTicks <= 0;
    }

    /**
     * True when this hint needs the resource it names, which is what the validator
     * checks: an {@code on_resource} hint without one can never fire.
     */
    public boolean needsResource() {
        return trigger == Trigger.ON_RESOURCE;
    }
}
