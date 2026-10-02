package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.VersusData;
import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
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
        run.zombieStartCountdown = Math.max(0, data.zombieStartTicks());
        run.collected = 0;
    }

    @Override
    public void tick(LevelServer level, VersusData data) {
        Run run = run(level);
        // The build window first: while it is open the zombie side is paid nothing and asked
        // nothing, so the plant side's opening is the amount the level gave it plus the sky.
        if (run.zombieStartCountdown > 0) {
            run.zombieStartCountdown--;
        } else {
            payTheZombieSide(level, data, run);
        }
        level.jevBrain().tick(level, data);
        if (data.races() && run.collected >= data.sunGoal()) {
            level.plantGoalReached();
        }
        // The countdown is part of "has anything the HUD draws changed": it ticks every second of a
        // window in which nothing else happens at all, so a payload sent only on a collection left
        // the client showing the window's opening number for its whole length. The screenshot of
        // the first smoke run is what showed it - thirty seconds on the clock, twenty seconds in.
        int secondsLeft = countdownSeconds(run);
        if (run.collected != run.sentCollected || secondsLeft != run.sentCountdownSeconds
                || level.jevBrain().dirty()) {
            run.sentCollected = run.collected;
            run.sentCountdownSeconds = secondsLeft;
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
        tag.putInt("ZombieStart", run.zombieStartCountdown);
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
        run.sentCountdownSeconds = -1;
        if (tag.contains("Income")) {
            run.incomeCountdown = Math.max(0, tag.getInt("Income"));
        }
        if (tag.contains("ZombieStart")) {
            run.zombieStartCountdown = Math.max(0, tag.getInt("ZombieStart"));
        }
    }

    @Override
    public void sendState(LevelServer level, VersusData data, LevelServer.ServerBridge bridge) {
        Run run = run(level);
        JevBrain brain = level.jevBrain();
        level.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_VERSUS, State.CODEC,
                new State(data.sunGoal(), run.collected, opponentSide(level), brain.status().ordinal(),
                        brain.lastCardId(), brain.lastRow(), brain.lastColumn(),
                        run.zombieStartCountdown)));
    }

    /**
     * True while the zombie side is still held back by the build window.
     *
     * <p>Asked by the opponent before it acts, and by nothing else: the window is one fact about
     * the run, so the mechanic that counts it down is the only thing that answers it.
     */
    public static boolean zombieSideIsWaiting(LevelServer level) {
        return run(level).zombieStartCountdown > 0;
    }

    /** The side the opponent plays: the one the human is not on. */
    public static String opponentSide(LevelServer level) {
        return PvzceIds.ZOMBIE_TEAM.equals(level.humanTeamId()) ? "plant" : "zombie";
    }

    /** Seconds the build window has left, rounded up, or {@code -1} when it is over. */
    private static int countdownSeconds(Run run) {
        if (run.zombieStartCountdown <= 0) {
            return -1;
        }
        return (run.zombieStartCountdown + PvzceConstants.TICKS_PER_SECOND - 1)
                / PvzceConstants.TICKS_PER_SECOND;
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
                        String lastCardId, int lastRow, int lastColumn, int zombieStartCountdown) {
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
                        .field(State::zombieStartCountdown,
                                com.pvzce.common.network.PacketByteBuf::writeInt,
                                com.pvzce.common.network.PacketByteBuf::readInt)
                        .build(values -> new State((Integer) values.get(0), (Integer) values.get(1),
                                (String) values.get(2), (Integer) values.get(3), (String) values.get(4),
                                (Integer) values.get(5), (Integer) values.get(6), (Integer) values.get(7)));
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
        /** Ticks until the zombie side's next payment. */
        public int incomeCountdown;
        /** Ticks left of the build window; the zombie side is idle while this is positive. */
        public int zombieStartCountdown;
        /** The countdown in seconds as the client last heard it, so a changing clock is sent. */
        public int sentCountdownSeconds = -1;
    }
}
