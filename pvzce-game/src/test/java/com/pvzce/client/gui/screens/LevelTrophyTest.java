package com.pvzce.client.gui.screens;

import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelPayload;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trophy on a level list row: a beaten level of a trophy category, and nothing else.
 *
 * <p>The row it must not lose is the one that reads 进行中 because a replay was abandoned -
 * the medal is earned once, and the status label is about the save, not about the medal. That
 * is why the check reads {@code cleared} rather than the label, and why it is pinned here
 * rather than left to a screenshot.
 */
class LevelTrophyTest {
    @BeforeAll
    static void load() throws Exception {
        com.pvzce.common.tag.TestContent.loadBuiltInContentAndTags();
    }

    private static LevelListS2C.LevelInfo row(String category, String status, boolean runningSave,
                                              boolean cleared) {
        return LevelListS2C.LevelInfo.of("pvzce:yard/" + category + "/test", "测试", "", "pvzce:plant_team",
                List.of(), status, "day", "pvzce:yard", "pvzce:" + category, runningSave, cleared,
                new LevelPayload(9, 5, List.of(), 0, List.of(), List.of(), List.of(), List.of()),
                LevelListS2C.UnlockInfo.OPEN);
    }

    @Test
    void aBeatenMiniGameKeepsItsTrophyWhileAReplayIsInProgress() {
        assertTrue(LevelRowRenderer.showsTrophy(row("minigame", LevelListS2C.LevelInfo.COMPLETED,
                false, true)), "beaten and nothing running: the medal is on the row");
        assertTrue(LevelRowRenderer.showsTrophy(row("minigame", LevelListS2C.LevelInfo.IN_PROGRESS,
                        true, true)),
                "an abandoned replay reads 进行中, and the level was still beaten");
    }

    @Test
    void anUnbeatenLevelAndAnOrdinaryCategoryHaveNoTrophy() {
        assertFalse(LevelRowRenderer.showsTrophy(row("minigame", LevelListS2C.LevelInfo.IN_PROGRESS,
                        true, false)),
                "a mini-game that has never been finished has earned nothing");
        assertFalse(LevelRowRenderer.showsTrophy(row("minigame", "", false, false)));
        assertFalse(LevelRowRenderer.showsTrophy(row("adventure", LevelListS2C.LevelInfo.COMPLETED,
                        false, true)),
                "an adventure level is progress, not a medal - the category decides");
        assertFalse(LevelRowRenderer.showsTrophy(row("nosuchcategory", LevelListS2C.LevelInfo.COMPLETED,
                        false, true)),
                "an unknown category answers no rather than throwing");
    }
}
