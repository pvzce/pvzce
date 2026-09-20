package com.pvzce.client.animation;

import com.pvzce.api.entity.EntityAnimations;

import java.util.ArrayList;
import java.util.List;

/**
 * Which clip a requested state actually plays, for the states that name a family.
 *
 * <p>The death states are a family rather than a clip. The original shuffles its death
 * sequences so a lane of zombies does not fall over in unison, and the sequences live in the
 * same reanim file, so the choice needs the file - which only the client has. The server
 * therefore publishes the one state {@link EntityAnimations#DEATH} and this class decides
 * which of the file's death clips it means:
 *
 * <ul>
 *   <li>the ordinary pool is {@code death} and {@code death2}, and only the clips the file
 *       actually defines take part. Asking for a clip the art never drew is what made a
 *       newspaper zombie whose file has only {@code death} stand about in {@code idle} for
 *       the whole six seconds of its corpse: half of all deaths rolled a state that resolved
 *       to nothing;</li>
 *   <li>the pick is a function of the entity id rather than a random roll, so the same run
 *       of the same level still deals the same sequences to the same bodies - entity ids are
 *       handed out by the server in level order - and a resync does not reshuffle a corpse
 *       that is already on the ground;</li>
 *   <li>{@code death_superlong} is deliberately not in the pool: it is a heavy body's death,
 *       not a variation on an ordinary one, and a definition that wants it points its
 *       {@code death} state at a file where that is the clip (see
 *       {@code gargantuar}, whose file has no second sequence to choose between);</li>
 *   <li>drowning is a member of the family rather than a pool of its own: {@code death_water}
 *       plays where the art has it, and where it does not the body sinks with its ordinary
 *       death instead of standing upright in the water.</li>
 * </ul>
 *
 * <p>A state outside the family resolves to itself when the file defines it, and to
 * {@code null} otherwise - the caller's signal to substitute {@code idle}, which is the
 * fallback every unknown clip has always got.
 */
final class AnimationVariants {
    /** The ordinary death sequences, in the order the original draws them. */
    private static final List<String> DEATH_POOL =
            List.of(EntityAnimations.DEATH, EntityAnimations.DEATH2);

    /** Every state that means "this body is falling over", not just the ordinary two. */
    private static final List<String> DEATH_STATES =
            List.of(EntityAnimations.DEATH, EntityAnimations.DEATH2, EntityAnimations.DEATH_WATER);

    private AnimationVariants() {
    }

    /**
     * The clip name a state resolves to, or {@code null} when the file has nothing for it.
     *
     * @param seed a stable per-target number, so a body keeps the sequence it was given
     */
    static String resolve(AnimationFile file, String state, long seed) {
        if (file == null || state == null) {
            return null;
        }
        if (DEATH_POOL.contains(state) && file.clip(state).isPresent()) {
            // The state names a clip the art has, but it is the family's entry point rather
            // than an answer: which sequence this body gets is still to be decided.
            return pickDeath(file, seed);
        }
        if (file.clip(state).isPresent()) {
            return state;
        }
        // The art has no clip under this state's own name. For a death that means "it drew
        // the family, just not this member" - a drowned zombie whose file only has `death`.
        return DEATH_STATES.contains(state) ? pickDeath(file, seed) : null;
    }

    /** One of the death clips this file defines, or {@code null} when it defines none. */
    private static String pickDeath(AnimationFile file, long seed) {
        List<String> present = new ArrayList<>(2);
        for (String name : DEATH_POOL) {
            if (file.clip(name).isPresent()) {
                present.add(name);
            }
        }
        if (present.isEmpty()) {
            return null;
        }
        if (present.size() == 1) {
            // The common case: art with a single death sequence. No roll happens at all, so
            // a file that gains a second sequence later is the only thing that can change
            // which clip any given body plays.
            return present.get(0);
        }
        return present.get((int) Math.floorMod(seed, present.size()));
    }
}
