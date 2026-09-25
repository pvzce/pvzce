package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.network.packet.ResourceDeltaS2C;
import com.pvzce.server.level.LevelServer;

/**
 * The sun bank leaks: "阳光流失".
 *
 * <p>Every second the level takes a share of what the player is holding - a percentage rather than a
 * flat number, so the mutation stays relevant to a rich lawn and does not simply kill a poor one:
 * one percent of fifty sun is nothing, one percent of two thousand is a sunflower's worth every
 * second and a half.
 *
 * <p>Two limits, both of them what keep this a mutation rather than a loss condition:
 *
 * <ul>
 *   <li><b>It never takes the last sun.</b> The floor is one point per tick <em>when there is
 *       something to take</em>, and a bank at zero is left alone - a drain that could not empty the
 *       bank would be a slow countdown to a number the player cannot spend, and one that empties it
 *       stops the game rather than changing it.</li>
 *   <li><b>The client is told.</b> The bank is drawn from the server's total, so a drain that did not
 *       send the new value would show the player a number that is not what they have - the same rule
 *       every other resource change follows.</li>
 * </ul>
 *
 * <p>What it drains is <em>unspent</em> sun only: the mutation makes saving up a decision, which is
 * the one thing a lawn's economy never asks for. It cannot touch a card already paid for or a plant
 * already planted.
 */
final class SunDrainMutation implements Mutation, MutationManager.SaveHandle {
    /** How often the bank is taxed, in ticks. */
    private static final int DRAIN_INTERVAL_TICKS = 60;
    /** What one tax takes, as a share of what the player is holding. */
    private static final float DRAIN_SHARE = 0.01F;

    @Override
    public Identifier id() {
        return PvzceIds.MUTATION_SUN_DRAIN;
    }

    @Override
    public Object apply(LevelServer level, Mutation.Roll roll) {
        return new Applied();
    }

    /**
     * On a restore the tally comes back and nothing is taken.
     *
     * <p>The sun the mutation already took is gone - the bank is in the save - so taking a share on
     * load would be charging the player for the time the game was closed.
     */
    @Override
    public Object applyFromSave(LevelServer level, Mutation.Roll roll) {
        return pendingState;
    }

    @Override
    public void tick(LevelServer level, Mutation.Roll roll, Object state) {
        if (!(state instanceof Applied applied)) {
            return;
        }
        if (++applied.ticksSinceDrain < DRAIN_INTERVAL_TICKS) {
            return;
        }
        applied.ticksSinceDrain = 0;
        if (level.plantPlayer() == null) {
            return;
        }
        com.pvzce.server.Team team = level.plantPlayer().team();
        int bank = team.resourcesOf(PvzceIds.SUN);
        if (bank <= 0) {
            return;
        }
        int taken = Math.max(1, Math.round(bank * DRAIN_SHARE));
        taken = Math.min(taken, bank);
        team.consume(PvzceIds.SUN, taken);
        applied.drained += taken;
        level.send(new ResourceDeltaS2C(team.id().toString(), PvzceIds.SUN.toString(),
                team.resourcesOf(PvzceIds.SUN)));
    }

    @Override
    public CompoundTag saveState() {
        CompoundTag tag = new CompoundTag();
        if (savingState != null) {
            tag.putInt("TicksSinceDrain", savingState.ticksSinceDrain);
            tag.putInt("Drained", savingState.drained);
        }
        return tag;
    }

    @Override
    public void loadState(CompoundTag tag) {
        Applied applied = new Applied();
        if (tag != null) {
            applied.ticksSinceDrain = Math.max(0, tag.getInt("TicksSinceDrain"));
            applied.drained = Math.max(0, tag.getInt("Drained"));
        }
        this.pendingState = applied;
    }

    @Override
    public java.util.Optional<String> announcement(LevelServer level, Mutation.Roll roll,
                                                  Object state) {
        return java.util.Optional.of("阳光流失：存着的阳光会慢慢漏掉");
    }

    /** This activation's tally: how long since the last tax, and how much it has taken. */
    private static final class Applied {
        private int ticksSinceDrain;
        private int drained;
    }

    /** The state as it stands, set by the manager around {@link #saveState}. */
    private Applied savingState;
    /** What {@link #loadState} read, waiting for {@link #applyFromSave} to consume it. */
    private Applied pendingState;

    @Override
    public void savingState(Object state) {
        this.savingState = state instanceof Applied applied ? applied : null;
    }
}
