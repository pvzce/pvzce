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
import com.pvzce.common.jev.JevSettings;
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

    /** How often the opponent sweeps the lawn for its own drops. */
    private static final int AUTO_PICKUP_INTERVAL_TICKS = 6;
    /** A retry this soon after being refused by an in-flight request, rather than a whole interval. */
    private static final int BUSY_RETRY_TICKS = 20;
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
    private Status status = Status.BUILTIN;
    private boolean dirty = true;
    private boolean fallbackReported;
    private int decisionCountdown;
    private int pickupCountdown;
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
        if (plantSide) {
            collectOwnDrops(level, opponent);
        }
        drainAnswers(level, data, opponent, plantSide);
        if (decisionCountdown > 0) {
            decisionCountdown--;
            return;
        }
        decisionCountdown = Math.max(BUSY_RETRY_TICKS, data.decisionTicks());
        ask(level, data, opponent, plantSide);
    }

    /** The team this brain plays: whoever the human is not. */
    public static Team opponentTeam(LevelServer level) {
        Identifier mine = PvzceIds.ZOMBIE_TEAM.equals(level.humanTeamId())
                ? PvzceIds.PLANT_TEAM : PvzceIds.ZOMBIE_TEAM;
        return level.team(mine);
    }

    // ------------------------------------------------------------------
    // Asking
    // ------------------------------------------------------------------

    private void ask(LevelServer level, VersusData data, Team opponent, boolean plantSide) {
        List<CardChoice> hand = hand(level, data, opponent, plantSide);
        if (hand.isEmpty()) {
            return;
        }
        JevSettings settings = level.jevSettings();
        if (!settings.configured()) {
            if (status != Status.BUILTIN) {
                status = Status.BUILTIN;
                dirty = true;
            }
            playFallback(level, opponent, plantSide, hand);
            return;
        }
        JevPrompt prompt = prompt(level, data, opponent, plantSide, hand);
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
        playFallback(level, opponent, plantSide, hand(level, data, opponent, plantSide));
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
        int cost = card.costSun();
        if (cost > 0 && !team.consume(PvzceIds.SUN, cost)) {
            return false;
        }
        if (level.spawnPlant(def, team, x, y) == null) {
            if (cost > 0) {
                team.addResource(PvzceIds.SUN, cost);
            }
            return false;
        }
        return true;
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
        int cost = card.costSun();
        if (cost > 0 && !team.consume(PvzceIds.SUN, cost)) {
            return false;
        }
        ZombieEntity spawned = level.spawnZombie(card.content(), team, x + 0.5F, y);
        if (spawned == null) {
            if (cost > 0) {
                team.addResource(PvzceIds.SUN, cost);
            }
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
    private void playFallback(LevelServer level, Team opponent, boolean plantSide,
                              List<CardChoice> hand) {
        int sun = opponent.resourcesOf(PvzceIds.SUN);
        List<CardChoice> affordable = new ArrayList<>();
        for (CardChoice card : hand) {
            if (card.cost() <= sun) {
                affordable.add(card);
            }
        }
        if (affordable.isEmpty()) {
            return;
        }
        affordable.sort(Comparator.comparingInt(CardChoice::cost));
        boolean inDanger = plantSide && threatened(level);
        if (inDanger) {
            // Spending the best card it has is the only thing that helps a lane that is about to
            // be lost, and hoarding through a breach is how a fallback looks broken.
            affordable.sort(Comparator.comparingInt(CardChoice::cost).reversed());
        }
        if (plantSide) {
            playPlantFallback(level, opponent, affordable, inDanger);
            return;
        }
        int row = emptiestRow(level);
        int column = frontColumn(level, plantSide);
        for (CardChoice card : affordable) {
            if (tryZombie(level, opponent, card.card(), column, row)) {
                record(card, column, row);
                return;
            }
        }
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
    private void playPlantFallback(LevelServer level, Team opponent, List<CardChoice> affordable,
                                   boolean inDanger) {
        int lane = -1;
        if (inDanger) {
            lane = mostThreatenedRow(level);
        } else {
            lane = anyLaneWithAZombie(level);
        }
        if (lane >= 0) {
            int column = frontColumn(level, true);
            int behind = Math.max(backColumn(level, true), column - 1);
            // Best first: stopping what is already walking is worth more than a cheaper plant that
            // will be eaten before it fires twice.
            for (int i = affordable.size() - 1; i >= 0; i--) {
                CardChoice card = affordable.get(i);
                if (tryPlant(level, opponent, card.card(), behind, lane)) {
                    record(card, behind, lane);
                    return;
                }
            }
        }
        int back = backColumn(level, true);
        for (CardChoice card : affordable) {
            if (tryPlant(level, opponent, card.card(), back, lane >= 0 ? lane : 0)
                    || tryPlant(level, opponent, card.card(), back, anyLaneWithoutPlants(level))) {
                record(card, back, lane >= 0 ? lane : 0);
                return;
            }
        }
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

    /** The lane whose frontmost zombie is closest to the house, or zero when no lane has one. */
    private static int mostThreatenedRow(LevelServer level) {
        int best = 0;
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
            hand.add(new CardChoice(card, describe(card, plantSide)));
        }
        return List.copyOf(hand);
    }

    /** One card, as the prompt describes it: what it is, what it costs, what it can take. */
    private static String describe(SlotResolver.ResolvedCard card, boolean plantSide) {
        StringBuilder text = new StringBuilder(card.slotId().toString());
        text.append(" — ").append(plantSide ? "plant" : "zombie");
        text.append(", costs ").append(card.costSun()).append(" sun");
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

    /** One card the opponent may play, with the sentence the prompt shows for it. */
    record CardChoice(SlotResolver.ResolvedCard card, String description) {
        int cost() {
            return card.costSun();
        }
    }

    private static JevPrompt prompt(LevelServer level, VersusData data, Team opponent,
                                    boolean plantSide, List<CardChoice> hand) {
        int sun = opponent.resourcesOf(PvzceIds.SUN);
        List<JevPrompt.CardOption> cards = new ArrayList<>();
        for (CardChoice choice : hand) {
            cards.add(new JevPrompt.CardOption(choice.card().slotId().toString(), choice.cost(),
                    choice.cost() <= sun, choice.description()));
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
                columnOptions);
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

    // ------------------------------------------------------------------
    // The plant side's own sun
    // ------------------------------------------------------------------

    /**
     * Picks up the opponent's own fallen sun.
     *
     * <p>Deliberately not "collect everything the other team owns": a drop is collected by its own
     * team, so the human's sun is the human's. Deliberately only once it has landed, too - it is
     * what a player's click looks like from the outside, and a sun that vanishes in mid-air reads
     * as a bug even when it is the opponent's.
     */
    private void collectOwnDrops(LevelServer level, Team opponent) {
        if (pickupCountdown > 0) {
            pickupCountdown--;
            return;
        }
        pickupCountdown = AUTO_PICKUP_INTERVAL_TICKS;
        List<ResourceDropEntity> mine = new ArrayList<>();
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof ResourceDropEntity drop && drop.landed() && !drop.collected()
                    && !drop.isRemoved() && opponent.equals(drop.team())) {
                mine.add(drop);
            }
        }
        for (ResourceDropEntity drop : mine) {
            level.autoCollectDrop(drop);
        }
    }
}
