package com.pvzce.client.sound;

import com.pvzce.api.content.ZombieDef;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.PvzceClient;
import com.pvzce.common.core.BuiltInRegistries;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sounds that belong to a walking entity, and stop when it stops walking.
 *
 * <p>One sound in the game is not a moment but a <em>thing</em>: the jack-in-the-box's music box,
 * which plays under the whole of its walk - twenty-odd seconds of it - and has to stop the instant
 * the box opens or the body dies. The server cannot express that with an effect packet (an effect
 * is a moment), and it should not have to: the client is already drawing the entity, already knows
 * its animation state, and is the only side that finds out at the right moment.
 *
 * <p>So the definition carries the sound ({@code sounds.walk}, the original's
 * {@code StartZombieSound}) and this class runs it: a loop per entity while its animation is the
 * walking one and it is on the board, stopped when the animation changes, when the entity leaves
 * the mirror, or when the level stops running.
 *
 * <p>Starting when the zombie reaches the board rather than when it spawns is the original's own
 * rule - a zombie is spawned off the right edge and the music belongs to the lawn - and it is why
 * the test here can be a cell comparison rather than a "has been seen" flag that would have to be
 * saved.
 */
public final class EntityLoops {
    /** One entity's running loop: the engine's handle, and the event it was started with. */
    private record Loop(int handle, String sound) {
    }

    private final Map<Integer, Loop> loops = new HashMap<>();

    /**
     * The loop this entity wants right now, or {@code null} when it wants none.
     *
     * <p>The rule, on its own, away from the audio plumbing: a zombie whose definition carries a
     * walking sound has it while it is <em>walking</em> - the walking animation, and on the board
     * rather than off the right edge where every zombie spawns - and loses it the moment either
     * stops being true. A state the capability invents for "the magnet took its box"
     * ({@code walk_no_box}) is not {@code walk}, so a clown that has been disarmed is silent, which
     * is what its own walk state means.
     */
    public static Identifier walkSoundFor(ClientLevel level, ClientEntity entity) {
        ZombieDef def = BuiltInRegistries.ZOMBIES.get(entity.defId());
        if (def == null || entity.health() <= 0 || !EntityAnimations.WALK.equals(entity.animation())
                || entity.cellX() >= level.width()) {
            return null;
        }
        return def.sounds().walkSound().orElse(null);
    }

    /** Runs once a frame, from the in-game screen. */
    public void tick(PvzceClient client) {
        ClientLevel level = client.level();
        if (client.sound() == null || !"running".equals(level.gameState())) {
            stopAll(client);
            return;
        }
        Set<Integer> live = new HashSet<>();
        for (ClientEntity entity : level.entities().values()) {
            Identifier sound = walkSoundFor(level, entity);
            if (sound == null) {
                Loop idle = loops.get(entity.id());
                if (idle != null) {
                    stop(client, entity.id(), idle);
                }
                continue;
            }
            live.add(entity.id());
            Loop loop = loops.get(entity.id());
            if (loop != null && loop.sound().equals(sound.toString())) {
                continue;
            }
            // A different sound for the same entity (its definition changed under a reload) or no
            // loop yet: take the old one down first, so two tunes never overlap.
            if (loop != null) {
                stop(client, entity.id(), loop);
            }
            int handle = client.sound().startLoop(sound.toString(), 1F, 1F);
            if (handle >= 0) {
                loops.put(entity.id(), new Loop(handle, sound.toString()));
            }
        }
        // Anything that left the mirror - eaten, blown up, despawned with the level - takes its
        // sound with it. Without this the tune outlives the clown, which is the one failure this
        // whole class exists to prevent.
        for (Integer entityId : List.copyOf(loops.keySet())) {
            if (!live.contains(entityId)) {
                stop(client, entityId, loops.get(entityId));
            }
        }
    }

    /** Stops every loop; the level is over, or the screen is going away. */
    public void stopAll(PvzceClient client) {
        for (Map.Entry<Integer, Loop> entry : List.copyOf(loops.entrySet())) {
            stop(client, entry.getKey(), entry.getValue());
        }
    }

    /** How many loops are running; for tests and for a leak that would otherwise be silent. */
    public int runningCount() {
        return loops.size();
    }

    private void stop(PvzceClient client, int entityId, Loop loop) {
        loops.remove(entityId);
        if (loop != null && client.sound() != null) {
            client.sound().stopLoop(loop.handle());
        }
    }
}
