package com.pvzce.common.jev;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Jev's answer to one {@link JevPrompt}: a card and a cell, or a decision to hold.
 *
 * <p>The parse is deliberately suspicious. The model is asked for a criteria key and returns
 * typed JSON, but the JSON arrives wrapped differently by each provider and the model itself
 * is free to answer approximately, so nothing here trusts a field name or a string:
 *
 * <ul>
 *   <li>the answer map is looked for under {@code data.answers}, then {@code answers}, and
 *       finally as the response root itself (that is the three shapes the three providers
 *       were observed to use);</li>
 *   <li>an answer's selected key is read from {@code choice}, or from {@code answer} for a
 *       provider that names it that way;</li>
 *   <li>a key is matched exactly, then case-insensitively, then by its path after the
 *       namespace ({@code basic_zombie} for {@code pvzce:basic_zombie});</li>
 *   <li>{@code row_3} and {@code 3} are the same row, and the same for columns;</li>
 *   <li>an answer that names something the prompt did not offer is <b>not</b> repaired into
 *       something close - it is refused, and the caller falls back to its own policy. A
 *       model that says "gargantuar" when no gargantuar card is in hand has not made a
 *       decision this game can execute.</li>
 * </ul>
 *
 * <p>Whatever the outcome, the caller re-checks it against the simulation before anything is
 * spawned: this type answers "what did Jev say", not "is it allowed".
 *
 * @param actionKey    the criteria key Jev picked: a card id, or {@link JevPrompt#KEY_HOLD}
 * @param row          the lane it picked, or {@code -1} for a hold
 * @param column       the column it picked, or {@code -1} for a hold
 * @param confidence   the answer's own confidence, or {@code -1} when it did not report one
 * @param model        the model the provider actually served, for the log
 */
public record JevDecision(String actionKey, int row, int column, double confidence, String model) {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("PVZCE/Jev");

    /** How the model might spell "do nothing" when asked for a criteria key. */
    private static final Set<String> HOLD_WORDS = Set.of(
            "hold", "wait", "wait_turn", "none", "nothing", "skip", "pass", "do_nothing",
            "save", "save_sun", "no_action");

    public boolean isHold() {
        return JevPrompt.KEY_HOLD.equals(actionKey);
    }

    /** The card to play, or {@code null} when the answer was to hold. */
    public String cardId() {
        return isHold() ? null : actionKey;
    }

    /**
     * Reads one response body, or answers empty when it holds nothing this level can execute.
     *
     * @param body   the raw response the provider returned
     * @param prompt the prompt that was sent, which is the only definition of the legal keys
     */
    public static Optional<JevDecision> parse(String body, JevPrompt prompt) {
        if (body == null || body.isBlank() || prompt == null) {
            return Optional.empty();
        }
        JsonObject root = parseObject(body);
        if (root == null) {
            LOGGER.debug("Jev answered with something that is not a JSON object");
            return Optional.empty();
        }
        JsonObject answers = findAnswers(root);
        if (answers == null) {
            LOGGER.debug("Jev answered without an answers block: {}", abbreviate(body));
            return Optional.empty();
        }
        JsonObject action = answerObject(answers, JevPrompt.KEY_ACTION);
        if (action == null) {
            LOGGER.debug("Jev answered without an action for the {} question", JevPrompt.KEY_ACTION);
            return Optional.empty();
        }
        String chosen = selectedKey(action);
        if (chosen == null) {
            return Optional.empty();
        }
        String key = resolveAction(chosen, prompt);
        if (key == null) {
            LOGGER.debug("Jev chose '{}', which is not one of {}", chosen, prompt.allowedActions());
            return Optional.empty();
        }
        double confidence = number(action, "confidence");
        String model = string(root, "model");
        if (JevPrompt.KEY_HOLD.equals(key)) {
            return Optional.of(new JevDecision(key, -1, -1, confidence, model));
        }
        Integer row = resolveIndex(selectedKey(answerObject(answers, JevPrompt.KEY_ROW)), prompt.rows());
        Integer column = resolveIndex(selectedKey(answerObject(answers, JevPrompt.KEY_COLUMN)),
                prompt.columns());
        if (row == null || column == null) {
            LOGGER.debug("Jev chose a card but not a cell (row={}, column={})", row, column);
            return Optional.empty();
        }
        return Optional.of(new JevDecision(key, row, column, confidence, model));
    }

    private static JsonObject parseObject(String body) {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The three shapes the providers wrap their answers in, in the order they are tried.
     *
     * <p>The community site answers {@code {code, message, data:{answers:{...}}}}, the
     * aggregating gateways answer {@code {model, answers:{...}}}, and a provider that follows
     * the request shape for the reply as well puts the questions at the root. The third case is
     * only accepted when the root really does carry our question, so an error object that
     * happens to mention "action" is not read as a decision.
     */
    private static JsonObject findAnswers(JsonObject root) {
        JsonElement data = root.get("data");
        if (data != null && data.isJsonObject()) {
            JsonElement nested = data.getAsJsonObject().get("answers");
            if (nested != null && nested.isJsonObject()) {
                return nested.getAsJsonObject();
            }
        }
        JsonElement answers = root.get("answers");
        if (answers != null && answers.isJsonObject()) {
            return answers.getAsJsonObject();
        }
        if (root.has(JevPrompt.KEY_ACTION) && root.has(JevPrompt.KEY_ROW)) {
            return root;
        }
        return null;
    }

    private static JsonObject answerObject(JsonObject answers, String question) {
        JsonElement element = answers.get(question);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /** The key this answer selected: {@code choice}, or {@code answer} on a provider that uses it. */
    private static String selectedKey(JsonObject answer) {
        if (answer == null) {
            return null;
        }
        String choice = string(answer, "choice");
        return choice != null ? choice : string(answer, "answer");
    }

    /** Exact key, then case-insensitive, then the path after the namespace, then a hold synonym. */
    private static String resolveAction(String chosen, JevPrompt prompt) {
        String trimmed = chosen.trim();
        List<String> allowed = prompt.allowedActions();
        for (String candidate : allowed) {
            if (candidate.equals(trimmed)) {
                return candidate;
            }
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String candidate : allowed) {
            if (candidate.toLowerCase(Locale.ROOT).equals(lower)) {
                return candidate;
            }
        }
        String path = trimmed.contains(":") ? trimmed.substring(trimmed.indexOf(':') + 1) : trimmed;
        for (String candidate : allowed) {
            String candidatePath = candidate.contains(":")
                    ? candidate.substring(candidate.indexOf(':') + 1) : candidate;
            if (candidatePath.equalsIgnoreCase(path)) {
                return candidate;
            }
        }
        if (HOLD_WORDS.contains(lower) || HOLD_WORDS.contains(lower.replace(' ', '_'))) {
            return JevPrompt.KEY_HOLD;
        }
        return null;
    }

    /** {@code row_3}, {@code 3} and {@code column 3} are one answer; anything off the board is none. */
    private static Integer resolveIndex(String chosen, List<? extends JevPrompt.CellOption> options) {
        if (chosen == null) {
            return null;
        }
        String trimmed = chosen.trim();
        for (JevPrompt.CellOption option : options) {
            if (trimmed.equalsIgnoreCase(option.key())
                    || trimmed.equals(String.valueOf(option.index()))) {
                return option.index();
            }
        }
        Integer parsed = trailingNumber(trimmed);
        if (parsed != null) {
            for (JevPrompt.CellOption option : options) {
                if (option.index() == parsed) {
                    return parsed;
                }
            }
        }
        return null;
    }

    /** The last integer in a string: {@code "col_7"} and {@code "column 7"} both mean seven. */
    private static Integer trailingNumber(String text) {
        int end = text.length();
        while (end > 0 && !Character.isDigit(text.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && Character.isDigit(text.charAt(start - 1))) {
            start--;
        }
        if (start == end) {
            return null;
        }
        try {
            return Integer.parseInt(text.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String string(JsonObject object, String field) {
        if (object == null) {
            return null;
        }
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static double number(JsonObject object, String field) {
        if (object == null) {
            return -1D;
        }
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()) {
            return -1D;
        }
        try {
            return element.getAsDouble();
        } catch (RuntimeException e) {
            return -1D;
        }
    }

    private static String abbreviate(String body) {
        String flat = body.replace('\n', ' ');
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
    }

    @Override
    public String toString() {
        return isHold()
                ? "JevDecision[hold, confidence=" + confidence + "]"
                : "JevDecision[" + actionKey + " at " + row + "," + column
                        + ", confidence=" + confidence + "]";
    }
}
