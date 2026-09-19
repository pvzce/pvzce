package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.TeamDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.Slot;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the duplication-driven defects that the capability
 * refactor was meant to eliminate. Each case here used to fail because the same
 * logic existed in two places and only one copy was right.
 */
class EntityModelRegressionTest {
    private static final Identifier PLANT_TEAM = PvzceIds.PLANT_TEAM;

    @BeforeAll
    static void load() throws Exception {
        // Content and convention tags together: the placement rules read tags,
        // so a data-only load would leave every cell unplantable.
        TestContent.loadBuiltInContentAndTags();
    }

    private static LevelDef level(int width, int height) {
        return new LevelDef(Identifier.withDefaultNamespace("regression"), "regression", "", width, height,
                Map.of(),
                List.of(new TeamDef(PLANT_TEAM, "植物方", "survive_waves"),
                        new TeamDef(PvzceIds.ZOMBIE_TEAM, "僵尸方", "plant_side_lost")),
                PLANT_TEAM, Map.of(), Map.of(), List.of(), 1F,
                List.of(Identifier.withDefaultNamespace("pea_shooter"), PvzceIds.SUN),
                Map.of(), 1000, LevelDef.LevelMusicDef.DEFAULT, List.of(), 6);
    }

    /**
     * Entity grid derivation must follow the level's own size. It used to clamp to
     * the default 9x5 board, so on a larger lawn a plant in row 5 looked at row 4
     * and a zombie "reached the house" in the wrong row.
     */
    @Test
    void entityGridFollowsLevelSizeNotTheDefaultBoard() {
        LevelServer level = new LevelServer(level(14, 8));
        PlantDef pea = BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace("pea_shooter"));
        assertNotNull(pea);

        PlantEntity far = level.spawnPlant(pea, level.team(PLANT_TEAM), 11, 6);
        assertEquals(11, far.gridX(), "column past the default width must not be clamped to 8");
        assertEquals(6, far.gridY(), "row past the default height must not be clamped to 4");

        level.spawnZombie(Identifier.withDefaultNamespace("basic_zombie"),
                level.team(PvzceIds.ZOMBIE_TEAM), 13.5F, 6);
        // Spawns are queued and streamed on the next flush (once per tick), which is
        // what keeps a wave of zombies to a single batch of packets.
        level.flushPending(packet -> {
        });
        assertTrue(level.zombiesInRow(6).size() == 1,
                "the far row must be a real row, so row lookup finds its zombie");
        assertTrue(level.zombiesInRow(4).isEmpty(), "row 6 must not alias row 4");
    }

    /**
     * Every entity serializes its own state symmetrically. Plants used to be saved
     * as a lossy {id,x,y,health} snapshot while zombies round-tripped fully, so a
     * restored sunflower fired immediately, a restored potato mine re-armed from
     * zero and a restored plant lost its carrier height and sub-cell offset.
     */
    @Test
    void plantStateRoundTripsThroughSaveAndRestore() {
        LevelServer level = new LevelServer(level(9, 5));
        PlantDef sunflower = BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace("sunflower"));
        assertNotNull(sunflower);

        PlantEntity original = level.spawnPlant(sunflower, level.team(PLANT_TEAM), 3, 2);
        original.setHeight(0.42F);
        original.setCellX(original.cellX() + 0.06F);
        // Run a while so the production timer is genuinely mid-cycle.
        for (int i = 0; i < 60; i++) {
            level.tick(packet -> {
            });
        }
        CompoundTag saved = original.saveState();

        LevelServer restoredLevel = new LevelServer(level(9, 5));
        restoredLevel.restore(saveTagFor(level, saved, original.entityKind()));
        PlantEntity restored = restoredLevel.plantsAt(3, 2).stream().findFirst().orElseThrow();

        assertEquals(original.health(), restored.health());
        assertEquals(original.height(), restored.height(), 0.0001F);
        assertEquals(original.cellX(), restored.cellX(), 0.0001F);
        assertEquals(original.animation(), restored.animation());
        assertEquals(original.age(), restored.age());
    }

    /**
     * Wraps one entity snapshot in a minimal running save the level will restore.
     *
     * <p>The {@code Kind} key is part of the contract: {@code LevelServer.save} writes it
     * next to every entity snapshot, and restore builds the shell from it.
     */
    private static CompoundTag saveTagFor(LevelServer source, CompoundTag entityTag, String kind) {
        CompoundTag root = source.save();
        entityTag.putString("Kind", kind);
        com.pvzce.common.nbt.ListTag entities = new com.pvzce.common.nbt.ListTag();
        entities.add(entityTag);
        root.put("Entities", entities);
        root.putString("GameState", "running");
        return root;
    }

    /**
     * A slot id must mean the same thing to the seed chooser and to the server's
     * card bar. The client used to invent a 0-cost plant card for unknown ids while
     * the server dropped the slot, so {@code combat_test}'s {@code pvzce:hammer}
     * entry was selectable but never granted.
     */
    @Test
    void everyReviewableSlotResolvesIdenticallyOnBothSides() {
        assertTrue(SlotResolver.isResolvable(Identifier.withDefaultNamespace("hammer")),
                "tools/hammer.json ships a hammer card, so the card must be resolvable");
        SlotResolver.ResolvedCard hammer = SlotResolver.resolve(Identifier.withDefaultNamespace("hammer"))
                .orElseThrow();
        assertEquals(Slot.Kind.TOOL, hammer.kind());

        for (Identifier slotId : level(9, 5).slots()) {
            assertTrue(SlotResolver.resolve(slotId).isPresent(),
                    "level slot " + slotId + " must resolve on both sides");
        }

        // A genuinely unknown id is rejected on both sides instead of half-working.
        assertTrue(SlotResolver.resolve(Identifier.withDefaultNamespace("not_a_card")).isEmpty());
    }

    /**
     * Zombie capabilities must be able to stop ground shots without the entity
     * knowing which capability did it.
     */
    @Test
    void zombieCapabilitiesDecideWhatGroundShotsMayHit() {
        LevelServer level = new LevelServer(level(9, 5));
        level.spawnZombie(Identifier.withDefaultNamespace("balloon_zombie"),
                level.team(PvzceIds.ZOMBIE_TEAM), 5.5F, 1);
        level.flushPending(packet -> {
        });
        ZombieEntity balloon = level.zombiesInRow(1).stream().findFirst().orElseThrow();
        assertFalse(balloon.canBeHitByGround(), "a flying zombie ignores ground-layer shots");
        assertFalse(balloon.isGrounded());
    }
}
