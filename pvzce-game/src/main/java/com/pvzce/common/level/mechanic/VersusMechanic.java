package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.VersusData;
import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.network.packet.ResourceDeltaS2C;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.Team;
import com.pvzce.server.ai.JevBrain;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.List;

/**
 * 对战: the mode where a human and a Jev-driven opponent share one lawn.
 *
 * <p>The level's data says what the matchup is ({@link VersusData}); this class runs it. Three
 * things happen here and nowhere else:
 *
 * <ul>
 *   <li><b>the economy</b> - both sides' opening sun, the zombie side's periodic payment, and the
 *       refund for a plant a zombie finishes eating. It is deliberately the same code for a human
 *       and for the opponent, so a level cannot be a different game depending on which side the
 *       player picked;</li>
 *   <li><b>the race</b> - the sun the plant side has <em>picked up</em>, counted from the
 *       collection hook, and the win when it reaches the goal;</li>
 *   <li><b>the opponent</b> - one call per tick into {@link JevBrain}, which owns the clock, the
 *       request and the fallback policy. This class never talks to Jev itself; it hands the brain
 *       the level, the mode's numbers and the list of cards the opponent may play.</li>
 * </ul>
 *
 * <p>The sun total is counted from pickups rather than from the wallet for a reason that is worth
 * writing down: a wallet goes down when a card is spent, and "collect 5000 sun" is a statement
 * about income, not about the balance at one instant. It also means the goal cannot be reached by
 * handing the plant side a bigger opening purse, which is what makes the three levels a difficulty
 * curve rather than three numbers.
 */
public final class VersusMechanic implements LevelMechanic<VersusData> {
    /** Save key for this mode's own run state. */
    private static final String KEY_RUN = "Versus";

    @Override
    public MapCodec<VersusData> codec() {
        return VersusData.MAP_CODEC;
    }

    @Override
    public List<String> validate(LevelDef def, VersusData data) {
        List<String> errors = new ArrayList<>(data.validate());
        // Both sides have to exist: the mode pays, seats and asks about both of them, and a level
        // that forgot one would fail somewhere deep in the tick rather than here.
        if (!hasTeam(def, PvzceIds.PLANT_TEAM)) {
            errors.add("versus needs a pvzce:plant_team in the level's teams list");
        }
        if (!hasTeam(def, PvzceIds.ZOMBIE_TEAM)) {
            errors.add("versus needs a pvzce:zombie_team in the level's teams list");
        }
        if (!def.waves().isEmpty()) {
            errors.add("versus level declares " + def.waves().size()
                    + " waves, but its zombies come from the opposing side, not from the wave table");
        }
        errors.addAll(unknownCards(data.plantCards(), "plant card"));
        errors.addAll(unknownCards(data.zombieCards(), "zombie card"));
        for (Identifier card : data.plantCards()) {
            if (isZombieCard(card)) {
                errors.add("versus lists '" + card + "' among the plant cards, but it is a zombie card");
            }
        }
        for (Identifier card : data.zombieCards()) {
            if (!isZombieCard(card)) {
                errors.add("versus lists '" + card + "' among the zombie cards, but it is not one");
            }
        }
        return errors;
    }

    private static boolean hasTeam(LevelDef def, Identifier team) {
        return def.teams().stream().anyMatch(t -> team.equals(t.id()));
    }

    /** True when this id resolves to a card the zombie side holds. */
    public static boolean isZombieCard(Identifier card) {
        SlotResolver.ResolvedCard resolved = SlotResolver.resolve(card).orElse(null);
        return resolved != null && resolved.kind() == com.pvzce.common.core.Slot.Kind.ZOMBIE;
    }

    private static List<String> unknownCards(List<Identifier> cards, String what) {
        List<String> errors = new ArrayList<>();
        for (Identifier card : cards) {
            if (card != null && SlotResolver.resolve(card).isEmpty()) {
                errors.add("versus names an unknown " + what + " '" + card + "'");
            }
        }
        return errors;
    }

    @Override
    public List<FieldSpec> editorFields() {
        return VersusData.editorFields();
    }

    @Override
    public void onLevelCreated(LevelServer level, VersusData data) {
        Team plant = level.team(PvzceIds.PLANT_TEAM);
        Team zombie = level.team(PvzceIds.ZOMBIE_TEAM);
        // The opening purses go through the same credit as every later payment, so a versus level
        // is played the same way by a human and by the opponent.
        // The plant side's own sun is collectible by definition of the mode: a level's
        // `unlock_resources` reaches the human's team only, and on a versus level the plant side may
        // be the opponent - which cannot click, and would otherwise be unable to pick up the
        // resource its win condition is counted in.
        // Both sides collect on their own: an AI cannot click, and a match where one player clicks
        // while the other is handed its sun is not the same match. The loop and the delay are the
        // auto-pickup buff's, so a drop a player would have clicked is worth exactly what an
        // auto-collected one is.
        level.setBothSidesCollect(true);
        if (plant != null) {
            plant.unlockResource(PvzceIds.SUN);
        }
        if (zombie != null) {
            zombie.unlockResource(PvzceIds.SUN);
        }
        if (plant != null && data.plantInitialSun() > 0) {
            plant.addResource(PvzceIds.SUN, data.plantInitialSun());
        }
        if (zombie != null && data.zombieInitialSun() > 0) {
            zombie.addResource(PvzceIds.SUN, data.zombieInitialSun());
        }
        Run run = run(level);
        run.incomeCountdown = Math.max(1, data.zombieIncomeTicks());
        run.collected = 0;
    }

    @Override
    public void tick(LevelServer level, VersusData data) {
        Run run = run(level);
        payTheZombieSide(level, data, run);
        level.jevBrain().tick(level, data);
        if (data.races() && run.collected >= data.sunGoal()) {
            level.plantGoalReached();
        }
        // The opponent's wallet is part of "has anything the HUD draws changed", and it is the one
        // field that moves on its own clock: it is paid every `zombie_income_ticks` whether or not
        // it decides to spend, so a payload sent only on a decision would leave the panel showing
        // one number while the pressure behind it grew. (The build window's countdown was the same
        // mistake in the version before this one.)
        int opponentSun = opponentSun(level);
        // The strategist's plan and whether it is thinking are drawn by the F3 overlay only, and they
        // are the two fields that change on the commander's own clock - once a minute, plus a blink
        // when a request goes out. They ride the same payload because they are the same panel.
        String plan = level.jevBrain().commanderPlan();
        boolean busy = level.jevBrain().commanderBusy();
        if (run.collected != run.sentCollected || opponentSun != run.sentOpponentSun
                || !plan.equals(run.sentCommanderPlan) || busy != run.sentCommanderBusy
                || level.jevBrain().dirty()) {
            run.sentCollected = run.collected;
            run.sentOpponentSun = opponentSun;
            run.sentCommanderPlan = plan;
            run.sentCommanderBusy = busy;
            level.jevBrain().clearDirty();
            sendState(level, data, null);
        }
    }

    /** The zombie side's standing income: no producers, so the level pays it on a clock. */
    private void payTheZombieSide(LevelServer level, VersusData data, Run run) {
        if (data.zombieIncomeSun() <= 0 || data.zombieIncomeTicks() <= 0) {
            return;
        }
        if (--run.incomeCountdown > 0) {
            return;
        }
        run.incomeCountdown = data.zombieIncomeTicks();
        Team zombie = level.team(PvzceIds.ZOMBIE_TEAM);
        if (zombie == null) {
            return;
        }
        zombie.addResource(PvzceIds.SUN, data.zombieIncomeSun());
        publishIfHuman(level, zombie);
    }

    @Override
    public void onPlantConsumed(LevelServer level, VersusData data, Team eater, PlantEntity plant) {
        if (eater == null || plant == null || data.eatRefundPercent() <= 0) {
            return;
        }
        if (plant.team() == eater) {
            return;
        }
        int cost = plant.def().cost().amountOf(PvzceIds.SUN);
        int refund = Math.round(cost * (data.eatRefundPercent() / 100F));
        if (refund <= 0) {
            return;
        }
        eater.addResource(PvzceIds.SUN, refund);
        publishIfHuman(level, eater);
    }

    @Override
    public void onResourceCollected(LevelServer level, VersusData data, Team team,
                                    Identifier resource, int amount) {
        if (!PvzceIds.SUN.equals(resource) || team == null
                || !PvzceIds.PLANT_TEAM.equals(team.id())) {
            return;
        }
        run(level).collected += Math.max(0, amount);
    }

    /**
     * Tells the human's HUD that their wallet moved.
     *
     * <p>Only for the human's own team: the client draws one sun counter, and a delta for the
     * opponent's team would be a packet about a number nobody is looking at. The opponent's own
     * balance still moves on the server, which is what its decisions are made from.
     */
    private static void publishIfHuman(LevelServer level, Team team) {
        if (team.id().equals(level.humanTeamId())) {
            level.send(new ResourceDeltaS2C(team.id().toString(), PvzceIds.SUN.toString(),
                    team.resourcesOf(PvzceIds.SUN)));
        }
    }

    @Override
    public void collectSave(LevelServer level, VersusData data, CompoundTag root) {
        Run run = run(level);
        CompoundTag tag = new CompoundTag();
        tag.putInt("Collected", run.collected);
        tag.putInt("Income", run.incomeCountdown);
        root.put(KEY_RUN, tag);
    }

    @Override
    public void applySave(LevelServer level, VersusData data, CompoundTag root) {
        Run run = run(level);
        if (!root.contains(KEY_RUN)) {
            return;
        }
        CompoundTag tag = root.getCompound(KEY_RUN);
        run.collected = Math.max(0, tag.getInt("Collected"));
        run.sentCollected = run.collected;
        run.sentOpponentSun = Integer.MIN_VALUE;
        run.sentCommanderPlan = "";
        if (tag.contains("Income")) {
            run.incomeCountdown = Math.max(0, tag.getInt("Income"));
        }
    }

    @Override
    public void sendState(LevelServer level, VersusData data, LevelServer.ServerBridge bridge) {
        Run run = run(level);
        JevBrain brain = level.jevBrain();
        level.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_VERSUS, State.CODEC,
                new State(data.sunGoal(), run.collected, opponentSide(level), brain.status().ordinal(),
                        brain.lastCardId(), brain.lastRow(), brain.lastColumn(),
                        opponentSun(level), opponentHand(level), brain.commanderPlan(),
                        brain.commanderBusy())));
    }

    /** The side the opponent plays: the one the human is not on. */
    public static String opponentSide(LevelServer level) {
        return PvzceIds.ZOMBIE_TEAM.equals(level.humanTeamId()) ? "plant" : "zombie";
    }

    /** This mode's run state, created on first use and kept per level. */
    public static Run run(LevelServer level) {
        return level.mechanicState(PvzceIds.MECHANIC_VERSUS, Run::new);
    }

    /**
     * What the HUD needs to draw the race and the opponent's last move.
     *
     * <p>Numbers and ids rather than a sentence: the client owns the words (they are language keys),
     * and the server owns the facts. {@code opponentStatus} is an ordinal of {@link JevBrain.Status}
     * for the same reason - it is a decision the client renders, not a message it forwards.
     */
    public record State(int sunGoal, int sunCollected, String opponentSide, int opponentStatus,
                        String lastCardId, int lastRow, int lastColumn, int opponentSun,
                        List<String> opponentHand, String commanderPlan, boolean commanderBusy) {
        public State {
            opponentHand = List.copyOf(opponentHand);
        }

        public static final com.pvzce.common.network.PacketStruct.Codec<State> CODEC =
                com.pvzce.common.network.PacketStruct.<State>builder()
                        .field(State::sunGoal, com.pvzce.common.network.PacketByteBuf::writeInt,
                                com.pvzce.common.network.PacketByteBuf::readInt)
                        .field(State::sunCollected, com.pvzce.common.network.PacketByteBuf::writeInt,
                                com.pvzce.common.network.PacketByteBuf::readInt)
                        .field(State::opponentSide, com.pvzce.common.network.PacketByteBuf::writeString,
                                com.pvzce.common.network.PacketByteBuf::readString)
                        .field(State::opponentStatus, com.pvzce.common.network.PacketByteBuf::writeInt,
                                com.pvzce.common.network.PacketByteBuf::readInt)
                        .field(State::lastCardId, com.pvzce.common.network.PacketByteBuf::writeString,
                                com.pvzce.common.network.PacketByteBuf::readString)
                        .field(State::lastRow, com.pvzce.common.network.PacketByteBuf::writeInt,
                                com.pvzce.common.network.PacketByteBuf::readInt)
                        .field(State::lastColumn, com.pvzce.common.network.PacketByteBuf::writeInt,
                                com.pvzce.common.network.PacketByteBuf::readInt)
                        .field(State::opponentSun, com.pvzce.common.network.PacketByteBuf::writeInt,
                                com.pvzce.common.network.PacketByteBuf::readInt)
                        .stringList(State::opponentHand)
                        .field(State::commanderPlan,
                                com.pvzce.common.network.PacketByteBuf::writeString,
                                com.pvzce.common.network.PacketByteBuf::readString)
                        .field(State::commanderBusy,
                                com.pvzce.common.network.PacketByteBuf::writeBoolean,
                                com.pvzce.common.network.PacketByteBuf::readBoolean)
                        .build(values -> new State((Integer) values.get(0), (Integer) values.get(1),
                                (String) values.get(2), (Integer) values.get(3), (String) values.get(4),
                                (Integer) values.get(5), (Integer) values.get(6), (Integer) values.get(7),
                                castStrings(values.get(8)), (String) values.get(9),
                                (Boolean) values.get(10)));
    }

    /** The opponent's sun, so the other player can watch the pressure build. */
    private static int opponentSun(LevelServer level) {
        Team opponent = com.pvzce.server.ai.JevBrain.opponentTeam(level);
        return opponent == null ? 0 : opponent.resourcesOf(PvzceIds.SUN);
    }

    /** The cards the opponent may play, in the level's own order. */
    private static List<String> opponentHand(LevelServer level) {
        com.pvzce.api.content.VersusData data = level.versusData();
        Team opponent = com.pvzce.server.ai.JevBrain.opponentTeam(level);
        if (data == null || opponent == null) {
            return List.of();
        }
        List<String> hand = new ArrayList<>();
        for (Identifier card : data.cardsFor(opponent.id())) {
            hand.add(card.toString());
        }
        return List.copyOf(hand);
    }

    @SuppressWarnings("unchecked")
    private static List<String> castStrings(Object value) {
        return (List<String>) value;
    }

    /**
     * This mode's own run state for one level.
     *
     * <p>Held by the level (through {@code mechanicState}) rather than by the mechanic, because a
     * mechanic is a singleton shared by every level that declares it and two versus levels must not
     * count each other's sun.
     */
    public static final class Run {
        /** Sun the plant side has picked up this run; the race's progress. */
        public int collected;
        /** The last value sent to the client, so an unchanged total is not re-sent every tick. */
        public int sentCollected = -1;
        /** The opponent's wallet as the client last heard it; it moves on its own clock. */
        public int sentOpponentSun = Integer.MIN_VALUE;
        /** The strategist's plan as the client last heard it, and whether it was thinking. */
        public String sentCommanderPlan = "";
        public boolean sentCommanderBusy;
        /** Ticks until the zombie side's next payment. */
        public int incomeCountdown;
    }
}
