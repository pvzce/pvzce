package com.pvzce.common.jev;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * One "what should I do next" turn: the board as Jev is told it, and the three choices it is
 * asked for.
 *
 * <p>The whole shape of a versus level's opponent is in this record. Every few seconds the
 * server builds one of these from the live level - the sun it holds, the cards it may still
 * play, what is standing in every row - and asks Jev three questions at once: <b>which
 * card</b> (with {@code hold} as a real option, so saving sun is a decision rather than a
 * failure), <b>which row</b>, and <b>which column</b>. Jev evaluates all three against the
 * same state in parallel and returns one typed answer each.
 *
 * <p>Three questions rather than one "which cell" question because a cell is not a
 * category: Jev's {@code choice} answers pick from a declared key set, and 45 anonymous
 * cells would be a bad set. Splitting the cell into row and column also lets the row
 * descriptions carry what is actually in each lane, which is the context the decision
 * needs.
 *
 * <p>This type is pure data and JSON. It does not know about levels, teams or entities -
 * {@code JevBrain} builds it from those - so the request shape can be unit tested without a
 * server, and so a future caller (a test harness, a different game mode) can ask the same
 * model the same kind of question.
 *
 * @param side          which side is asking, which is also what the state says
 * @param objective     one line describing what this side is trying to achieve
 * @param sun           the sun this side holds right now
 * @param goalTarget    the sun goal it is racing towards, or
 *                      {@link #NO_GOAL} when its only goal is to break through
 * @param goalCollected how much of that goal is already banked
 * @param elapsedSeconds how long this run has been going, for the model's sense of pace
 * @param cards         the cards it may play this turn, affordable ones flagged
 * @param rows          one option per lane, with that lane's contents described
 * @param columns       one option per cell column this side may legally use
 */
public record JevPrompt(
        Side side,
        String objective,
        int sun,
        int goalTarget,
        int goalCollected,
        int elapsedSeconds,
        List<CardOption> cards,
        List<RowOption> rows,
        List<ColumnOption> columns,
        String directive,
        String commanderCard) {

    /** {@link #directive} for a match without a commander: no plan, not an empty one. */
    public static final String NO_DIRECTIVE = "";

    /** {@link #commanderCard} for a plan that says "wait", or for no plan at all. */
    public static final String NO_CARD = "";

    /** The same prompt for a match with no commander: no plan rather than an empty one. */
    public JevPrompt(Side side, String objective, int sun, int goalTarget, int goalCollected,
                     int elapsedSeconds, List<CardOption> cards, List<RowOption> rows,
                     List<ColumnOption> columns) {
        this(side, objective, sun, goalTarget, goalCollected, elapsedSeconds, cards, rows, columns,
                NO_DIRECTIVE, NO_CARD);
    }

    /** A prompt whose strategist asked for one particular card. */
    public JevPrompt(Side side, String objective, int sun, int goalTarget, int goalCollected,
                     int elapsedSeconds, List<CardOption> cards, List<RowOption> rows,
                     List<ColumnOption> columns, String directive) {
        this(side, objective, sun, goalTarget, goalCollected, elapsedSeconds, cards, rows, columns,
                directive, NO_CARD);
    }

    /** {@link #goalTarget} for a side whose win condition is not a sun total. */
    public static final int NO_GOAL = -1;

    /** The criteria key that means "play nothing this turn". */
    public static final String KEY_HOLD = "hold";

    public static final String KEY_ACTION = "action";
    public static final String KEY_ROW = "row";
    public static final String KEY_COLUMN = "column";

    /** Which side of the lawn is being asked; the state text and the questions both say it. */
    public enum Side {
        PLANT,
        ZOMBIE;

        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * One card the side could play, and whether it can afford it.
     *
     * <p>Unaffordable cards stay in the list on purpose. "I cannot afford the gargantuar
     * yet" is information the model should have when it decides whether to hold, and hiding
     * the card would leave it choosing between what it can buy without knowing what it is
     * saving for.
     */
    public record CardOption(String id, int cost, boolean affordable, String description,
                             boolean ready, int cooldownSeconds) {
        /**
         * A card with nothing to say about recharge: ready, which is what a prompt built outside a
         * running level (the protocol tests) means by a card.
         */
        public CardOption(String id, int cost, boolean affordable, String description) {
            this(id, cost, affordable, description, true, 0);
        }

    }

    /**
     * One place the answer may name: a row or a column.
     *
     * <p>Shared so {@link JevDecision} can resolve "row_3" and "col_3" with one piece of code -
     * the two questions differ in what their descriptions say, not in how an index is read back.
     */
    public interface CellOption {
        int index();

        /** The criteria key: {@code row_3} or {@code col_3}. */
        String key();
    }

    /** One lane: its index, and a description of what is standing in it. */
    public record RowOption(int index, String description) implements CellOption {
        @Override
        public String key() {
            return "row_" + index;
        }
    }

    /** One column this side may legally place in, and what that column means tactically. */
    public record ColumnOption(int index, String description) implements CellOption {
        @Override
        public String key() {
            return "col_" + index;
        }
    }

    public JevPrompt {
        cards = List.copyOf(cards);
        rows = List.copyOf(rows);
        columns = List.copyOf(columns);
        objective = objective == null ? "" : objective;
        directive = directive == null ? NO_DIRECTIVE : directive.trim();
        commanderCard = commanderCard == null ? NO_CARD : commanderCard.trim();
    }

    /** The criteria keys the {@code action} question offers, {@code hold} included. */
    public List<String> allowedActions() {
        List<String> keys = new java.util.ArrayList<>();
        for (CardOption card : cards) {
            keys.add(card.id());
        }
        keys.add(KEY_HOLD);
        return List.copyOf(keys);
    }

    /**
     * The request body for one provider.
     *
     * <p>All three providers take the same body - a model name, a state, and a map of typed
     * questions - and differ only in the URL and in how they wrap the answers; the probe that
     * pinned this down is recorded in {@code docs/架构变更记录.md}.
     */
    public JsonObject requestBody(String model) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.add("state", state());
        root.add("questions", questions());
        return root;
    }

    /** The compact state block: never the whole board, only what the decision depends on. */
    private JsonObject state() {
        JsonObject state = new JsonObject();
        state.addProperty("role", side.wireName());
        state.addProperty("objective", objective);
        state.addProperty("sun_available", sun);
        state.addProperty("seconds_elapsed", elapsedSeconds);
        if (!directive.isEmpty()) {
            // The strategist's standing order, as its own state field rather than folded into the
            // objective: an order and a win condition are different things, and a model that can tell
            // them apart can follow one while still reporting the other.
            state.addProperty("commander_plan", directive);
            if (!commanderCard.isEmpty()) {
                // Its own field so the tactical model can weigh "the strategist wants this card"
                // without parsing Chinese prose for a card id.
                state.addProperty("commander_card", commanderCard);
            }
        }
        if (goalTarget > 0) {
            JsonObject goal = new JsonObject();
            goal.addProperty("sun_target", goalTarget);
            goal.addProperty("sun_collected", goalCollected);
            goal.addProperty("sun_remaining", Math.max(0, goalTarget - goalCollected));
            state.add("goal", goal);
        }
        JsonArray hand = new JsonArray();
        for (CardOption card : cards) {
            JsonObject option = new JsonObject();
            option.addProperty("card", card.id());
            option.addProperty("cost", card.cost());
            option.addProperty("affordable_now", card.affordable());
            // Said out loud, because a card that is recharging is not a card this turn: without it the
            // model keeps asking for the one it just played and has the move refused.
            option.addProperty("ready", card.ready());
            option.addProperty("cooldown_seconds", card.cooldownSeconds());
            hand.add(option);
        }
        state.add("your_cards", hand);
        JsonArray board = new JsonArray();
        for (RowOption row : rows) {
            board.add(row.description());
        }
        state.add("board_rows", board);
        // The two axes in one sentence each, because "row" and "column" are exactly the pair a model
        // mixes up, and every other line of this state assumes the reader has them: a row is a lane
        // (row_0 is the top one), a column is an x position (col_0 is the house end, larger columns
        // toward the entrance the zombies walk in from).
        state.addProperty("board_axes", "row_N is a lane, row_0 at the top; col_N is an x position, "
                + "col_0 against the house, larger columns toward the zombies' entrance");
        JsonArray legalColumns = new JsonArray();
        for (ColumnOption column : columns) {
            legalColumns.add(column.index());
        }
        state.add("legal_columns", legalColumns);
        return state;
    }

    private JsonObject questions() {
        JsonObject questions = new JsonObject();

        JsonObject criteria = new JsonObject();
        for (CardOption card : cards) {
            criteria.addProperty(card.id(), card.description());
        }
        criteria.addProperty(KEY_HOLD,
                "Play nothing this turn and bank the sun for something better.");
        if (!directive.isEmpty()) {
            // Said as an instruction, not as data: the plan is advice from a slower model that saw an
            // older board, so the tactical model is told to follow it *unless* the board in front of it
            // says otherwise. Obeying a stale order into a lost lane is worse than ignoring it.
            criteria.addProperty(KEY_ACTION + "_plan_note",
                    (commanderCard.isEmpty() ? "" : "你的指挥官点名要 " + commanderCard + "；")
                            + "你的指挥官给出的方向是：" + directive
                            + " —— 优先按它执行；如果眼前的局面与它明显冲突（那一条车道即将失守、"
                            + "手里的阳光买不起它说的卡），按局面办，不要为了服从它而放弃一条车道。");
        }
        questions.add(KEY_ACTION, question("choice",
                "Which single card should " + side.wireName() + " play this turn, and where? "
                        + "Cards you cannot afford yet are listed as well: if the card you actually "
                        + "want is one payment away, choose hold and buy it next turn instead of "
                        + "spending the same sun on a weaker card now. A cheap card every turn is "
                        + "not better than a strong card every other turn.", criteria));

        JsonObject rowCriteria = new JsonObject();
        for (RowOption row : rows) {
            rowCriteria.addProperty(row.key(), row.description());
        }
        questions.add(KEY_ROW, question("choice",
                "Which row should the card go in?", rowCriteria));

        JsonObject columnCriteria = new JsonObject();
        for (ColumnOption column : columns) {
            columnCriteria.addProperty(column.key(), column.description());
        }
        questions.add(KEY_COLUMN, question("choice",
                "Which column should the card go in? A column is an x position, so a zombie placed in "
                        + "a larger column has more open lawn to walk under fire before it reaches "
                        + "anything - the criteria say how much for each. Only these are legal for "
                        + side.wireName() + ".", columnCriteria));
        return questions;
    }

    private static JsonObject question(String type, String instructions, JsonObject criteria) {
        JsonObject question = new JsonObject();
        question.addProperty("type", type);
        question.addProperty("instructions", instructions);
        question.add("criteria", criteria);
        return question;
    }
}
