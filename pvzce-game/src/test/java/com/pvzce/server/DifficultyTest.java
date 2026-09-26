package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LevelDialogue;
import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.content.LevelUnlock;
import com.pvzce.api.content.TeamDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.Difficulty;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four difficulty tiers: what they change, where they live, and what a save carries.
 *
 * <p>Three things are worth pinning here and each of them shipped as a question rather than as a
 * fact: the tiers move the four numbers they claim to (and {@code NORMAL} moves none of them, which
 * is what makes "the original's difficulty" true by construction); the tier is a fact about the
 * <em>world</em>, so it survives a save/load round trip; and it is written into the level's rules as
 * a multiplier, so the file must carry the level's own numbers rather than the folded ones - a save
 * that kept the fold would be multiplied a second time on load.
 */
class DifficultyTest {
    private static final Identifier PLANT_TEAM = Identifier.withDefaultNamespace("plant_team");
    private static final Identifier ZOMBIE_TEAM = Identifier.withDefaultNamespace("zombie_team");
    private static final Identifier BASIC_ZOMBIE = Identifier.withDefaultNamespace("basic_zombie");

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    // ------------------------------------------------------------------
    // The table
    // ------------------------------------------------------------------

    @Test
    void theOriginalTierChangesNothing() {
        assertEquals(1F, Difficulty.NORMAL.zombieHealth());
        assertEquals(1F, Difficulty.NORMAL.zombieSpeed());
        assertEquals(1F, Difficulty.NORMAL.spawnInterval());
        assertEquals(1F, Difficulty.NORMAL.sunRate());
        assertTrue(Difficulty.NORMAL.ruleFactors().isEmpty(),
                "and it contributes no factors at all, so applying it is not four multiplications");
        for (Difficulty tier : Difficulty.values()) {
            LevelServer level = level(tier);
            assertEquals(tier.zombieSpeed(),
                    level.rules().getFloat(PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER), 0.0001F,
                    tier.key() + ": a level that wrote no speed rule of its own plays at exactly"
                            + " the tier's factor, which is 1 only for the original");
        }
    }

    @Test
    void everyTierIsMonotonic() {
        // A tier table is read top to bottom, so "harder" has to mean the same direction in all
        // four columns. This is the assertion that would have caught a sign typed the wrong way.
        for (int i = 1; i < Difficulty.values().length; i++) {
            Difficulty easier = Difficulty.values()[i - 1];
            Difficulty harder = Difficulty.values()[i];
            assertTrue(harder.zombieHealth() > easier.zombieHealth(), "health rises with the tier");
            assertTrue(harder.zombieSpeed() > easier.zombieSpeed(), "so does speed");
            assertTrue(harder.spawnInterval() < easier.spawnInterval(),
                    "the gap between zombies falls");
            assertTrue(harder.sunRate() < easier.sunRate(), "and so does sun");
        }
    }

    @Test
    void theSpawnIntervalIsInvertedIntoTheRuleItLandsIn() {
        // The table says "gaps 25% longer" for EASY; the rule it lands in is a *speed* multiplier,
        // so the value written is 1/1.15. That inversion is folded in one place, and this is it.
        Map<Identifier, Float> factors = Difficulty.EASY.ruleFactors();
        assertEquals(1F, factors.get(PvzceIds.RULE_ZOMBIE_SPAWN_SPEED_MULTIPLIER)
                * Difficulty.EASY.spawnInterval(), 0.0001F,
                "applying the factor and the interval has to be a no-op on the gap itself");
        assertTrue(factors.get(PvzceIds.RULE_ZOMBIE_SPAWN_SPEED_MULTIPLIER) < 1F,
                "an easier tier spawns more slowly, so the speed rule goes down");
    }

    @Test
    void aNameThatIsNotATierIsNotATier() {
        assertFalse(Difficulty.isKnown("impossible"));
        assertFalse(Difficulty.isKnown(""));
        assertFalse(Difficulty.isKnown(null));
        assertTrue(Difficulty.isKnown("hard"));
        assertTrue(Difficulty.isKnown("HELL"));
        assertEquals(Difficulty.DEFAULT, Difficulty.parse("nonsense"),
                "parsing a bad name gives the original rather than throwing; refusing it is the"
                        + " packet handler's job");
        assertEquals(Difficulty.HARD, Difficulty.parse("Hard"));
    }

    // ------------------------------------------------------------------
    // What a level plays with
    // ------------------------------------------------------------------

    @Test
    void theTierIsFoldedIntoTheLevelsOwnRules() {
        LevelServer hell = level(Difficulty.HELL);
        assertEquals(Difficulty.HELL.zombieSpeed(), hell.rules()
                        .getFloat(PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER), 0.0001F);
        assertEquals(Difficulty.HELL.zombieHealth(), hell.rules()
                        .getFloat(PvzceIds.RULE_ZOMBIE_HEALTH_MULTIPLIER), 0.0001F);
        assertEquals(1F / Difficulty.HELL.spawnInterval(), hell.rules()
                        .getFloat(PvzceIds.RULE_ZOMBIE_SPAWN_SPEED_MULTIPLIER), 0.0001F);
        assertEquals(Difficulty.HELL.sunRate(), hell.rules()
                        .getFloat(PvzceIds.RULE_SUN_RATE_MULTIPLIER), 0.0001F);
    }

    @Test
    void itMultipliesWhatTheLevelWroteRatherThanReplacingIt() {
        // 1-5 writes `zombie_speed_multiplier: 1.5`; on HELL it has to play at 1.5 x 1.25.
        LevelServer level = withSpeed(1.5F, Difficulty.HELL);
        assertEquals(1.5F * Difficulty.HELL.zombieSpeed(),
                level.rules().getFloat(PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER), 0.0001F);
    }

    @Test
    void switchingTheTierMidRunRetunesWithoutLosingTheLevelsOwnNumber() {
        LevelServer level = withSpeed(1.5F, Difficulty.HARD);
        float atHard = level.rules().getFloat(PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER);
        assertTrue(level.setDifficulty(Difficulty.EASY), "the tier changes");
        assertEquals(1.5F * Difficulty.EASY.zombieSpeed(),
                level.rules().getFloat(PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER), 0.0001F,
                "the level's own 1.5 is still in there");
        assertFalse(level.setDifficulty(Difficulty.EASY), "and setting the same tier twice is a no-op");
        assertNotEquals(atHard, level.rules().getFloat(PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER));
    }

    @Test
    void aZombieSpawnedAfterTheSwitchHasTheNewHealth() {
        LevelServer level = level(Difficulty.NORMAL);
        ZombieEntity before = spawn(level, BASIC_ZOMBIE);
        int normalHealth = before.health();
        level.setDifficulty(Difficulty.HELL);
        ZombieEntity after = spawn(level, BASIC_ZOMBIE);

        assertEquals(normalHealth, before.health(), "what is already on the lawn keeps what it spawned with");
        assertEquals(Math.round(normalHealth * Difficulty.HELL.zombieHealth()), after.health(),
                "and the next one arrives on the new tier");
    }

    // ------------------------------------------------------------------
    // The world's own fact
    // ------------------------------------------------------------------

    @Test
    void theTierIsAPropertyOfTheWorldAndSurvivesASave() {
        PlayerProfile profile = PlayerProfile.starter();
        assertEquals(Difficulty.DEFAULT, profile.difficulty(), "a fresh world is the original's");
        assertTrue(profile.setDifficulty(Difficulty.HELL));

        PlayerProfile restored = PlayerProfile.load(profile.save());
        assertEquals(Difficulty.HELL, restored.difficulty());
        assertFalse(restored.setDifficulty(Difficulty.HELL), "and the round trip did not change it");
    }

    @Test
    void aProfileWrittenBeforeTheTiersExistedComesBackOnTheOriginal() {
        // A record with no Difficulty key at all: what every save written before this field looks
        // like. It must come back on the original, not on whatever {@code values()[0]} happens to
        // be - a world must not become harder because a field was added to the format.
        assertEquals(Difficulty.DEFAULT, PlayerProfile.load(new CompoundTag()).difficulty());

        CompoundTag handwritten = PlayerProfile.starter().save();
        handwritten.putString("Difficulty", "impossible");
        assertEquals(Difficulty.DEFAULT, PlayerProfile.load(handwritten).difficulty(),
                "and a hand-edited name that is not a tier falls back rather than throwing");
    }

    @Test
    void aSaveCarriesTheLevelsOwnNumbersRatherThanTheFoldedOnes() {
        // The one way this whole design can go wrong: if the file kept the tier's factor, loading
        // would multiply a second time and a resumed HELL run would be unplayable.
        LevelServer level = withSpeed(1.5F, Difficulty.HELL);
        CompoundTag save = level.save();

        LevelServer resumed = withSpeed(1.5F, Difficulty.HELL);
        resumed.restore(save);
        assertEquals(1.5F * Difficulty.HELL.zombieSpeed(),
                resumed.rules().getFloat(PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER), 0.0001F,
                "a resumed run plays at the tier it was saved on, not at its square");
    }

    @Test
    void aLevelCreatedWithoutAProfilePlaysTheOriginal() {
        // Tests, the plant AI and the editor's preview all build a level with no world behind it,
        // and every one of them expects the numbers the level file wrote.
        LevelServer level = new LevelServer(withSpeedDef(1.5F));
        assertEquals(Difficulty.NORMAL, level.difficulty());
        assertEquals(1.5F, level.rules().getFloat(PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER), 0.0001F);
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private static LevelServer level(Difficulty tier) {
        return withSpeed(0F, tier);
    }

    /** A level whose own {@code zombie_speed_multiplier} is written, played on one tier. */
    private static LevelServer withSpeed(float speed, Difficulty tier) {
        LevelDef def = withSpeedDef(speed);
        PlayerProfile profile = PlayerProfile.starter();
        profile.setDifficulty(tier);
        return new LevelServer(def, List.of(), LevelServer.SeedContext.forProfile(def, profile));
    }

    private static LevelDef withSpeedDef(float speed) {
        LevelDef demo = BuiltInRegistries.LEVELS.get(
                Identifier.withDefaultNamespace("yard/adventure/demo_level"));
        Map<Identifier, com.google.gson.JsonElement> rules = speed <= 0F
                ? Map.of()
                : Map.of(PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER,
                        new com.google.gson.JsonPrimitive(speed));
        return new LevelDef(
                Identifier.withDefaultNamespace("difficulty_test"), "难度测试", "", 9, 5,
                demo.scene(),
                List.of(new TeamDef(PLANT_TEAM, "植物方", "survive_waves"),
                        new TeamDef(ZOMBIE_TEAM, "僵尸方", "plant_side_lost")),
                PLANT_TEAM, rules, Map.of(), List.of(), 1F,
                List.of(Identifier.withDefaultNamespace("pea_shooter"),
                        Identifier.withDefaultNamespace("sun")),
                Map.of(), PvzceConstants.INITIAL_SUN, LevelDef.LevelMusicDef.DEFAULT, List.of(),
                6, LevelRewards.NONE, LevelUnlock.NONE, List.of(), LevelDialogue.EMPTY);
    }

    private static ZombieEntity spawn(LevelServer level, Identifier type) {
        ZombieEntity zombie = level.spawnZombie(type, level.team(ZOMBIE_TEAM), 6F, 2);
        level.flushPending(null);
        return zombie;
    }
}
