package com.pvzce.common.jev;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Jev request/answer contract, pinned against real responses.
 *
 * <p>The two bodies in {@link #openRouterResponse()} and {@link #communityResponse()} were
 * captured from the live endpoint while this was built (the key is not in them). They are the
 * reason this test exists in this shape: the envelope differs per provider, the model answers
 * exactly what it was asked, and a game that parses one of those shapes and not the other
 * would work on one player's setup and silently hold every turn on the next.
 */
class JevProtocolTest {

    private static JevPrompt zombiePrompt() {
        return new JevPrompt(
                JevPrompt.Side.ZOMBIE,
                "Break through the plants before they collect 5000 sun.",
                150,
                JevPrompt.NO_GOAL,
                0,
                42,
                List.of(
                        new JevPrompt.CardOption("pvzce:basic_zombie", 50, true, "basic, 50 sun"),
                        new JevPrompt.CardOption("pvzce:conehead_zombie", 75, false, "tougher, 75 sun")),
                List.of(
                        new JevPrompt.RowOption(0, "row_0: peashooter at x1"),
                        new JevPrompt.RowOption(1, "row_1: empty")),
                List.of(
                        new JevPrompt.ColumnOption(5, "col_5: closest to the house"),
                        new JevPrompt.ColumnOption(6, "col_6: one back")));
    }

    /** The verbatim body OpenRouter returned for a two-question prompt. */
    private static String openRouterResponse() {
        return "{\"model\":\"typesafe/jev-1.13-20260917\",\"answers\":{"
                + "\"action\":{\"type\":\"choice\",\"choice\":\"basic_zombie\","
                + "\"probabilities\":{\"basic_zombie\":0.65,\"hold\":0.1,\"conehead_zombie\":0.25},"
                + "\"confidence\":0.48},"
                + "\"row\":{\"type\":\"choice\",\"choice\":\"0\","
                + "\"probabilities\":{\"row_0\":0.71,\"row_1\":0.29},\"confidence\":0.42},"
                + "\"column\":{\"type\":\"choice\",\"choice\":\"col_6\","
                + "\"probabilities\":{\"col_5\":0.57,\"col_6\":0.43},\"confidence\":0.13}},"
                + "\"usage\":{\"input_tokens\":525,\"output_tokens\":76},"
                + "\"id\":\"gen-dec-1\",\"provider\":\"TypeSafe\"}";
    }

    /** The envelope the community site documents: answers live under {@code data}. */
    private static String communityResponse() {
        return "{\"code\":0,\"message\":\"ok\",\"data\":{\"answers\":{"
                + "\"action\":{\"type\":\"choice\",\"choice\":\"pvzce:basic_zombie\","
                + "\"confidence\":0.61},"
                + "\"row\":{\"type\":\"choice\",\"choice\":\"row_1\"},"
                + "\"column\":{\"type\":\"choice\",\"choice\":\"6\"}}}}";
    }

    @Test
    void theRequestBodyCarriesTheStateAndThreeQuestions() {
        JsonObject body = zombiePrompt().requestBody("typesafe/jev-1.13");
        assertEquals("typesafe/jev-1.13", body.get("model").getAsString());

        JsonObject state = body.getAsJsonObject("state");
        assertEquals("zombie", state.get("role").getAsString());
        assertEquals(150, state.get("sun_available").getAsInt());
        assertEquals(42, state.get("seconds_elapsed").getAsInt());
        // No sun goal for this side, so no goal block to confuse the model with a race it is not in.
        assertFalse(state.has("goal"));
        assertEquals(2, state.getAsJsonArray("your_cards").size());
        assertFalse(state.getAsJsonArray("your_cards").get(1).getAsJsonObject()
                .get("affordable_now").getAsBoolean());
        assertEquals(2, state.getAsJsonArray("legal_columns").size());

        JsonObject questions = body.getAsJsonObject("questions");
        assertEquals(3, questions.size());
        JsonObject actions = questions.getAsJsonObject("action").getAsJsonObject("criteria");
        assertTrue(actions.has("pvzce:basic_zombie"));
        assertTrue(actions.has(JevPrompt.KEY_HOLD), "holding must be an offered option, not a failure");
        assertEquals(2, questions.getAsJsonObject("row").getAsJsonObject("criteria").size());
        assertEquals(2, questions.getAsJsonObject("column").getAsJsonObject("criteria").size());
    }

    @Test
    void aPlantSidePromptCarriesItsSunGoal() {
        JevPrompt prompt = new JevPrompt(JevPrompt.Side.PLANT, "Collect 5000 sun.",
                300, 5000, 1200, 600,
                List.of(new JevPrompt.CardOption("pvzce:sunflower", 50, true, "sun producer")),
                List.of(new JevPrompt.RowOption(0, "row_0: empty")),
                List.of(new JevPrompt.ColumnOption(0, "col_0: behind everything")));
        JsonObject goal = prompt.requestBody("m").getAsJsonObject("state").getAsJsonObject("goal");
        assertEquals(5000, goal.get("sun_target").getAsInt());
        assertEquals(1200, goal.get("sun_collected").getAsInt());
        assertEquals(3800, goal.get("sun_remaining").getAsInt());
    }

    @Test
    void readsTheTopLevelEnvelopeAndResolvesKeysLoosely() {
        Optional<JevDecision> parsed = JevDecision.parse(openRouterResponse(), zombiePrompt());
        assertTrue(parsed.isPresent());
        JevDecision decision = parsed.get();
        // "basic_zombie" was answered without the namespace and matched back to pvzce:basic_zombie.
        assertEquals("pvzce:basic_zombie", decision.cardId());
        assertFalse(decision.isHold());
        assertEquals(0, decision.row());
        assertEquals(6, decision.column());
        assertEquals(0.48D, decision.confidence(), 1e-9D);
        assertEquals("typesafe/jev-1.13-20260917", decision.model());
    }

    @Test
    void readsTheNestedCommunityEnvelope() {
        Optional<JevDecision> parsed = JevDecision.parse(communityResponse(), zombiePrompt());
        assertTrue(parsed.isPresent());
        assertEquals("pvzce:basic_zombie", parsed.get().cardId());
        assertEquals(1, parsed.get().row());
        assertEquals(6, parsed.get().column());
    }

    @Test
    void aResponseThatIsItsOwnAnswerMapIsReadToo() {
        String body = "{\"action\":{\"choice\":\"pvzce:basic_zombie\"},"
                + "\"row\":{\"choice\":\"row_0\"},\"column\":{\"choice\":\"col_5\"}}";
        Optional<JevDecision> parsed = JevDecision.parse(body, zombiePrompt());
        assertTrue(parsed.isPresent());
        assertEquals(0, parsed.get().row());
        assertEquals(5, parsed.get().column());
    }

    @Test
    void holdIsAHoldWhicheverWordTheModelUses() {
        for (String word : List.of("hold", "wait", "none", "skip", "do nothing")) {
            String body = "{\"answers\":{\"action\":{\"choice\":\"" + word + "\"},"
                    + "\"row\":{\"choice\":\"row_0\"},\"column\":{\"choice\":\"col_5\"}}}";
            Optional<JevDecision> parsed = JevDecision.parse(body, zombiePrompt());
            assertTrue(parsed.isPresent(), word);
            assertTrue(parsed.get().isHold(), word);
            assertEquals(-1, parsed.get().row(), word);
        }
    }

    @Test
    void anAnswerOutsideTheOfferedKeysIsRefusedRatherThanRepaired() {
        String crown = "{\"answers\":{\"action\":{\"choice\":\"pvzce:gargantuar\"},"
                + "\"row\":{\"choice\":\"row_0\"},\"column\":{\"choice\":\"col_5\"}}}";
        assertTrue(JevDecision.parse(crown, zombiePrompt()).isEmpty(),
                "a card that was never in hand is not a decision this level can execute");

        String offBoard = "{\"answers\":{\"action\":{\"choice\":\"pvzce:basic_zombie\"},"
                + "\"row\":{\"choice\":\"row_9\"},\"column\":{\"choice\":\"col_5\"}}}";
        assertTrue(JevDecision.parse(offBoard, zombiePrompt()).isEmpty());

        String leftOfTheLine = "{\"answers\":{\"action\":{\"choice\":\"pvzce:basic_zombie\"},"
                + "\"row\":{\"choice\":\"row_0\"},\"column\":{\"choice\":\"col_2\"}}}";
        assertTrue(JevDecision.parse(leftOfTheLine, zombiePrompt()).isEmpty(),
                "a column outside the legal set is refused, which is what keeps the placement zone honest");
    }

    @Test
    void garbageAndErrorEnvelopesAreRefused() {
        assertTrue(JevDecision.parse("", zombiePrompt()).isEmpty());
        assertTrue(JevDecision.parse("not json at all", zombiePrompt()).isEmpty());
        assertTrue(JevDecision.parse("{\"code\":401,\"message\":\"no auth\"}", zombiePrompt()).isEmpty());
        assertTrue(JevDecision.parse("{\"answers\":{}}", zombiePrompt()).isEmpty());
        // A hold with no row/column is still a decision; a card with no cell is not.
        assertTrue(JevDecision.parse("{\"answers\":{\"action\":{\"choice\":\"pvzce:basic_zombie\"}}}",
                zombiePrompt()).isEmpty());
    }

    @Test
    void settingsRefuseToBeARequestWithoutAUrlAndAKey() {
        assertFalse(JevSettings.NONE.configured());
        assertFalse(new JevSettings("https://example.test/v1/systemone", "", "").configured());
        assertTrue(new JevSettings("https://example.test/v1/systemone", "", "k").configured());
        // A blank model falls back to the default rather than making the setting unusable.
        assertEquals(JevSettings.DEFAULT_MODEL,
                new JevSettings("u", "  ", "k").model());
        assertNotNull(JevSettings.NONE.redacted().toString());
        assertFalse(JevSettings.NONE.redacted().toString().contains("null"));
    }
}
