package com.pvzce.server.ai;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.server.Team;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal plant-team AI: every 120 ticks it fills a free cell with priority
 * defense -> economy -> offense. Enable a plant-team AI level with the env var
 * {@code pvzce:plant_ai = true}.
 *
 * <p>It places through {@link LevelServer#spawnPlant}, so AI plants get the same
 * stacking height, carrier offset and {@code onPlaced} handling as player-planted
 * ones. Previously the AI built a {@code PlantEntity} by hand and skipped all of
 * that, which is why AI plants on a lily pad or flower pot floated at the wrong
 * height.
 */
public final class PlantAIPlayer {
    private static final Identifier SUN = PvzceIds.SUN;
    private static final int DECISION_INTERVAL_TICKS = 120;
    private int cooldown;

    public void tick(LevelServer level) {
        if (cooldown-- > 0) {
            return;
        }
        cooldown = DECISION_INTERVAL_TICKS;
        Team team = level.team(PvzceIds.PLANT_TEAM);
        if (team == null || !com.pvzce.common.network.packet.GameStateS2C.RUNNING.equals(level.gameState())) {
            return;
        }
        PlantDef chosen = choose(level, team);
        if (chosen == null) {
            return;
        }
        int cost = chosen.cost().amountOf(SUN);
        if (!team.consume(SUN, cost)) {
            return;
        }
        int[] cell = findFreeCell(level, chosen);
        if (cell == null) {
            team.addResource(SUN, cost);
            return;
        }
        level.spawnPlant(chosen, team, cell[0], cell[1]);
    }

    /**
     * Cells the AI considers "its half" of the lawn, counted from the house side.
     * Beyond this it lets the player's own plants hold the line.
     */
    private static final int PLANTABLE_COLUMNS = 3;
    /** A zombie closer than this to the house counts as an emergency. */
    private static final float DANGER_CELL_X = 4F;

    /**
     * Which plant to build, by role.
     *
     * <p>The three ids used to be literals in the middle of the decision, so a data
     * pack that renamed or rebalanced a plant left the AI referencing a plant that
     * might not exist (and silently playing nothing). Each role is now an env var
     * with a default, which also gives a level author a way to steer the AI without
     * touching code.
     */
    private Identifier rolePlant(LevelServer level, String envPath, String fallbackPath) {
        return level.envVars().get(PvzceIds.id("identifier"),
                Identifier.withDefaultNamespace(envPath),
                Identifier.withDefaultNamespace(fallbackPath));
    }

    private PlantDef choose(LevelServer level, Team team) {
        boolean danger = level.entities().stream()
                .anyMatch(e -> e instanceof ZombieEntity z && !z.isRemoved() && z.cellX() < DANGER_CELL_X);
        int sun = team.resourcesOf(SUN);
        Identifier preferred;
        if (danger && sun >= 100) {
            preferred = rolePlant(level, "ai_defense_plant", "wall_nut");
        } else if (sun < 300) {
            preferred = rolePlant(level, "ai_economy_plant", "sunflower");
        } else {
            preferred = rolePlant(level, "ai_offense_plant", "pea_shooter");
        }
        PlantDef def = BuiltInRegistries.PLANTS.get(preferred);
        if (def == null) {
            // A level may point a role at a plant its pack does not provide; fall back
            // to the built-in choice rather than idling forever.
            def = BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace("pea_shooter"));
        }
        return def != null && def.cost().amountOf(SUN) <= sun ? def : null;
    }

    /** A cell the chosen plant may legally occupy; uses the level's own placement rule. */
    private int[] findFreeCell(LevelServer level, PlantDef chosen) {
        List<int[]> cells = new ArrayList<>();
        String feet = chosen.placement().feet();
        for (int x = 1; x <= PLANTABLE_COLUMNS && x < level.width(); x++) {
            for (int y = 0; y < level.height(); y++) {
                if (!level.canPlacePlant(chosen, x, y)) {
                    continue;
                }
                if (!PvzceIds.FEET_PLANT.equals(feet) && level.hasPlantWithFeet(x, y, feet)) {
                    continue;
                }
                cells.add(new int[]{x, y});
            }
        }
        if (cells.isEmpty()) {
            return null;
        }
        return cells.get(level.random().nextInt(cells.size()));
    }
}
