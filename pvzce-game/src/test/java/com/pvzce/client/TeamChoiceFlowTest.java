package com.pvzce.client;

import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelPayload;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether the entry flow asks which side, and — the reason this exists — whether it ever stops.
 *
 * <p>关卡准备 forwards the player's answer back into the entry flow, so "ask" and "the player just
 * answered" arrive at the same method. The first version of the team choice asked again, and
 * 开始游戏 re-opened the page it was clicked on: a soft-lock that no server-side test could see,
 * because the server was never reached at all.
 */
class TeamChoiceFlowTest {
    private static LevelListS2C.LevelInfo level(String... teamIds) {
        List<LevelListS2C.TeamInfo> teams = java.util.Arrays.stream(teamIds)
                .map(teamId -> new LevelListS2C.TeamInfo(teamId, teamId, "survive_waves"))
                .toList();
        return LevelListS2C.LevelInfo.of("pvzce:yard/versus/duel_1", "对战", "", "pvzce:plant_team",
                teams, "", "", "pvzce:yard", "pvzce:versus", false,
                LevelPayload.EMPTY, LevelListS2C.UnlockInfo.OPEN);
    }

    @Test
    void aLevelWithTwoSidesAsksUntilThePlayerAnswers() {
        LevelListS2C.LevelInfo both = level("pvzce:plant_team", "pvzce:zombie_team");
        assertTrue(PvzceClient.asksForTeamChoice(both, ""),
                "nobody has answered yet, so the screen is the question");
        assertTrue(PvzceClient.asksForTeamChoice(both, null));
        assertFalse(PvzceClient.asksForTeamChoice(both, "pvzce:plant_team"),
                "an answer is not asked for again: this is the loop 开始游戏 used to fall into");
        assertFalse(PvzceClient.asksForTeamChoice(both, "pvzce:zombie_team"));
    }

    @Test
    void aLevelWithOneSideNeverAsks() {
        LevelListS2C.LevelInfo one = level("pvzce:plant_team");
        assertFalse(PvzceClient.asksForTeamChoice(one, ""));
        assertFalse(PvzceClient.asksForTeamChoice(one, "pvzce:plant_team"));
    }
}
