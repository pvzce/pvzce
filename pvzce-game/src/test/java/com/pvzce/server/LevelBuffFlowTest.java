package com.pvzce.server;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.PvzcePackets;
import com.pvzce.common.network.packet.LevelInitS2C;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.PlayLevelC2S;
import com.pvzce.common.network.packet.ProfileS2C;
import com.pvzce.common.network.packet.ResourceCollectS2C;
import com.pvzce.common.network.packet.SeedOption;
import com.pvzce.common.resource.PvzceDataLoader;
import com.pvzce.common.resource.PvzceResourceManager;
import com.pvzce.common.util.LevelKey;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.ServerHarness;
import com.pvzce.testutil.TestDirs;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The buff page, end to end: a real server, a real packet, and what the client is told.
 *
 * <p>{@code LevelBuffSelectionTest} pins the rules as functions. This pins the half that only
 * exists once a level actually starts - the chooser's pool arrives in the payload, the buffs a
 * client submits become the run's rules, and the world remembers them. Without it the two halves
 * could each be right and still not be wired to each other, which is exactly the failure a
 * pure-function test cannot see.
 *
 * <p>Runs against the shipped data pack rather than a fixture level, because what is being checked
 * is that the built-in levels' own {@code buffs} blocks reach the client: a fixture would only
 * prove the mechanism works on a file the test wrote itself.
 */
class LevelBuffFlowTest {
    private static final Identifier AUTO = PvzceIds.BUFF_AUTO_COLLECT;
    private static final Identifier RANGE = PvzceIds.BUFF_MUSHROOM_RANGE;
    private static final Identifier PLANT_TEAM = Identifier.withDefaultNamespace("plant_team");
    private static final String WORLD = "buffflow";
    /** A built-in level that offers the player a choice (the 1-6..1-9 / 2-x group does). */
    private static final String CHOICE_LEVEL = "pvzce:yard/adventure/1_6";
    /** A built-in level that fixes its buffs: a conveyor belt has no cards to choose either. */
    private static final String FIXED_LEVEL = "pvzce:yard/adventure/1_5";
    /** A level written before buffs existed: it must be unaffected in every way. */
    private static final String PLAIN_LEVEL = "pvzce:yard/adventure/1_1";
    /**
     * A fixture level that pins auto-pickup, written as JSON into a data pack.
     *
     * <p>The shipped levels that fix a buff are all conveyor belts, and a belt has no plant player
     * to collect for - so "the drop was taken" cannot be observed on one. This is also the only
     * place the two flat fields are exercised through the real codec, from a file rather than from
     * a constructor.
     */
    private static final String PICKUP_LEVEL = "pvzce:auto_pickup_test";

    /** The chooser's buff page is filled from the payload, and it offers both built-ins. */
    @Test
    void thePayloadCarriesTheBuffPage() throws Exception {
        Path dir = gameDir();
        try (ServerHarness harness = ServerHarness.createWithWorld(dir, WORLD, true)) {
            LevelListS2C.LevelInfo info = levelInfo(harness, CHOICE_LEVEL);
            // Read from the registry rather than written down: the claim is "the whole catalogue
            // is on offer", and a literal list turns every new buff into a failure of a test that
            // is about the offer reaching the client at all.
            assertEquals(com.pvzce.common.core.BuiltInRegistries.LEVEL_BUFFS.keySet().stream()
                            .map(com.pvzce.api.util.Identifier::toString).toList(),
                    info.payload().buffPool().stream().map(SeedOption::slotId).toList(),
                    "the whole catalogue is on offer for a level that opted in");
            assertEquals(com.pvzce.common.PvzceConstants.DEFAULT_BUFF_SLOTS, info.payload().maxBuffSlots(),
                    "a level that declares no count follows the backpack");
            assertEquals(List.of(), info.payload().activeBuffs(),
                    "a level that has not started has nothing switched on yet");
        }
    }

    /** A level that does not opt in has no buff page at all. */
    @Test
    void aLevelThatDidNotOptInOffersNothing() throws Exception {
        Path dir = gameDir();
        try (ServerHarness harness = ServerHarness.createWithWorld(dir, WORLD, true)) {
            LevelListS2C.LevelInfo info = levelInfo(harness, PLAIN_LEVEL);
            assertTrue(info.payload().buffPool().isEmpty(), "no pool, so no page");
            assertFalse(BuiltInRegistries.LEVELS.get(Identifier.parse(PLAIN_LEVEL)).offersBuffChoice(),
                    "and the level itself says it never opted in");
        }
    }

    /**
     * The pool marks what this player has been given, and a sandbox has been given everything.
     *
     * <p>The other half of the gate (what a player who owns <em>nothing</em> sees) is pinned by
     * {@code LevelBuffSelectionTest}, which can build that profile without a server; this is the
     * wiring check - the marker survives the payload, and the run accepts a buff the player owns.
     */
    @Test
    void thePoolCarriesTheLockMarker() throws Exception {
        Path dir = gameDir();
        try (ServerHarness harness = ServerHarness.createWithWorld(dir, WORLD, true)) {
            LevelListS2C.LevelInfo info = levelInfo(harness, CHOICE_LEVEL);
            assertTrue(info.payload().buffPool().stream()
                            .noneMatch(option -> option.costSun()
                                    == com.pvzce.common.core.SeedOptions.LOCKED_OPTION),
                    "a sandbox world owns every buff, so nothing is padlocked");

            harness.clear();
            harness.send(new PlayLevelC2S(CHOICE_LEVEL, WORLD, true, List.of(),
                    List.of(RANGE.toString())));
            LevelInitS2C init = harness.awaitPacket(LevelInitS2C.class, 8_000);
            assertEquals(List.of(RANGE.toString()), init.payload().activeBuffs());
        }
    }

    /**
     * A world that has been handed a buff may switch it on; one that has not, may not.
     *
     * <p>No sandbox here: this is the real gate, driven through the real profile, which is what
     * "1-9 hands over 远距蘑菇" comes down to once the level has been cleared.
     */
    @Test
    void onlyAGrantedBuffCanBeSwitchedOn() throws Exception {
        Path dir = gameDir();
        try (ServerHarness harness = ServerHarness.createWithWorld(dir, WORLD, false)) {
            // 1-6 wants 1-5 cleared, and this world has played nothing: mark the chain as cleared
            // so the *gate under test* is the buff one rather than the level one. Written through
            // the store rather than by faking a level unlock, so the entry flow is the real one.
            clearPrerequisitesOfChoiceLevel(dir);
            // Nothing granted: every buff is listed for a level that offers them, and all of them
            // are padlocked - so the run gets none.
            LevelListS2C.LevelInfo info = levelInfo(harness, CHOICE_LEVEL);
            assertEquals(com.pvzce.common.core.BuiltInRegistries.LEVEL_BUFFS.size(),
                    info.payload().buffPool().size(), "all of them are still listed");
            assertTrue(info.payload().buffPool().stream().allMatch(option -> option.costSun()
                            == com.pvzce.common.core.SeedOptions.LOCKED_OPTION),
                    "and all of them are padlocked");

            harness.clear();
            harness.send(new PlayLevelC2S(CHOICE_LEVEL, WORLD, true, List.of(),
                    List.of(AUTO.toString())));
            LevelInitS2C refused = harness.awaitPacket(LevelInitS2C.class, 8_000);
            assertEquals(List.of(), refused.payload().activeBuffs(),
                    "a buff the player was never given cannot be played with");

            // Handed over the way 1-9 hands it over, then asked again.
            harness.server().grantBuff(RANGE);
            harness.clear();
            harness.send(new PlayLevelC2S(CHOICE_LEVEL, WORLD, true, List.of(),
                    List.of(RANGE.toString())));
            LevelInitS2C accepted = harness.awaitPacket(LevelInitS2C.class, 8_000);
            assertEquals(List.of(RANGE.toString()), accepted.payload().activeBuffs());
        }
    }

    /** What the client submits is what the run plays with - and what the world remembers. */
    @Test
    void theSubmittedBuffsBecomeTheRunsRulesAndTheWorldsMemory() throws Exception {
        Path dir = gameDir();
        try (ServerHarness harness = ServerHarness.createWithWorld(dir, WORLD, true)) {
            harness.clear();
            harness.send(new PlayLevelC2S(CHOICE_LEVEL, WORLD, true, List.of(),
                    List.of(AUTO.toString())));

            LevelInitS2C init = harness.awaitPacket(LevelInitS2C.class, 8_000);
            assertEquals(List.of(AUTO.toString()), init.payload().activeBuffs(),
                    "the run is played with the buff the client switched on");

            LevelServer level = harness.server().level();
            assertNotNull(level);
            assertEquals(1, level.activeBuffs().size());
            assertTrue(level.activeBuffs().get(0).autoCollectsResources());
            assertEquals(1F, level.sporeRangeMultiplier(null),
                    "a null plant is nobody's spore shooter, and this is not the range buff");

            // The world's auto list is now "what I last went in with", which is what the next
            // chooser pre-selects - there is no separate save-preferences step.
            ProfileS2C profile = harness.packet(ProfileS2C.class);
            assertNotNull(profile, "the profile is refreshed after a run starts");
            assertEquals(List.of(AUTO.toString()), profile.autoBuffs());
        }
    }

    /** Continuing a save plays by the buffs that save was started with. */
    @Test
    void continuingASaveKeepsItsBuffs() throws Exception {
        Path dir = gameDir();
        try (ServerHarness harness = ServerHarness.createWithWorld(dir, WORLD, true)) {
            harness.clear();
            harness.send(new PlayLevelC2S(CHOICE_LEVEL, WORLD, true, List.of(),
                    List.of(RANGE.toString())));
            LevelInitS2C first = harness.awaitPacket(LevelInitS2C.class, 8_000);
            assertEquals(List.of(RANGE.toString()), first.payload().activeBuffs());

            // A run belongs on disk before "continue" is a real case; asking the server to write
            // it is what the shutdown path does.
            harness.server().saveGame();
            Path saveFile = dir.resolve("saves/" + WORLD + "/levels/"
                    + LevelKey.of(Identifier.parse(CHOICE_LEVEL)) + "/level.dat");
            assertTrue(Files.isRegularFile(saveFile), "the run was not written to " + saveFile);

            // A second entry, this time asking for the other buff: the save on disk is what the
            // server resumes, so the request is not what decides.
            harness.clear();
            harness.send(new PlayLevelC2S(CHOICE_LEVEL, WORLD, false, List.of(),
                    List.of(AUTO.toString())));
            LevelInitS2C resumed = harness.awaitPacket(LevelInitS2C.class, 8_000);
            assertEquals(List.of(RANGE.toString()), resumed.payload().activeBuffs(),
                    "a resumed run keeps the buffs it was started with");
        }
    }

    /** A fixed-buff level hands its buff over without any page and without being asked. */
    @Test
    void aFixedBuffLevelTurnsItOnByItself() throws Exception {
        Path dir = gameDir();
        try (ServerHarness harness = ServerHarness.createWithWorld(dir, WORLD, true)) {
            harness.clear();
            // No buff list at all: this entry has no chooser behind it.
            harness.send(new PlayLevelC2S(FIXED_LEVEL, WORLD, true, List.of()));
            LevelInitS2C init = harness.awaitPacket(LevelInitS2C.class, 8_000);
            assertEquals(List.of(AUTO.toString()), init.payload().activeBuffs());
            assertTrue(init.payload().buffPool().isEmpty(), "a level that fixes them offers none");
        }
    }

    /**
     * Auto-pickup credits the resource without anyone clicking it.
     *
     * <p>The delay is the buff's whole visible behaviour - the drop lands and a moment later it is
     * on its way to the bank - so the test waits for the collect packet, which is also what the
     * player sees, rather than for a number.
     */
    @Test
    void autoPickupCollectsADropByItself() throws Exception {
        Path dir = gameDir();
        try (ServerHarness harness = ServerHarness.createWithWorld(dir, WORLD, true)) {
            harness.clear();
            harness.send(new PlayLevelC2S(PICKUP_LEVEL, WORLD, true, List.of()));
            LevelInitS2C init = harness.awaitPacket(LevelInitS2C.class, 8_000);
            assertEquals(List.of(AUTO.toString()), init.payload().activeBuffs());
            assertTrue(init.payload().buffPool().isEmpty(), "a level that fixes them offers none");

            LevelServer level = harness.server().level();
            assertNotNull(level);
            harness.clear();
            level.spawnResource(PvzceIds.SUN, 25, 4F, 2F, level.team(PLANT_TEAM));
            // The spawn is queued, so wait for the drop to reach the client before waiting for it
            // to be taken: a collect that arrives first would be a different bug, but an empty
            // packet list would look the same as no buff at all.
            harness.waitFor(packet -> packet instanceof com.pvzce.common.network.packet.EntitySpawnS2C,
                    5_000, "the sun never reached the client");

            PvzcePacket collected = harness.awaitPacketOrNull(ResourceCollectS2C.class, 8_000);
            assertNotNull(collected, "the drop was picked up without being clicked");
        }
    }

    /**
     * Without the buff the same drop just lies there.
     *
     * <p>The other half of the claim, and the one that catches a buff wired to "always on".
     */
    @Test
    void withoutTheBuffTheDropStays() throws Exception {
        Path dir = gameDir();
        try (ServerHarness harness = ServerHarness.createWithWorld(dir, WORLD, true)) {
            harness.clear();
            harness.send(new PlayLevelC2S(PLAIN_LEVEL, WORLD, true, List.of("pvzce:sun")));
            LevelInitS2C init = harness.awaitPacket(LevelInitS2C.class, 8_000);
            assertEquals(List.of(), init.payload().activeBuffs());

            LevelServer level = harness.server().level();
            assertNotNull(level);
            harness.clear();
            level.spawnResource(PvzceIds.SUN, 25, 4F, 2F, level.team(PLANT_TEAM));
            harness.waitFor(packet -> packet instanceof com.pvzce.common.network.packet.EntitySpawnS2C,
                    5_000, "the sun never reached the client");

            // Two seconds is well past the buff's delay; nothing should come back.
            assertTrue(harness.awaitPacketOrNull(ResourceCollectS2C.class, 2_000) == null,
                    "nothing collects a drop in a level with no auto-pickup buff");
        }
    }

    /**
     * Writes the completion markers 1-6 asks for, so a fresh world may enter it.
     *
     * <p>The chain is 1-1 through 1-5; a world that has cleared them is exactly a world that has
     * reached 1-6, and this is the only way to say so without playing five levels.
     */
    private static void clearPrerequisitesOfChoiceLevel(Path dir) throws Exception {
        Path worldDir = com.pvzce.common.util.WorldPaths.worldDir(dir, WORLD);
        com.pvzce.server.WorldStore store = new com.pvzce.server.WorldStore(dir);
        for (int i = 1; i <= 5; i++) {
            store.writeCompletion(worldDir, Identifier.parse("pvzce:yard/adventure/1_" + i),
                    PLANT_TEAM, 0, 0);
        }
    }

    private static LevelListS2C.LevelInfo levelInfo(ServerHarness harness, String levelId) throws Exception {
        LevelListS2C list = harness.levelList(WORLD);
        for (LevelListS2C.LevelInfo info : list.levels()) {
            if (info.id().equals(levelId)) {
                return info;
            }
        }
        throw new AssertionError(levelId + " is not in the level list");
    }

    /** Writes {@link #PICKUP_LEVEL} into a data pack the server scans at startup. */
    private static void writeFixtureLevel(Path dir) throws Exception {
        Path pack = dir.resolve("datapacks/buff_test");
        Path levelFile = pack.resolve("data/pvzce/levels/auto_pickup_test.json");
        Files.createDirectories(levelFile.getParent());
        Files.writeString(pack.resolve("pack.mcmeta"),
                "{\"pack\":{\"pack_format\":1,\"description\":\"buff flow test\"}}");
        Files.writeString(levelFile, """
                {
                  "id": "pvzce:auto_pickup_test",
                  "name": "Auto Pickup Test",
                  "width": 9,
                  "height": 1,
                  "scene": { "pvzce:grass": [
                    "0,0","1,0","2,0","3,0","4,0","5,0","6,0","7,0","8,0" ] },
                  "waves": [],
                  "slots": [ "pvzce:sun" ],
                  "max_seed_slots": 1,
                  "initial_sun": 50,
                  "unlock_resources": { "pvzce:sun": true },
                  "buffs": [ "pvzce:auto_collect" ]
                }
                """);
    }

    /**
     * A game directory with the shipped pack loaded, so the server sees the built-in levels.
     *
     * <p>The same shape {@code LevelRestartFlowTest} uses: the content registries are global and
     * loaded once, and the server reloads the same packs on its own thread.
     */
    private static Path gameDir() throws Exception {
        Path dir = TestDirs.create("pvzce-buff-flow");
        writeFixtureLevel(dir);
        BuiltInRegistries.bootstrap();
        PvzcePackets.register();
        PvzceResourceManager resources = new PvzceResourceManager(
                Thread.currentThread().getContextClassLoader());
        resources.init(dir);
        PvzceDataLoader.LoadResult result =
                new PvzceDataLoader().load(resources, BuiltInRegistries.ACCESS);
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        resources.close();
        return dir;
    }
}
