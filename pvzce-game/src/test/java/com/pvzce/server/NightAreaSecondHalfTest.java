package com.pvzce.server;

import com.pvzce.api.content.GraveFieldData;
import com.pvzce.api.content.LevelBelt;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.ZombieDef;
import com.pvzce.api.content.ZombieStatus;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceParticles;
import com.pvzce.common.capability.plant.FreezeAllCapability;
import com.pvzce.common.capability.plant.NocturnalCapability;
import com.pvzce.common.capability.plant.ShooterCapability;
import com.pvzce.common.capability.zombie.ArmorCapability;
import com.pvzce.common.capability.zombie.SummonDancersCapability;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.tag.PvzceTags;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.ConveyorBelt;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Night area's second half: 2-6 to 2-10.
 *
 * <p>What is pinned here is what these levels were asked to be - the original's reward ladder
 * and its new units, the graves every night lawn opens with, and the finale's conveyor - rather
 * than their wave tables, which are balance data and belong to the same tuning pass as every
 * other level's.
 */
class NightAreaSecondHalfTest {
    private static final List<String> SECOND_HALF = List.of("2_6", "2_7", "2_8", "2_9", "2_10");

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static LevelDef level(String name) {
        LevelDef def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/" + name));
        assertNotNull(def, name + " must be part of the built-in adventure");
        return def;
    }

    /** A level with no wave table, so a test ticks the board it built and nothing else. */
    private static LevelServer board(String name) {
        return new LevelServer(TestLevels.withWaves(level(name), List.of()));
    }

    private static String firstUnlock(LevelDef def) {
        return def.rewards().firstClear().stream()
                .filter(LevelRewards.Reward::isUnlock)
                .map(reward -> reward.id().orElseThrow().toString())
                .findFirst()
                .orElse(null);
    }

    /**
     * The original's ladder for the second half of the night.
     *
     * <p>2-6 the scaredy-shroom, 2-7 the ice-shroom, 2-8 the doom-shroom, 2-10 the lily pad -
     * and 2-9, which in the original hands over the zombies' note rather than a plant, pays the
     * standard money bag instead (the same shape 1-9 has).
     */
    @Test
    void theRewardLadderMatchesTheOriginal() {
        assertEquals("pvzce:scaredy_shroom", firstUnlock(level("2_6")));
        assertEquals("pvzce:ice_shroom", firstUnlock(level("2_7")));
        assertEquals("pvzce:doom_shroom", firstUnlock(level("2_8")));
        assertNull(firstUnlock(level("2_9")));
        assertEquals("pvzce:lily_pad", firstUnlock(level("2_10")));

        // Every unlock needs a card to land on, and 2-9 must still pay something.
        for (String name : List.of("2_6", "2_7", "2_8", "2_10")) {
            assertNotNull(BuiltInRegistries.SLOT_TYPES.get(
                            PvzceIds.id(firstUnlock(level(name)).substring("pvzce:".length()))),
                    name + "'s unlock needs a card");
        }
        // 2-9 still gives no plant - the original's note is not an item - but it does hand over
        // the spore-range buff, which is the one the original's note unlocks and the one 2-10's
        // mushroom belt wants.
        assertEquals(1, level("2_9").rewards().firstClear().size(),
                "2-9 declares exactly one first-clear entry");
        assertEquals("pvzce:mushroom_range",
                level("2_9").rewards().firstClear().get(0).id().orElseThrow().toString());
        assertTrue(level("2_9").rewards().firstClear().get(0).isBuff(), "and it is a buff");
        assertEquals(LevelRewards.DEFAULT.repeat(), level("2_9").rewards().repeat(),
                "its repeat stipend is the standard one");

        // A chain, not a fan: each of the five needs the one before it.
        assertEquals("pvzce:yard/adventure/2_5", requires(level("2_6")));
        assertEquals("pvzce:yard/adventure/2_6", requires(level("2_7")));
        assertEquals("pvzce:yard/adventure/2_7", requires(level("2_8")));
        assertEquals("pvzce:yard/adventure/2_8", requires(level("2_9")));
        assertEquals("pvzce:yard/adventure/2_9", requires(level("2_10")));
    }

    private static String requires(LevelDef def) {
        return def.unlock().requires().isEmpty()
                ? null : def.unlock().requires().get(0).id().orElseThrow().toString();
    }

    private static void assertNull(Object value) {
        assertTrue(value == null, "expected nothing, got " + value);
    }

    /**
     * The scaredy-shroom: a long-range shooter that ducks and stops firing when something is
     * close.
     *
     * <p>The whole plant is the pair - same spore as the puff-shroom's, over any distance, and
     * a zombie within a cell of it silences it entirely.
     */
    @Test
    void theScaredyShroomDucksAndStopsFiring() {
        PlantDef scaredy = BuiltInRegistries.PLANTS.get(PvzceIds.id("scaredy_shroom"));
        assertNotNull(scaredy);
        assertEquals(25, scaredy.cost().resources().getOrDefault(PvzceIds.SUN, 0));
        assertNotNull(scaredy.capability(NocturnalCapability.class), "it is a mushroom");

        ShooterCapability shooter = scaredy.capability(ShooterCapability.class).orElseThrow();
        assertEquals(20, shooter.shots().get(0).damage());
        assertTrue(shooter.shots().get(0).hasUnlimitedRange(),
                "its spore crosses the lane, unlike the puff-shroom's");
        assertEquals(1F, shooter.hideWithin(), 0.0001F);

        LevelServer level = board("2_6");
        var plant = level.spawnPlant(scaredy, level.team(PvzceIds.PLANT_TEAM), 3, 2);
        assertNotNull(plant);

        // Far away: it fires, exactly like any other shooter.
        ZombieEntity far = level.spawnZombie(PvzceIds.id("basic_zombie"),
                level.team(PvzceIds.ZOMBIE_TEAM), plant.cellX() + 5F, 2);
        assertNotNull(far);
        tick(level, 120);
        assertEquals(EntityAnimations.SHOOT, plant.animation(),
                "with a zombie down the lane it shoots");
        assertTrue(far.health() < far.def().health(), "and the spores land");

        // Next to it: it ducks and does not fire again. The settle first is the spore already
        // in the air - "it stops firing" is about what it launches, not about what it launched.
        far.setCellX(plant.cellX() + 1F);
        tick(level, 30);
        assertEquals(EntityAnimations.HIDE, plant.animation(), "a zombie within a cell makes it duck");
        int healthBefore = far.health();
        tick(level, 200);
        assertEquals(EntityAnimations.HIDE, plant.animation(),
                "a zombie within a cell makes it duck");
        assertEquals(healthBefore, far.health(),
                "and a ducked scaredy-shroom fires nothing at all, however long it waits");
        assertEquals(3, plant.gridX(), "it does not move out of its cell either");
    }

    /**
     * The ice-shroom freezes the whole lawn once, and then it is gone.
     *
     * <p>Rows rather than a radius, a pea's worth of damage, the original's durations - and the
     * two exemptions the original makes, which are the tag's whole content: a zombie the cold
     * cannot hold still takes the hit and the chill.
     */
    @Test
    void theIceShroomFreezesTheWholeLawnOnce() {
        PlantDef ice = BuiltInRegistries.PLANTS.get(PvzceIds.id("ice_shroom"));
        assertNotNull(ice);
        assertEquals(75, ice.cost().resources().getOrDefault(PvzceIds.SUN, 0));
        assertNotNull(ice.capability(NocturnalCapability.class));
        FreezeAllCapability freeze = ice.capability(FreezeAllCapability.class).orElseThrow();
        assertEquals(360, freeze.freezeTicks(), "six seconds of standing still");
        assertEquals(960, freeze.chill().ticks(), "then sixteen seconds of chill");
        assertEquals(0.5F, freeze.chill().magnitude(), 0.0001F);

        // The exemptions are data, and the two the original names are in it.
        assertTrue(PvzceTags.ZOMBIES.contains(PvzceTags.ZOMBIE_FREEZE_IMMUNE,
                PvzceIds.id("balloon_zombie")), "an airborne zombie is not frozen");
        assertTrue(PvzceTags.ZOMBIES.contains(PvzceTags.ZOMBIE_FREEZE_IMMUNE,
                PvzceIds.id("miner_zombie")), "nor is the one under the lawn");
        assertFalse(PvzceTags.ZOMBIES.contains(PvzceTags.ZOMBIE_FREEZE_IMMUNE,
                PvzceIds.id("basic_zombie")), "an ordinary zombie freezes");

        LevelServer level = board("2_7");
        var plant = level.spawnPlant(ice, level.team(PvzceIds.PLANT_TEAM), 4, 2);
        assertNotNull(plant);
        ZombieEntity basic = level.spawnZombie(PvzceIds.id("basic_zombie"),
                level.team(PvzceIds.ZOMBIE_TEAM), 8F, 0);
        ZombieEntity balloon = level.spawnZombie(PvzceIds.id("balloon_zombie"),
                level.team(PvzceIds.ZOMBIE_TEAM), 8F, 4);
        assertNotNull(basic);
        assertNotNull(balloon);

        tick(level, freeze.fuseTicks() + 2);
        assertTrue(plant.isRemoved(), "a used ice-shroom leaves nothing behind");
        assertEquals(basic.def().health() - freeze.damage(), basic.health(), "one pea of damage");
        assertTrue(basic.hasStatus(ZombieStatus.IMMOBILIZED), "and the lawn stops");
        assertTrue(basic.chilled(), "with the chill to follow");
        assertEquals(balloon.def().health() - freeze.damage(), balloon.health(),
                "the exempt ones are still hit");
        assertTrue(balloon.chilled(), "and still chilled");
        assertFalse(balloon.hasStatus(ZombieStatus.IMMOBILIZED),
                "but the cold does not hold them");

        // A frozen zombie is published as frozen: the client draws the ice and stops the clip
        // from this flag, and it cannot be worked out from "slowed" (which only scales speed).
        assertTrue(basic.updatePacket().frozen(), "the update carries the freeze");
        assertFalse(balloon.updatePacket().frozen(), "and not for a zombie the cold let go");

        // The freeze is a status with a clock, not a permanent state.
        tick(level, freeze.freezeTicks() + 5);
        assertFalse(basic.hasStatus(ZombieStatus.IMMOBILIZED), "it thaws");
        assertTrue(basic.chilled(), "and is still chilled afterwards");
    }

    /**
     * The spore is purple and its impact is a spore burst.
     *
     * <p>The projectile used the bite line's own cloud ({@code puff_splat}, the eight big purple
     * puffs a chomper leaves behind) as its impact, so every spore that landed looked like
     * something had taken a bite out of the zombie. The bite keeps that effect; the spore gets
     * its own, smaller one.
     */
    @Test
    void theSporeLooksLikeASporeWhenItLands() {
        var puff = BuiltInRegistries.PROJECTILES.get(PvzceIds.id("puff"));
        assertNotNull(puff);
        assertEquals("pvzce:spore_splat", puff.impactParticle().map(Object::toString).orElse(""),
                "the spore bursts into spores, not into a chomp cloud");
        assertTrue(BuiltInRegistries.PARTICLES.containsKey(PvzceIds.id("spore_splat")),
                "and the burst has to exist");
        assertEquals(PvzceIds.id("puff_splat"), PvzceParticles.CHOMP,
                "while the bite line keeps the cloud it is named after");
    }

    /** The football zombie: the original's fast, heavily helmeted one. */
    @Test
    void theFootballZombieIsTheFastArmouredOne() {
        ZombieDef football = BuiltInRegistries.ZOMBIES.get(PvzceIds.id("football_zombie"));
        assertNotNull(football);
        assertEquals(200, football.health(), "the body is an ordinary zombie's");
        assertEquals(0.43F, football.moveSpeed(), 0.0001F, "and it walks about twice as fast");
        ArmorCapability armor = football.capability(ArmorCapability.class).orElseThrow();
        assertEquals(1400, armor.armor().get(0).durability(), "the original's helmet");
        assertEquals("top", armor.armor().get(0).position());
        assertEquals(1, football.equipment().size(), "the helmet is drawn and wears through");

        assertTrue(level("2_6").previewZombieIds().contains("pvzce:football_zombie"),
                "2-6 is where it arrives: " + level("2_6").previewZombieIds());
    }

    /**
     * The dancing zombie calls four backup dancers, and refills the formation.
     *
     * <p>The crew is spawned onto the dancer's own team, which is what makes a hypnotised
     * dancer's crew fight for the plants without a branch anywhere.
     */
    @Test
    void theDancingZombieSummonsAndRefillsItsCrew() {
        ZombieDef dancer = BuiltInRegistries.ZOMBIES.get(PvzceIds.id("dancing_zombie"));
        assertNotNull(dancer);
        assertEquals(500, dancer.health());
        SummonDancersCapability summon =
                dancer.capability(SummonDancersCapability.class).orElseThrow();
        assertEquals(PvzceIds.id("backup_dancer"), summon.dancer());
        assertEquals(4, summon.count());
        assertTrue(BuiltInRegistries.ZOMBIES.containsKey(PvzceIds.id("backup_dancer")),
                "the crew has to exist");

        LevelServer level = board("2_8");
        ZombieEntity dancerEntity = level.spawnZombie(PvzceIds.id("dancing_zombie"),
                level.team(PvzceIds.ZOMBIE_TEAM), 8.5F, 2);
        assertNotNull(dancerEntity);
        tick(level, 2);
        assertEquals(EntityAnimations.MOONWALK, dancerEntity.animation(),
                "it moonwalks in, which is the original's fast entrance");

        // Walk in to the summon line, stop, raise the arms, and call. The pose is checked
        // *during* the raise: the capability takes the tick over while it summons, so the walk
        // loop that publishes a gait never runs - the state has to be published from the
        // capability itself or the arm raise is invisible.
        for (int i = 0; i < 600 && !EntityAnimations.ARM_RAISE.equals(dancerEntity.animation()); i++) {
            tick(level, 1);
        }
        assertEquals(EntityAnimations.ARM_RAISE, dancerEntity.animation(),
                "it stops and raises its arms to call the crew");

        tick(level, 600);
        assertEquals(4, crew(level).size(), "four dancers, in the original's cross: " + crew(level));
        assertEquals(EntityAnimations.WALK, dancerEntity.animation(), "then it dances forward");
        for (ZombieEntity member : crew(level)) {
            assertEquals(dancerEntity.team(), member.team(), "the crew is on the dancer's side");
            assertTrue(Math.abs(member.cellX() - dancerEntity.cellX()) <= 1.5F,
                    "and stands in the formation");
        }

        // A hole is filled again, which is what keeps the crew a threat - but not quickly: the
        // refill interval is a floor (twenty seconds), because a crew replaced as fast as it is
        // killed is a crew the player can never finish off.
        assertEquals(1200, summon.resummonTicks(), "twenty seconds between calls");
        ZombieEntity fallen = crew(level).get(0);
        fallen.remove();
        tick(level, 100);
        assertEquals(3, crew(level).size(), "a hole stays open for a while: " + crew(level));
        tick(level, summon.resummonTicks());
        assertEquals(4, crew(level).size(), "the formation is back to four: " + crew(level));
    }

    /** The backup dancers themselves: ordinary bodies at the formation's pace. */
    @Test
    void theBackupDancerIsAnOrdinaryBody() {
        ZombieDef backup = BuiltInRegistries.ZOMBIES.get(PvzceIds.id("backup_dancer"));
        assertNotNull(backup);
        assertEquals(200, backup.health());
        assertEquals(0.18F, backup.moveSpeed(), 0.0001F, "the dance step, not a march");
        assertTrue(backup.equipment().isEmpty(), "it wears nothing");
    }

    private static List<ZombieEntity> crew(LevelServer level) {
        List<ZombieEntity> crew = new ArrayList<>();
        for (ZombieEntity zombie : level.entities().stream()
                .filter(ZombieEntity.class::isInstance).map(ZombieEntity.class::cast).toList()) {
            if (zombie.defId().equals(PvzceIds.id("backup_dancer")) && !zombie.isRemoved()) {
                crew.add(zombie);
            }
        }
        return crew;
    }

    /**
     * Every night lawn opens with the original's graves.
     *
     * <p>The count is the original's; where they stand is the lawn's business, so what is
     * asserted is the count, that they are in the half away from the house, and that the whole
     * board is still painted (the graves stand <em>on</em> the lawn, they do not replace cells
     * of it).
     */
    @Test
    void everyNightLawnScattersTheOriginalsGraves() {
        // `Board::AddGraveStones` per level: the count, and the column its leftmost grave may be
        // in. The original writes one call per column (2-1 gets one in column 6, one in 7 and two
        // in 8); the level file carries the total and the left edge.
        var expected = java.util.Map.of(
                "2_1", new int[]{4, 6},
                "2_2", new int[]{4, 6},
                "2_3", new int[]{4, 6},
                "2_4", new int[]{7, 5},
                "2_6", new int[]{7, 5},
                "2_7", new int[]{11, 4},
                "2_8", new int[]{11, 4},
                "2_9", new int[]{11, 4},
                "2_10", new int[]{13, 3});
        for (var entry : expected.entrySet()) {
            LevelDef def = level(entry.getKey());
            GraveFieldData field = LevelMechanics
                    .dataOf(def, PvzceIds.MECHANIC_GRAVE_FIELD, GraveFieldData.class)
                    .orElseThrow(() -> new AssertionError(entry.getKey() + " scatters no graves"));
            assertEquals(entry.getValue()[0], field.count(), entry.getKey() + "'s grave count");
            assertEquals(entry.getValue()[1], field.minX(), entry.getKey() + "'s leftmost column");
            assertEquals(9 * 5, def.scene().get(PvzceIds.GRASS).size(),
                    entry.getKey() + " paints the whole lawn and lays the graves on it");

            LevelServer level = board(entry.getKey());
            var graves = level.graveCells();
            assertEquals(entry.getValue()[0], graves.size(),
                    entry.getKey() + " stands its graves up when it is built: " + graves);
            for (var cell : graves) {
                assertTrue(cell.x() >= entry.getValue()[1],
                        entry.getKey() + " put a grave at column " + cell.x());
                assertTrue(PvzceIds.SURFACE_GRAVE.equals(cell.value().surfaceClass()));
            }
        }
    }

    /**
     * 2-10 is the original's Night finale: a conveyor belt of the night's plants over a lawn
     * with thirteen graves on it.
     *
     * <p>The grave busters are capped at thirteen, one per grave - the original's "Max Count",
     * which is the one card on any belt in the game that runs out.
     */
    @Test
    void theFinalNightLevelIsABeltOverThirteenGraves() {
        LevelDef def = level("2_10");
        assertTrue(LevelMechanics.dealsItsOwnCards(def), "a belt level has no seed chooser");
        assertFalse(def.slots().size() > 0, "and no fixed cards either");

        LevelBelt belt = LevelMechanics.dataOf(def, PvzceIds.MECHANIC_CONVEYOR, LevelBelt.class)
                .orElseThrow();
        assertEquals(7, belt.cards().size(), "the original's seven night plants");
        var capped = belt.cards().stream()
                .filter(card -> card.card().equals(PvzceIds.id("grave_buster")))
                .findFirst().orElseThrow();
        assertEquals(13, capped.maxCount(), "one grave buster per grave");
        assertEquals(13, LevelMechanics.dataOf(def, PvzceIds.MECHANIC_GRAVE_FIELD, GraveFieldData.class)
                .orElseThrow().count());

        // The cap is a belt rule, and the belt is where it can be tested: with a pool of one
        // capped card and one unlimited one, the capped one runs out and the belt keeps going.
        LevelBelt mixed = new LevelBelt(1, 6, 0, List.of(
                new LevelBelt.BeltCard(PvzceIds.id("grave_buster"), 1, 3),
                new LevelBelt.BeltCard(PvzceIds.id("puff_shroom"), 1, LevelBelt.BeltCard.UNLIMITED)));
        Random rng = new Random(7);
        ConveyorBelt running = new ConveyorBelt(mixed, rng);
        java.util.Map<Identifier, Integer> dealt = new java.util.HashMap<>();
        for (int i = 0; i < 200; i++) {
            Identifier drawn = mixed.pick(rng, card -> dealt.getOrDefault(card, 0));
            assertNotNull(drawn, "an unlimited card is always there to draw");
            dealt.merge(drawn, 1, Integer::sum);
            // Spend whatever landed, so the belt never fills up.
            for (ConveyorBelt.Card card : running.cards()) {
                running.take(card.id());
            }
            running.tick(rng);
        }
        assertEquals(3, dealt.getOrDefault(PvzceIds.id("grave_buster"), 0),
                "the capped card is dealt exactly its max_count and never again");
        assertTrue(dealt.getOrDefault(PvzceIds.id("puff_shroom"), 0) > 0,
                "and the rest of the pool keeps coming");

        // The belt's own tally is the one that counts deliveries, and it survives a save: a
        // belt whose only card is capped stops producing once it has handed them all out, and
        // comes back from a save still finished rather than offering a fresh set.
        LevelBelt onlyCapped = new LevelBelt(1, 6, 0, List.of(
                new LevelBelt.BeltCard(PvzceIds.id("grave_buster"), 1, 2)));
        ConveyorBelt only = new ConveyorBelt(onlyCapped, new Random(3));
        for (int i = 0; i < 40 && only.canProduce(); i++) {
            only.tick(new Random(i));
        }
        assertEquals(2, only.cards().size(),
                "it handed out its two and then stopped, rather than filling the belt with them");
        java.util.Set<Identifier> kinds = new java.util.HashSet<>();
        for (ConveyorBelt.Card card : only.cards()) {
            kinds.add(card.cardId());
        }
        assertEquals(java.util.Set.of(PvzceIds.id("grave_buster")), kinds);
        assertFalse(only.canProduce(), "and it has nothing left to hand out");
        ConveyorBelt resumed = new ConveyorBelt(onlyCapped, new Random(3));
        resumed.restore(only.save());
        assertFalse(resumed.canProduce(), "a resumed belt remembers what it already dealt");
    }

    /** The bridge a level needs for a headless run: these tests read the board, not packets. */
    private static void tick(LevelServer level, int ticks) {
        LevelServer.ServerBridge bridge = packet -> {
        };
        for (int i = 0; i < ticks; i++) {
            level.tick(bridge);
            level.flushPending(bridge);
        }
    }
}
