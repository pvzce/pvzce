package com.pvzce.client.sound;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.ZombieDef;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.ClientLevel;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The sound that belongs to a walking zombie: the jack-in-the-box's music box.
 *
 * <p>What is pinned here is the rule rather than the audio - a test has no device to play through,
 * and {@code SoundEngine.startLoop} answers -1 without one, so the interesting half is <em>when</em>
 * a zombie wants a loop and when it stops wanting it. The plumbing around it (a handle per entity,
 * stopped when the entity leaves the mirror) is one {@code if} wide and reads its answer from here.
 *
 * <p>The failure this guards against is the player's own report - "the clown zombie's audio is
 * missing" - in both directions: a tune that never starts (the build used to fire a six-second
 * music box 110 ticks before the blast, so the player heard a second and a half of it and nothing
 * at all during the walk) and a tune that never stops (a loop held by an entity that is gone).
 */
class EntityLoopsTest {
    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static ClientLevel levelOf(String level) {
        LevelDef def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/" + level));
        assertNotNull(def, level + " has to exist");
        ClientLevel client = new ClientLevel();
        client.init(def.id().toString(), def.width(), def.height(), List.of(), List.of("small"),
                List.of(), 6, List.of(), List.of(), "pvzce:plant_team", "植物方",
                LevelMechanics.payloads(def), def.background().orElse(null),
                def.hiddenSceneElements(), def.disableShaders());
        return client;
    }

    /** A client-side zombie, as the mirror builds them from a spawn packet. */
    private static ClientEntity entity(String defId, float cellX, String animation) {
        ClientEntity entity = new ClientEntity(1, "zombie", defId, cellX, 2F, 200,
                com.pvzce.api.entity.EntityLayers.GROUND, animation, 0F, "pvzce:zombie_team");
        return entity;
    }

    /**
     * The clown's music box is carried by its definition, and it is the walk sound rather than the
     * pop's.
     *
     * <p>A data assertion, and the one that would have caught the wiring being on the wrong event:
     * the tune is six seconds long and the walk is twenty-odd, so a tune attached to the pop can
     * only ever be cut off.
     */
    @Test
    void theClownCarriesItsMusicBoxAsAWalkSound() {
        ZombieDef clown = BuiltInRegistries.ZOMBIES.get(PvzceIds.id("jack_in_the_box_zombie"));
        assertNotNull(clown, "the jack-in-the-box has to exist");
        assertEquals(PvzceSounds.ZOMBIE_JACK_IN_THE_BOX, clown.sounds().walkSound().orElse(null),
                "the music box is the sound of its walk");
        assertEquals(PvzceSounds.ZOMBIE_JACK_SURPRISE2, clown.sounds().special().orElse(null),
                "and the lid coming up is the sound of the box opening");

        ZombieDef walker = BuiltInRegistries.ZOMBIES.get(PvzceIds.id("basic_zombie"));
        assertNotNull(walker);
        assertEquals(java.util.Optional.empty(), walker.sounds().walkSound(),
                "an ordinary zombie walks in silence");
    }

    /** A walking clown on the board wants the music box; everything else wants nothing. */
    @Test
    void theMusicBoxPlaysOnlyWhileItWalksOnTheBoard() {
        ClientLevel level = levelOf("4_1");
        Identifier musicBox = PvzceSounds.ZOMBIE_JACK_IN_THE_BOX;

        assertEquals(musicBox, EntityLoops.walkSoundFor(level,
                entity("pvzce:jack_in_the_box_zombie", 7.5F, EntityAnimations.WALK)),
                "walking in the fog, playing its tune");
        assertNull(EntityLoops.walkSoundFor(level,
                        entity("pvzce:jack_in_the_box_zombie", 7.5F, EntityAnimations.POP)),
                "the box is open: the tune stops there, as the original's StopZombieSound does");
        assertNull(EntityLoops.walkSoundFor(level,
                        entity("pvzce:jack_in_the_box_zombie", 7.5F, "walk_no_box")),
                "and a clown whose box the magnet took has nothing left to play");
        assertNull(EntityLoops.walkSoundFor(level,
                        entity("pvzce:jack_in_the_box_zombie", 9.6F, EntityAnimations.WALK)),
                "off the right edge is still the road, not the lawn");
        assertNull(EntityLoops.walkSoundFor(level,
                        entity("pvzce:basic_zombie", 7.5F, EntityAnimations.WALK)),
                "and an ordinary zombie never had a tune");
        assertNull(EntityLoops.walkSoundFor(level,
                        entity("pvzce:not_a_zombie", 7.5F, EntityAnimations.WALK)),
                "an id nothing defines wants nothing");
        ClientEntity dead = entity("pvzce:jack_in_the_box_zombie", 7.5F, EntityAnimations.WALK);
        dead.update(7.5F, 2F, 0, EntityAnimations.WALK, 0F);
        assertNull(EntityLoops.walkSoundFor(level, dead), "death stops the loop before a new pose arrives");
    }

    @Test
    void loopReleaseUsesTheOpenAlSourceIdAndStopsItOnce() {
        var owned = new java.util.HashMap<Integer, String>();
        owned.put(73, "music_box");
        owned.put(91, "another_loop");
        var stopped = new java.util.ArrayList<Integer>();
        org.junit.jupiter.api.Assertions.assertTrue(SoundEngine.releaseLoop(owned, 73, stopped::add));
        org.junit.jupiter.api.Assertions.assertFalse(SoundEngine.releaseLoop(owned, 73, stopped::add));
        assertEquals(List.of(73), stopped, "a source id need not fit inside the source pool's array");
        assertEquals(java.util.Map.of(91, "another_loop"), owned);
    }
}
