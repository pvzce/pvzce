package com.pvzce.server.ai;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.VersusData;
import com.pvzce.api.content.ZombieDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.Slot;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.jev.JevDecision;
import com.pvzce.common.jev.JevPrompt;
import com.pvzce.common.jev.AiSettings;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.server.Team;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.PvzceEntity;
import com.pvzce.server.entity.ResourceDropEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The opponent of a versus level: what Jev is asked, when, and what happens to the answer.
 *
 * <p>One brain serves either side, because the two sides differ in what they are asked and in
 * which spawn call answers it - not in the machinery around them. Which side it drives is read
 * from the level every tick ({@code humanTeamId} is the player, so the opponent is the other team),
 * which also means a level whose player picks the other side needs no second code path.
 *
 * <p><b>Every answer is a suggestion until this class re-checks it.</b> Jev is asked which card and
 * which cell, and the answer is then put through the same questions the simulation would ask of a
 * player's packet: is that card in my deck, is it the right kind for my side, can I afford it, is
 * the cell inside my placement zone, is the cell free. A decision that fails any of them is
 * dropped and the built-in policy plays that turn instead - which is also what happens when there
 * is no key, when the request times out, and when the provider answers with something that is not
 * a decision at all.
 *
 * <p><b>The built-in policy is a policy, not a stub.</b> It has to be good enough to lose a
 * fifteen-minute match respectably, because that is what a player without an API key gets: the
 * cheapest card it can afford, in the lane with the most pressure (for plants) or the least
 * (for zombies), at the back of its own zone unless a lane is about to be lost. It is deliberately
 * simple - a fallback that is clever would be a second opponent to tune and to explain.
 *
 * <p><b>The opponent also collects.</b> A plant side that is driven by an AI has nobody to click
 * its sun, and its whole win condition is collecting it, so the brain picks up its own team's
 * landed drops. Only its own team's, and only once they have landed: a sun is collected where the
 * player would have collected it, and the human's own sun is left alone for the human.
 */
public final class JevBrain {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("PVZCE/Jev");

    /** A retry this soon after being refused by an in-flight request, rather than a whole interval. */
    private static final int BUSY_RETRY_TICKS = 20;
    /**
     * How often the built-in policy may act: twice a second.
     *
     * <p>A player clicks as fast as their sun allows, and the policy is a stand-in for one. The
     * three-second cadence belongs to the network half (asking Jev), not to the half that is a few
     * comparisons - and measuring this was the difference between an opponent that holds a lawn and
     * one that is overrun while it thinks.
     */
    private static final int FALLBACK_INTERVAL_TICKS = 30;
    /** How close a zombie has to be to the house before the fallback spends its best card. */
    private static final float DANGER_CELL_X = 2F;

    /**
     * What the HUD says about the opponent.
     *
     * <p>{@code BUILTIN} and {@code FALLBACK} are different facts and the player deserves both:
     * the first is "no key is configured, this is the game's own opponent", the second is "Jev was
     * asked and could not answer, this move is the game's own". The two look identical on the board
     * and completely different in a bug report.
     */
    public enum Status {
        BUILTIN,
        JEV,
        FALLBACK
    }

    private final JevClient client = new JevClient();
    /**
     * The strategist tier, built the first time a level actually has one configured.
     *
     * <p>Lazy so that the ordinary case - a player who never opened the AI settings page - pays for
     * neither the thread nor the HTTP client.
     */
    private CommanderClient commander;
    /** The standing plan the strategist last wrote, or empty; it rides in every Jev prompt. */
    private String directive = "";
    /**
     * The card the plan's first line named, as the hand spells it, or empty for "wait".
     *
     * <p>Separate from the prose because it is the one part of an answer that can be checked, and
     * because the tactical model should be able to see "the strategist's first choice is this card"
     * as a fact rather than as a word inside a sentence.
     */
    private String commanderCard = "";
    /** Ticks until the strategist is asked again; see {@link #COMMANDER_INTERVAL_TICKS}. */
    private int commanderCountdown;
    /** True while a strategy request is out, for the debug overlay and for the snapshot's wording. */
    private boolean commanderBusy;
    private Status status = Status.BUILTIN;
    private boolean dirty = true;
    private boolean fallbackReported;
    private int decisionCountdown;
    private long nextTicket = 1L;
    private long pendingTicket = -1L;
    private String lastCardId = "";
    private int lastRow = -1;
    private int lastColumn = -1;

    /** Whether anything the HUD draws has changed since it was last told. */
    public boolean dirty() {
        return dirty;
    }

    public void clearDirty() {
        dirty = false;
    }

    public Status status() {
        return status;
    }

    /** The card of the opponent's last accepted move, or empty when it has not moved yet. */
    public String lastCardId() {
        return lastCardId;
    }

    public int lastRow() {
        return lastRow;
    }

    public int lastColumn() {
        return lastColumn;
    }

    /** Stops the worker thread. Called when the level is left, so a closed run holds no thread. */
    public void close() {
        client.close();
        if (commander != null) {
            commander.close();
            commander = null;
        }
    }

    /**
     * The strategist's standing plan, or empty.
     *
     * <p>Shown in the F3 overlay and nothing else: a player watching the match should not have the
     * other side's intentions handed to them, but a developer asking "why did it do that" needs the
     * sentence the tactical model was given.
     */
    public String commanderPlan() {
        return directive;
    }

    /** The card the accepted plan asked for, or empty for "wait" (and when there is no plan). */
    public String commanderCard() {
        return commanderCard;
    }

    /** True while the strategist is thinking; part of the F3 line. */
    public boolean commanderBusy() {
        return commanderBusy;
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    /** One server tick of being somebody's opponent. */
    public void tick(LevelServer level, VersusData data) {
        if (!GameStateS2C.RUNNING.equals(level.gameState())) {
            return;
        }
        Team opponent = opponentTeam(level);
        if (opponent == null) {
            return;
        }
        boolean plantSide = PvzceIds.PLANT_TEAM.equals(opponent.id());
        tickCommander(level, data, opponent, plantSide);
        drainAnswers(level, data, opponent, plantSide);
        if (decisionCountdown > 0) {
            decisionCountdown--;
            return;
        }
        // Two cadences, because they are two different costs. Asking Jev is a network round trip, so
        // it happens every `decision_ticks` (three seconds); the built-in policy costs a few
        // comparisons, and at three seconds it was three times slower to react than a player - which
        // is how the plant opponent lost a match in 78 seconds to a rush a person would have held.
        decisionCountdown = level.jevSettings().configured()
                ? Math.max(BUSY_RETRY_TICKS, data.decisionTicks())
                : FALLBACK_INTERVAL_TICKS;
        ask(level, data, opponent, plantSide);
    }

    /** The team this brain plays: whoever the human is not. */
    public static Team opponentTeam(LevelServer level) {
        Identifier mine = PvzceIds.ZOMBIE_TEAM.equals(level.humanTeamId())
                ? PvzceIds.PLANT_TEAM : PvzceIds.ZOMBIE_TEAM;
        return level.team(mine);
    }

    // ------------------------------------------------------------------
    // The strategist tier
    // ------------------------------------------------------------------

    /**
     * How often the commander is asked to look at the board: thirty seconds.
     *
     * <p>Slower than the tactical loop by two orders of magnitude, and that is the design: the
     * tactical model answers "which card, where" every three seconds and must be cheap and stupid
     * about the long run; the commander answers "what is this match about" and must be neither. Thirty
     * seconds is the user's number, and it is roughly how long it takes a wave to change the answer.
     */
    private static final int COMMANDER_INTERVAL_TICKS = 30 * 60;

    private void tickCommander(LevelServer level, VersusData data, Team opponent, boolean plantSide) {
        AiSettings settings = level.commanderSettings();
        if (!settings.configured()) {
            return;
        }
        if (commander == null) {
            commander = new CommanderClient();
        }
        commanderBusy = commander.busy();
        Optional<String> answer = commander.poll();
        while (answer.isPresent()) {
            acceptPlan(level, data, opponent, plantSide, answer.get());
            answer = commander.poll();
        }
        // The plan is not part of "has anything the HUD draws changed": it is debug-only, so it
        // travels in the mechanic's own state when it changes (see VersusMechanic), not through this
        // flag - one dirty flag for one consumer, or every collection would re-send the whole board.
        if (commanderCountdown > 0) {
            commanderCountdown--;
            return;
        }
        // Asked even while a previous answer is in flight? No: one strategy line about one board is
        // worth one request, and a slow model must not build a queue of stale plans.
        if (commander.request(settings, commanderSnapshot(level, data, opponent, plantSide))) {
            commanderCountdown = COMMANDER_INTERVAL_TICKS;
        } else {
            commanderCountdown = COMMANDER_INTERVAL_TICKS / 4;
        }
    }

    /**
     * Takes the strategist's answer, or refuses it.
     *
     * <p>The answer's first line is meant to be one card id from the briefing (or {@code hold}), and
     * that is checked here rather than trusted, because a language model asked for "a specific card"
     * will happily name the one it knows from the game. The first live run did exactly that: a
     * commander told the zombie side to play a buckethead on a level whose deck holds only basic and
     * conehead zombies.
     *
     * <p>An order this side cannot execute is refused whole, not trimmed: the reasoning that came
     * with it was reasoned about a card that does not exist, so passing the reasoning on would hand
     * the tactical model a plan built on a fiction. Refused plans are logged with their text - the F3
     * panel only ever shows a plan that Jev was actually given.
     */
    private void acceptPlan(LevelServer level, VersusData data, Team opponent, boolean plantSide,
                            String answer) {
        List<CardChoice> hand = hand(level, data, opponent, plantSide);
        String firstLine = firstLine(answer);
        String wanted = cardIdIn(firstLine, hand);
        if (wanted == null && !isHold(firstLine)) {
            LOGGER.warn("commander named '{}', which this side cannot play; plan refused ({} cards "
                    + "in hand: {})", firstLine, hand.size(), handIds(hand));
            return;
        }
        directive = answer.trim();
        commanderCard = wanted == null ? "" : wanted;
        LOGGER.info("commander's plan: {} (first card: {})", directive,
                commanderCard.isEmpty() ? "hold" : commanderCard);
    }

    /** The first non-blank line of an answer, without markdown furniture. */
    private static String firstLine(String answer) {
        for (String line : answer.split("\n")) {
            String text = line.strip().replace("`", "").replace("*", "").strip();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    /**
     * The card id the first line names, as the hand spells it, or {@code null}.
     *
     * <p>Namespace-optional and separator-tolerant: a model writes {@code conehead_zombie: ...},
     * {@code pvzce:conehead_zombie}, or wraps the id in backticks, and all three mean the same card.
     */
    private static String cardIdIn(String line, List<CardChoice> hand) {
        String head = line;
        for (String separator : new String[]{"：", ":", "，", ",", "。", ".", " ", "\t", "-", "—"}) {
            int at = head.indexOf(separator);
            if (at >= 0) {
                head = head.substring(0, at);
            }
        }
        head = head.strip();
        if (head.isEmpty()) {
            return null;
        }
        for (CardChoice card : hand) {
            String id = card.card().slotId().toString();
            String path = card.card().slotId().path();
            if (id.equalsIgnoreCase(head) || path.equalsIgnoreCase(head)
                    || ("pvzce:" + head).equalsIgnoreCase(id)) {
                return id;
            }
        }
        return null;
    }

    /** True when the first line says "wait", in the two languages the prompt is written in. */
    private static boolean isHold(String line) {
        String head = line.strip().toLowerCase(java.util.Locale.ROOT);
        return head.startsWith("hold") || head.startsWith("wait") || head.contains("等待")
                || head.contains("按兵不动") || head.contains("攒");
    }

    /** The hand's ids, for the refusal log. */
    private static String handIds(List<CardChoice> hand) {
        List<String> ids = new ArrayList<>();
        for (CardChoice card : hand) {
            ids.add(card.card().slotId().path());
        }
        return String.join(",", ids);
    }

    /**
     * The board, as the prose a general model can read.
     *
     * <p>Written in the player's language (the model is told to answer in it) and shaped like a
     * briefing rather than a dump: which side it is, what the win condition is, how far along it is,
     * what it can afford, what is standing where, what is walking in, and what the previous plan was.
     * The last one matters most - a strategist that cannot see its own last order repeats it, and one
     * that can see it either keeps it or explains the change.
     */
    private String commanderSnapshot(LevelServer level, VersusData data, Team opponent,
                                     boolean plantSide) {
        StringBuilder text = new StringBuilder();
        text.append("你是").append(plantSide ? "植物方" : "僵尸方").append("的指挥官。\n");
        if (plantSide && data.races()) {
            text.append("胜利条件：在僵尸方攻破草坪之前收集到 ").append(data.sunGoal())
                    .append(" 阳光，当前 ").append(collected(level)).append("。\n");
        } else if (plantSide) {
            text.append("胜利条件：守住草坪。\n");
        } else {
            text.append("胜利条件：在植物方收集到足够阳光之前攻破草坪。\n");
        }
        text.append("当前阳光：").append(opponent.resourcesOf(PvzceIds.SUN)).append("。\n");
        text.append("阳光收入：").append(incomeText(level, data, plantSide)).append("。\n");
        if (!directive.isEmpty()) {
            text.append("你上次给的计划：").append(directive).append("\n");
        }
        List<CardChoice> hand = hand(level, data, opponent, plantSide);
        int sun = opponent.resourcesOf(PvzceIds.SUN);
        List<String> cards = new ArrayList<>();
        for (CardChoice card : hand) {
            cards.add(shortCardName(card.card().slotId()) + "("
                    + (card.cost() <= sun ? "" : "-") + card.cost()
                    + (card.ready() ? "" : "，冷却中 " + card.cooldownSeconds() + " 秒") + ")");
        }
        // A closed list, said out loud. The first live run had the commander order a buckethead on a
        // level whose zombie deck holds only basic and conehead zombies: the list was right there,
        // but nothing said it was *complete*, and a language model asked for "a card" reaches for the
        // one it knows from the game. The word 只有 and the sentence after the list are the fix.
        text.append(plantSide ? "你能用的植物只有这些（完整列表，括号里是价格，负号表示现在买不起）："
                : "你能用的僵尸只有这些（完整列表，括号里是价格，负号表示现在买不起）：");
        text.append(String.join("，", cards)).append("。\n");
        text.append("列表之外的任何卡都不可用，不要提到它们。\n");
        text.append("草坪（第 1 行在最上面，x 越小越靠近房子/左侧）：\n");
        for (int row = 0; row < level.height(); row++) {
            text.append("第 ").append(row + 1).append(" 行：");
            List<String> ours = new ArrayList<>();
            List<String> theirs = new ArrayList<>();
            for (PvzceEntity entity : level.entities()) {
                if (entity instanceof PlantEntity plant && !plant.isRemoved() && plant.gridY() == row) {
                    ours.add(shortCardName(plant.defId()) + "@" + String.format(
                            java.util.Locale.ROOT, "%.0f", plant.cellX() + 0.5F));
                }
                if (entity instanceof ZombieEntity zombie && zombie.isAlive()
                        && zombie.gridY() == row) {
                    theirs.add(shortCardName(zombie.defId()) + "@" + String.format(
                            java.util.Locale.ROOT, "%.1f", zombie.cellX()) + " hp"
                            + Math.round(zombie.health()));
                }
            }
            text.append(plantSide
                    ? (ours.isEmpty() ? "我方没有植物" : "我方 " + String.join("、", ours))
                    : (ours.isEmpty() ? "对方没有植物" : "对方 " + String.join("、", ours)));
            if (!theirs.isEmpty()) {
                text.append("；对方僵尸 ").append(String.join("、", theirs));
            }
            text.append("。\n");
        }
        text.append("请按这个格式回答：第一行只写上面列表里的一个卡 id（或者 hold），"
                + "第二行起写不超过两句话的理由。");
        return text.toString();
    }

    /**
     * How the side's sun arrives, in one phrase.
     *
     * <p>The wallet alone cannot be reasoned about: "50 now" and "50 now, thirty-five every five
     * seconds" lead to different plans, and a strategist that does not know the rate cannot say
     * "hold one payment and buy the good one" - which is the whole point of asking it.
     */
    private static String incomeText(LevelServer level, VersusData data, boolean plantSide) {
        if (plantSide) {
            // The sky is a level rule rather than a versus field, so it is read from the level.
            int min = level.rules().getInt(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN);
            int max = level.rules().getInt(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX);
            if (max <= 0) {
                return "没有天降阳光，靠植物自产";
            }
            return String.format(java.util.Locale.ROOT,
                    "天降阳光每 %.1f~%.1f 秒一朵，另有植物自产", min / 60.0, max / 60.0);
        }
        return String.format(java.util.Locale.ROOT, "每 %.1f 秒 %d",
                data.zombieIncomeTicks() / 60.0, data.zombieIncomeSun());
    }

    /** A content id without its namespace, for a briefing rather than a wire dump. */
    private static String shortCardName(Identifier id) {
        return id == null ? "?" : id.path();
    }

    // ------------------------------------------------------------------
    // Asking
    // ------------------------------------------------------------------

    private void ask(LevelServer level, VersusData data, Team opponent, boolean plantSide) {
        List<CardChoice> hand = hand(level, data, opponent, plantSide);
        if (hand.isEmpty()) {
            return;
        }
        AiSettings settings = level.jevSettings();
        if (!settings.configured()) {
            if (status != Status.BUILTIN) {
                status = Status.BUILTIN;
                dirty = true;
            }
            playFallback(level, data, opponent, plantSide, hand);
            return;
        }
        JevPrompt prompt = prompt(level, data, opponent, plantSide, hand, directive, commanderCard);
        long ticket = nextTicket++;
        if (client.request(settings, prompt, ticket)) {
            pendingTicket = ticket;
            return;
        }
        // The previous question is still on the wire. Waiting for it is better than asking a second
        // one about a board that is about to change under both of them.
        decisionCountdown = BUSY_RETRY_TICKS;
    }

    private void drainAnswers(LevelServer level, VersusData data, Team opponent, boolean plantSide) {
        Optional<JevClient.Result> next = client.poll();
        while (next.isPresent()) {
            JevClient.Result result = next.get();
            if (result.ticket() == pendingTicket) {
                pendingTicket = -1L;
                handle(level, data, opponent, plantSide, result);
            }
            next = client.poll();
        }
    }

    private void handle(LevelServer level, VersusData data, Team opponent, boolean plantSide,
                        JevClient.Result result) {
        if (result.usable() && play(level, opponent, plantSide, result.decision())) {
            status = Status.JEV;
            dirty = true;
            lastCardId = result.decision().isHold() ? lastCardId : result.decision().cardId();
            lastRow = result.decision().row();
            lastColumn = result.decision().column();
            LOGGER.debug("Jev played {} in {}ms (confidence {})", result.decision(),
                    result.millis(), result.decision().confidence());
            return;
        }
        if (!result.usable()) {
            reportFallback(level, result.error());
        } else {
            LOGGER.info("Jev chose {}, which this level cannot execute; using the built-in policy",
                    result.decision());
        }
        status = Status.FALLBACK;
        dirty = true;
        playFallback(level, data, opponent, plantSide, hand(level, data, opponent, plantSide));
    }

    /**
     * Says once, in the player's face, that the opponent is not Jev right now.
     *
     * <p>Once per run on purpose: with a three-second decision interval a broken key would
     * otherwise write a hundred identical lines, and the second one tells the player nothing the
     * first did not.
     */
    private void reportFallback(LevelServer level, String reason) {
        LOGGER.warn("Jev did not answer ({}); the built-in opponent takes over", reason);
        if (fallbackReported) {
            return;
        }
        fallbackReported = true;
        level.send(new com.pvzce.common.network.packet.ServerMessageS2C(
                "Jev 没有回应（" + reason + "），这一方暂时改用内置策略。"));
    }

    /**
     * Tries to execute one decision, through the same rules a player's packet goes through.
     *
     * @return true when the move was accepted and something was spawned
     */
    public boolean play(LevelServer level, Team opponent, boolean plantSide, JevDecision decision) {
        if (decision == null || decision.isHold()) {
            return decision != null;
        }
        SlotResolver.ResolvedCard card = SlotResolver.resolve(Identifier.parse(decision.cardId()))
                .orElse(null);
        if (card == null || !kindMatches(card.kind(), plantSide)) {
            return false;
        }
        // The decision carries a row and a column; the board wants x and y, and the two are not
        // the same order. Getting this wrong is invisible until a move is refused for being off the
        // board - which is exactly what the versus test caught the first time it ran.
        int x = decision.column();
        int y = decision.row();
        return plantSide
                ? tryPlant(level, opponent, card, x, y)
                : tryZombie(level, opponent, card, x, y);
    }

    private static boolean kindMatches(Slot.Kind kind, boolean plantSide) {
        return plantSide ? kind == Slot.Kind.PLANT : kind == Slot.Kind.ZOMBIE;
    }

    /** Spends the card and plants it, or answers false without having spent anything. */
    private static boolean tryPlant(LevelServer level, Team team, SlotResolver.ResolvedCard card,
                                    int x, int y) {
        PlantDef def = BuiltInRegistries.PLANTS.get(card.content());
        if (def == null || !level.inBounds(x, y) || !level.canPlacePlant(def, x, y)) {
            return false;
        }
        // Through the opponent's own bar, which is the same bar a player's clicks go through: a card
        // that is still recharging cannot be played, and playing one starts its recharge. The AI used
        // to bypass all of it and buy the same card as fast as its sun allowed.
        Slot slot = level.opponentSlot(card.slotId());
        String refusal = level.chargeCard(team, slot);
        if (refusal != null) {
            return false;
        }
        int cost = slot.costSun();
        if (level.spawnPlant(def, team, x, y) == null) {
            // Nothing was placed, so nothing was spent: the sun comes back and the card is ready
            // again. A refused cell must not cost a cooldown, or a lane that is briefly occupied
            // would lock a card out of the bar for the rest of its recharge.
            refundCard(level, team, slot, cost);
            return false;
        }
        return true;
    }

    /** Undoes {@link LevelServer#chargeCard} after a placement the board refused. */
    private static void refundCard(LevelServer level, Team team, Slot slot, int cost) {
        if (cost > 0) {
            team.addResource(PvzceIds.SUN, cost);
        }
        // The level owns the rule, and both callers use it: the AI's refused cell and the player's.
        level.restoreCard(slot);
    }

    /** The zombie side's half: paid for, placed, and refunded if the board refuses it. */
    private static boolean tryZombie(LevelServer level, Team team, SlotResolver.ResolvedCard card,
                                     int x, int y) {
        if (BuiltInRegistries.ZOMBIES.get(card.content()) == null) {
            return false;
        }
        if (!level.inBounds(x, y) || !level.canPlaceZombie(x, y)) {
            return false;
        }
        Slot slot = level.opponentSlot(card.slotId());
        String refusal = level.chargeCard(team, slot);
        if (refusal != null) {
            return false;
        }
        int cost = slot.costSun();
        ZombieEntity spawned = level.spawnZombie(card.content(), team, x + 0.5F, y);
        if (spawned == null) {
            refundCard(level, team, slot, cost);
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // The built-in policy
    // ------------------------------------------------------------------

    /**
     * Plays one turn without Jev: the cheapest card it can afford, aimed at the lane that needs it.
     *
     * <p>The plant side builds from the back and only reaches forward when a zombie is close to the
     * house; the zombie side spreads into the emptiest lane and enters as far forward as its zone
     * allows, which is both the most aggressive placement and the one a player would call "the
     * zombies are already among the plants".
     */
    private void playFallback(LevelServer level, VersusData data, Team opponent, boolean plantSide,
                              List<CardChoice> hand) {
        int sun = opponent.resourcesOf(PvzceIds.SUN);
        List<CardChoice> affordable = new ArrayList<>();
        for (CardChoice card : hand) {
            if (card.cost() <= sun) {
                affordable.add(card);
            }
        }
        if (affordable.isEmpty()) {
            // Nothing it can buy. Doing nothing *is* the policy here: saving toward the next card
            // is a turn spent, not a turn wasted.
            return;
        }
        affordable.sort(Comparator.comparingInt(CardChoice::cost));
        if (plantSide) {
            playPlantFallback(level, opponent, affordable);
            return;
        }
        playZombieFallback(level, data, opponent, hand, affordable);
    }

    /**
     * The zombie side's turn without Jev: spread out first, then spend the surplus on something
     * that survives.
     *
     * <p>The version before this always bought the <em>cheapest</em> card it could afford, which on
     * a level that pays fifty sun at a time and sells a fifty-sun zombie meant a match of nothing
     * but basic zombies - every payment was spent the moment it arrived, so the coneheads and
     * bucketheads were never reachable. The policy is now:
     *
     * <ol>
     *   <li>a lane with no zombie in it gets the cheapest card, because pressure in an empty lane
     *       is worth more than quality in a busy one;</li>
     *   <li>otherwise, if the best card in hand is one payment away, <b>hold this turn</b> and buy
     *       it next time - that is the "wait once" the mode was missing;</li>
     *   <li>otherwise buy the strongest card it can afford, so a surplus becomes a zombie that the
     *       lawn has to work for.</li>
     * </ol>
     */
    private void playZombieFallback(LevelServer level, VersusData data, Team opponent,
                                    List<CardChoice> hand, List<CardChoice> affordable) {
        CardChoice chosen = chooseZombieCard(level, data, opponent, hand, affordable);
        if (chosen == null) {
            return;
        }
        int column = frontColumn(level, false);
        int row = laneWithoutZombies(level);
        if (row < 0) {
            row = emptiestRow(level);
        }
        if (tryZombie(level, opponent, chosen.card(), column, row)) {
            record(chosen, column, row);
        }
    }

    /**
     * Which card the zombie side buys, or {@code null} for "wait this turn".
     *
     * <p>Separate from where it goes, and that separation is the fix for a real bug: the first
     * version decided the lane first - "a lane with no zombie in it gets the cheapest card" - and
     * because a lane is almost always empty in a running match, the cheapest card was the only card
     * it ever bought. The measurement was unambiguous: 191 zombies, all of them basic.
     *
     * <p>The order is about money, not about lanes:
     * <ol>
     *   <li>a surplus (the best card plus a reserve) buys the best card, so the lawn has to answer
     *       something with hit points;</li>
     *   <li>if the best card is one payment away, <b>wait one turn</b> for it instead of spending
     *       the same sun on a weaker one - the "allow the AI to wait once" the mode was missing;</li>
     *   <li>otherwise the strongest card it can afford, which is what keeps pressure on between the
     *       big ones.</li>
     * </ol>
     */
    private CardChoice chooseZombieCard(LevelServer level, VersusData data, Team opponent,
                                        List<CardChoice> hand, List<CardChoice> affordable) {
        int sun = opponent.resourcesOf(PvzceIds.SUN);
        CardChoice cheapest = affordable.get(0);
        CardChoice strongest = affordable.get(affordable.size() - 1);
        CardChoice best = hand.get(hand.size() - 1);

        // Every third purchase is a quality one. A fixed ratio rather than "save whenever the next
        // upgrade is close", because that rule fed back into the level's income: a richer level made
        // the upgrade reachable, the upgrade doubled the damage per sun, and the same income went
        // from fair to overwhelming (measured: peak 20 zombies, matches over in three minutes).
        // A fixed mix keeps the damage per sun a property of the ratio instead of of the paycheck.
        boolean qualityTurn = ++placements % QUALITY_EVERY == 0;
        if (!qualityTurn) {
            return cheapest;
        }
        if (best.cost() <= sun) {
            return best;
        }
        // A quality turn that cannot afford quality waits for it, up to one payment: that is the
        // "let the AI wait once" the mode was missing. With nothing on the board it sends the cheap
        // one instead - an opponent that opens with nothing is not an opponent.
        if (best.cost() - sun <= Math.max(1, data.zombieIncomeSun()) && boardHasAZombie(level)) {
            placements--;
            return null;
        }
        return strongest;
    }

    /** One purchase in this many is the best the side can afford; the rest keep the pressure on. */
    private static final int QUALITY_EVERY = 3;
    /** How many purchases this opponent has made, for the mix above. */
    private int placements;

    /** True when at least one of this side's zombies is alive. */
    private static boolean boardHasAZombie(LevelServer level) {
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie && zombie.isAlive()) {
                return true;
            }
        }
        return false;
    }

    /** A lane with no living zombie in it, or {@code -1} when every lane has one. */
    private static int laneWithoutZombies(LevelServer level) {
        boolean[] occupied = new boolean[Math.max(1, level.height())];
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie && zombie.isAlive()
                    && zombie.gridY() >= 0 && zombie.gridY() < occupied.length) {
                occupied[zombie.gridY()] = true;
            }
        }
        for (int row = 0; row < occupied.length; row++) {
            if (!occupied[row]) {
                return row;
            }
        }
        return -1;
    }

    /**
     * The plant side's turn without Jev: defend what is threatened, otherwise build the economy.
     *
     * <p>Two modes, because the two halves of this side's game want opposite things. With a zombie
     * on the board it spends its best card in the lane that zombie is closest to, one cell behind
     * the front - a lane that is being walked into needs a shooter more than the lawn needs another
     * sunflower. With nobody on the board it buys the cheapest card it has and plants it at the
     * back, which on every deck a level has written is the sun producer.
     *
     * <p>Measured against the same routine the versus soak drives the plant side with: it is a
     * policy a competent player would recognise, not a stub that idles.
     */
    private void playPlantFallback(LevelServer level, Team opponent, List<CardChoice> affordable) {
        // Roles, not prices. The version before this bought "the cheapest card it had" whenever the
        // lawn was quiet, which on every deck a level has written is the sun producer - so the plant
        // opponent spent whole matches planting sunflowers and never bought a repeater or a wall.
        //
        // Every rule tries its candidates in order rather than making one attempt: the first version
        // of this method picked the one best lane and gave up when that cell was occupied, which the
        // cooldown test caught as "the opponent planted one shooter and then stood still". A lane
        // being full is the normal case on a lawn that is working, not a reason to stop playing.
        List<CardChoice> producers = withRole(affordable, Role.PRODUCER);
        List<CardChoice> defenders = withRole(affordable, Role.SHOOTER);
        List<CardChoice> walls = withRole(affordable, Role.WALL);
        // 1. A lane that is about to be walked into and has nothing to shoot back with.
        if (!defenders.isEmpty()) {
            int column = shooterColumn(level);
            for (int lane : lanesWithZombieAndNoShooter(level)) {
                for (int i = defenders.size() - 1; i >= 0; i--) {
                    CardChoice card = defenders.get(i);
                    if (tryPlant(level, opponent, card.card(), column, lane)) {
                        record(card, column, lane);
                        return;
                    }
                }
            }
        }

        // 2. A wall in front of the lane a zombie has nearly reached: the zombie stops to eat it,
        //    which is the time the shooter behind it needs. Before the economy, because a lane that
        //    is being walked into does not need another sunflower.
        if (!walls.isEmpty() && frontmostZombieX(level) < WALL_DISTANCE) {
            int column = frontColumn(level, true);
            for (int lane : lanesByThreat(level)) {
                if (shooters(level, lane) == 0) {
                    continue;
                }
                CardChoice wall = walls.get(walls.size() - 1);
                if (tryPlant(level, opponent, wall.card(), column, lane)) {
                    record(wall, column, lane);
                    return;
                }
            }
        }

        // 3. The economy, up to a point: past five producers another sunflower is a lane the lawn
        //    cannot defend.
        if (!producers.isEmpty() && producers(level) < PRODUCER_TARGET) {
            CardChoice cheapest = producers.get(0);
            int back = backColumn(level, true);
            for (int lane : lanesEmptiestFirst(level)) {
                if (tryPlant(level, opponent, cheapest.card(), back, lane)) {
                    record(cheapest, back, lane);
                    return;
                }
            }
        }

        // 4. Otherwise widen the defense: the lane with the fewest shooters gets the best shooter the
        //    side can afford.
        if (!defenders.isEmpty()) {
            int column = shooterColumn(level);
            for (int lane : lanesByFewestShooters(level)) {
                for (int i = defenders.size() - 1; i >= 0; i--) {
                    CardChoice card = defenders.get(i);
                    if (tryPlant(level, opponent, card.card(), column, lane)) {
                        record(card, column, lane);
                        return;
                    }
                }
            }
        }
    }

    /** Lanes with a zombie in them and no shooter of ours, most threatened first. */
    private static List<Integer> lanesWithZombieAndNoShooter(LevelServer level) {
        List<Integer> lanes = new ArrayList<>();
        for (int lane : lanesByThreat(level)) {
            if (shooters(level, lane) == 0) {
                lanes.add(lane);
            }
        }
        return lanes;
    }

    /** Every lane, the one whose frontmost zombie is closest to the house first. */
    private static List<Integer> lanesByThreat(LevelServer level) {
        List<Integer> lanes = new ArrayList<>();
        for (int row = 0; row < level.height(); row++) {
            lanes.add(row);
        }
        lanes.sort(java.util.Comparator.comparingDouble(lane -> frontmostZombieX(level, lane)));
        return lanes;
    }

    /** Every lane, the one with the fewest plants in it first. */
    private static List<Integer> lanesEmptiestFirst(LevelServer level) {
        List<Integer> lanes = new ArrayList<>();
        for (int row = 0; row < level.height(); row++) {
            lanes.add(row);
        }
        lanes.sort(java.util.Comparator.comparingInt(lane -> plantsInLane(level, lane)));
        return lanes;
    }

    /** Every lane, the one with the fewest shooters first. */
    private static List<Integer> lanesByFewestShooters(LevelServer level) {
        List<Integer> lanes = new ArrayList<>();
        for (int row = 0; row < level.height(); row++) {
            lanes.add(row);
        }
        lanes.sort(java.util.Comparator.comparingInt(lane -> shooters(level, lane)));
        return lanes;
    }

    /** How many plants of ours, of any kind, stand in one lane. */
    private static int plantsInLane(LevelServer level, int lane) {
        int found = 0;
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof PlantEntity plant && !plant.isRemoved() && plant.gridY() == lane) {
                found++;
            }
        }
        return found;
    }

    /** The x of the frontmost zombie in one lane, or {@link Float#MAX_VALUE} when it has none. */
    private static float frontmostZombieX(LevelServer level, int lane) {
        float closest = Float.MAX_VALUE;
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie && zombie.isAlive() && zombie.gridY() == lane
                    && zombie.cellX() < closest) {
                closest = zombie.cellX();
            }
        }
        return closest;
    }

    /**
     * Where a shooter goes: one cell behind the front of the plantable area.
     *
     * <p>Not the front cell, because that is where the wall goes and where the first zombie arrives;
     * not the back, because a lane's shots travel the whole lane from anywhere and the front column
     * is the one a zombie reaches last. The calibrated stand-in for a player uses the same column,
     * which is what makes the two comparable.
     */
    private static int shooterColumn(LevelServer level) {
        return Math.max(backColumn(level, true) + 1, frontColumn(level, true) - 2);
    }

    /** How close a zombie has to be before a wall is worth planting in front of it. */
    private static final float WALL_DISTANCE = 6.5F;

    /** The x of the frontmost living zombie, or {@code Float.MAX_VALUE} when there is none. */
    private static float frontmostZombieX(LevelServer level) {
        float closest = Float.MAX_VALUE;
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie && zombie.isAlive() && zombie.cellX() < closest) {
                closest = zombie.cellX();
            }
        }
        return closest;
    }

    /** How many sun producers a level's plant deck is worth building before defense takes over. */
    private static final int PRODUCER_TARGET = 5;

    /** What a card is for, read from its own capabilities rather than guessed from its id. */
    private enum Role {
        PRODUCER,
        SHOOTER,
        WALL,
        OTHER
    }

    /** The role of one card, from the capabilities its content declares. */
    private static Role roleOf(SlotResolver.ResolvedCard card) {
        return roleOf(BuiltInRegistries.PLANTS.get(card.content()));
    }

    /** The role of one plant definition, read from the capability types it declares. */
    private static Role roleOf(PlantDef plant) {
        if (plant == null) {
            return Role.OTHER;
        }
        boolean shooter = false;
        for (var capability : plant.capabilities()) {
            String path = capability.type().path();
            if ("producer".equals(path) || "gold_magnet".equals(path)) {
                return Role.PRODUCER;
            }
            if ("shooter".equals(path) || "thrower".equals(path) || "cactus".equals(path)
                    || "melee".equals(path) || "spike".equals(path) || "cob_cannon".equals(path)
                    || "torchwood".equals(path)) {
                shooter = true;
            }
        }
        // A wall is a plant that is only a wall: the ones with a shooter capability are shooters
        // (a tall-nut has none, a pumpkin has none, a peashooter does).
        if (!shooter && plant.health() >= WALL_HEALTH) {
            return Role.WALL;
        }
        return shooter ? Role.SHOOTER : Role.OTHER;
    }

    /** Health from which a plant with no attack of its own counts as a wall. */
    private static final int WALL_HEALTH = 1000;

    /** The affordable cards of one role, cheapest first. */
    private static List<CardChoice> withRole(List<CardChoice> affordable, Role role) {
        List<CardChoice> found = new ArrayList<>();
        for (CardChoice card : affordable) {
            if (roleOf(card.card()) == role) {
                found.add(card);
            }
        }
        return found;
    }

    /** How many sun producers are standing, whatever their kind. */
    private static int producers(LevelServer level) {
        int found = 0;
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof PlantEntity plant && !plant.isRemoved()
                    && roleOfEntity(plant) == Role.PRODUCER) {
                found++;
            }
        }
        return found;
    }

    private static Role roleOfEntity(PlantEntity plant) {
        return roleOf(BuiltInRegistries.PLANTS.get(plant.defId()));
    }

    /** A lane with a zombie in it and no shooter of ours, or {@code -1}. */
    private static int laneWithZombieAndNoShooter(LevelServer level) {
        int best = -1;
        float closest = Float.MAX_VALUE;
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie && zombie.isAlive()
                    && shooters(level, zombie.gridY()) == 0 && zombie.cellX() < closest) {
                closest = zombie.cellX();
                best = Math.max(0, zombie.gridY());
            }
        }
        return best;
    }

    /** The lane with the fewest shooters of ours; ties go to the lowest lane. */
    private static int laneWithFewestShooters(LevelServer level) {
        int best = 0;
        for (int row = 1; row < level.height(); row++) {
            if (shooters(level, row) < shooters(level, best)) {
                best = row;
            }
        }
        return best;
    }

    /** How many shooters stand in one lane. */
    private static int shooters(LevelServer level, int row) {
        int found = 0;
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof PlantEntity plant && !plant.isRemoved() && plant.gridY() == row) {
                Role role = roleOfEntity(plant);
                if (role == Role.SHOOTER) {
                    found++;
                }
            }
        }
        return found;
    }

    /** The lane of any living zombie, or {@code -1} when the lawn is clear. */
    private static int anyLaneWithAZombie(LevelServer level) {
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie && zombie.isAlive()) {
                return Math.max(0, zombie.gridY());
            }
        }
        return -1;
    }

    /** A lane with no plants in it at all, or the lowest lane when every lane has one. */
    private static int anyLaneWithoutPlants(LevelServer level) {
        int[] plants = new int[Math.max(1, level.height())];
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof PlantEntity plant && !plant.isRemoved()
                    && plant.gridY() >= 0 && plant.gridY() < plants.length) {
                plants[plant.gridY()]++;
            }
        }
        int best = 0;
        for (int row = 1; row < plants.length; row++) {
            if (plants[row] < plants[best]) {
                best = row;
            }
        }
        return best;
    }

    private void record(CardChoice card, int column, int row) {
        lastCardId = card.card().slotId().toString();
        lastRow = row;
        lastColumn = column;
        dirty = true;
    }

    /** True when some lane has a zombie close enough to the house to be worth spending on. */
    private static boolean threatened(LevelServer level) {
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie && zombie.isAlive()
                    && zombie.cellX() <= DANGER_CELL_X) {
                return true;
            }
        }
        return false;
    }

    /** The lane whose frontmost zombie is closest to the house, or {@code -1} when there is none. */
    private static int mostThreatenedRow(LevelServer level) {
        int best = -1;
        float bestX = Float.MAX_VALUE;
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie && zombie.isAlive()
                    && zombie.cellX() < bestX) {
                bestX = zombie.cellX();
                best = Math.max(0, zombie.gridY());
            }
        }
        return best;
    }

    /** The lane with the fewest zombies; ties go to the lowest row. */
    private static int emptiestRow(LevelServer level) {
        int[] counts = new int[Math.max(1, level.height())];
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie && zombie.isAlive()
                    && zombie.gridY() >= 0 && zombie.gridY() < counts.length) {
                counts[zombie.gridY()]++;
            }
        }
        int best = 0;
        for (int row = 1; row < counts.length; row++) {
            if (counts[row] < counts[best]) {
                best = row;
            }
        }
        return best;
    }

    /** The column of this side's zone that is closest to the enemy. */
    private static int frontColumn(LevelServer level, boolean plantSide) {
        List<Integer> columns = legalColumns(level, plantSide);
        if (columns.isEmpty()) {
            return 0;
        }
        int best = columns.get(0);
        for (int column : columns) {
            best = plantSide ? Math.max(best, column) : Math.min(best, column);
        }
        return best;
    }

    /** The column of this side's zone that is furthest from the enemy. */
    private static int backColumn(LevelServer level, boolean plantSide) {
        List<Integer> columns = legalColumns(level, plantSide);
        if (columns.isEmpty()) {
            return 0;
        }
        int best = columns.get(0);
        for (int column : columns) {
            best = plantSide ? Math.min(best, column) : Math.max(best, column);
        }
        return best;
    }

    // ------------------------------------------------------------------
    // What the opponent can see
    // ------------------------------------------------------------------

    /** The placeable cards of the opponent's deck, in the order the level listed them. */
    static List<CardChoice> hand(LevelServer level, VersusData data, Team opponent,
                                 boolean plantSide) {
        List<CardChoice> hand = new ArrayList<>();
        for (Identifier cardId : data.cardsFor(opponent.id())) {
            SlotResolver.ResolvedCard card = SlotResolver.resolve(cardId).orElse(null);
            if (card == null || !kindMatches(card.kind(), plantSide)) {
                continue;
            }
            Slot slot = level.opponentSlot(card.slotId());
            hand.add(new CardChoice(card, describe(card, plantSide, slot),
                    slot == null || slot.ready(), slot == null ? 0 : slot.cooldownLeft()));
        }
        return List.copyOf(hand);
    }

    /** One card, as the prompt describes it: what it is, what it costs, what it can take. */
    private static String describe(SlotResolver.ResolvedCard card, boolean plantSide, Slot slot) {
        StringBuilder text = new StringBuilder(card.slotId().toString());
        text.append(" — ").append(plantSide ? "plant" : "zombie");
        text.append(", costs ").append(card.costSun()).append(" sun");
        if (slot != null && !slot.ready()) {
            text.append(", recharging for another ")
                    .append((slot.cooldownLeft() + PvzceConstants.TICKS_PER_SECOND - 1)
                            / PvzceConstants.TICKS_PER_SECOND)
                    .append(" seconds");
        }
        if (plantSide) {
            PlantDef plant = BuiltInRegistries.PLANTS.get(card.content());
            if (plant != null) {
                text.append(", ").append(plant.health()).append(" hp");
            }
        } else {
            ZombieDef zombie = BuiltInRegistries.ZOMBIES.get(card.content());
            if (zombie != null) {
                text.append(", ").append(zombie.health()).append(" hp");
                text.append(", walks at ").append(String.format(java.util.Locale.ROOT, "%.2f",
                        zombie.moveSpeed())).append(" cells per second");
            }
        }
        return text.toString();
    }

    /** The columns this side may legally use, in board order. */
    static List<Integer> legalColumns(LevelServer level, boolean plantSide) {
        List<Integer> columns = new ArrayList<>();
        for (int x = 0; x < level.width(); x++) {
            boolean legal = plantSide
                    ? level.plantZoneContains(x)
                    : level.zombieZoneContains(x);
            if (legal) {
                columns.add(x);
            }
        }
        return List.copyOf(columns);
    }

    /**
     * One card the opponent may play, with the sentence the prompt shows for it.
     *
     * <p>{@code ready} and {@code cooldownLeft} come from the opponent's own bar, which is what makes
     * "this card is recharging for another eight seconds" something the models can be told instead of
     * something they discover by having a move refused.
     */
    record CardChoice(SlotResolver.ResolvedCard card, String description, boolean ready,
                      int cooldownLeft) {
        int cost() {
            return card.costSun();
        }

        /** Whole seconds of recharge left, rounded up; zero when the card is ready. */
        int cooldownSeconds() {
            return cooldownLeft <= 0 ? 0
                    : (cooldownLeft + PvzceConstants.TICKS_PER_SECOND - 1)
                            / PvzceConstants.TICKS_PER_SECOND;
        }
    }

    private static JevPrompt prompt(LevelServer level, VersusData data, Team opponent,
                                    boolean plantSide, List<CardChoice> hand, String directive,
                                    String commanderCard) {
        int sun = opponent.resourcesOf(PvzceIds.SUN);
        List<JevPrompt.CardOption> cards = new ArrayList<>();
        for (CardChoice choice : hand) {
            cards.add(new JevPrompt.CardOption(choice.card().slotId().toString(), choice.cost(),
                    choice.cost() <= sun, choice.description(), choice.ready(),
                    choice.cooldownSeconds()));
        }
        List<Integer> columns = legalColumns(level, plantSide);
        List<JevPrompt.ColumnOption> columnOptions = new ArrayList<>();
        for (int column : columns) {
            columnOptions.add(new JevPrompt.ColumnOption(column, describeColumn(level, plantSide, column)));
        }
        return new JevPrompt(
                plantSide ? JevPrompt.Side.PLANT : JevPrompt.Side.ZOMBIE,
                objective(level, data, plantSide),
                sun,
                plantSide && data.races() ? data.sunGoal() : JevPrompt.NO_GOAL,
                plantSide && data.races() ? collected(level) : 0,
                level.tickCount() / PvzceConstants.TICKS_PER_SECOND,
                cards,
                describeRows(level),
                columnOptions,
                directive,
                commanderCard);
    }

    /**
     * How much sun the plant side has picked up, or zero on a level that is not racing one.
     *
     * <p>Read from the mode's own run state, which is the same number the HUD shows: the opponent
     * having to count pickups for itself would be a second answer to "how far along is the race".
     */
    private static int collected(LevelServer level) {
        Object run = level.mechanicStateOrNull(PvzceIds.MECHANIC_VERSUS,
                com.pvzce.common.level.mechanic.VersusMechanic.Run.class);
        return run instanceof com.pvzce.common.level.mechanic.VersusMechanic.Run state
                ? state.collected : 0;
    }

    private static String objective(LevelServer level, VersusData data, boolean plantSide) {
        if (plantSide) {
            return "You are the plant side, playing against a human on the zombie side. Collect "
                    + data.sunGoal() + " sun by picking up the sun that falls and the sun your "
                    + "sunflowers make, and do not let a zombie reach the left edge. You lose the "
                    + "moment one does.";
        }
        return "You are the zombie side, playing against a human on the plant side. Break through: "
                + "get one zombie past the left edge before the plant side collects " + data.sunGoal()
                + " sun. Plants shoot, so a zombie that walks into one alone is spent.";
    }

    /** One line per lane: what is in it, and how far up it is. */
    static List<JevPrompt.RowOption> describeRows(LevelServer level) {
        List<JevPrompt.RowOption> rows = new ArrayList<>();
        for (int y = 0; y < level.height(); y++) {
            StringBuilder text = new StringBuilder("row_" + y + ": ");
            List<String> parts = new ArrayList<>();
            for (PvzceEntity entity : level.entities()) {
                if (entity instanceof PlantEntity plant && plant.gridY() == y && !plant.isRemoved()) {
                    parts.add("plant " + plant.defId().path() + " at x" + plant.gridX()
                            + " (" + plant.health() + " hp)");
                } else if (entity instanceof ZombieEntity zombie && zombie.isAlive()
                        && zombie.gridY() == y) {
                    parts.add("zombie " + zombie.defId().path() + " at x"
                            + String.format(java.util.Locale.ROOT, "%.1f", zombie.cellX())
                            + " (" + zombie.health() + " hp)");
                }
            }
            // The house is at x=0 and the zombies walk towards it: saying which end is which in
            // every line is what keeps the model from having to be told the board's orientation
            // separately, and it is one clause.
            text.append(parts.isEmpty() ? "empty (no plants, no zombies)" : String.join("; ", parts));
            text.append(" — x0 is the house end, x").append(level.width() - 1).append(" the far end");
            rows.add(new JevPrompt.RowOption(y, text.toString()));
        }
        return List.copyOf(rows);
    }

    private static String describeColumn(LevelServer level, boolean plantSide, int column) {
        if (plantSide) {
            return column == 0
                    ? "col_0: the column against the house, the safest place to build up"
                    : "col_" + column + ": " + (column == 4 ? "the front line, closest to where the "
                            + "zombies arrive" : "a middle column, safer than the front");
        }
        return column == 5
                ? "col_5: the column closest to the plants, the most aggressive place to arrive"
                : "col_" + column + ": " + (column == level.width() - 1
                        ? "the far right, where a zombie has the most lawn left to walk"
                        : "a column behind the front line");
    }

}
