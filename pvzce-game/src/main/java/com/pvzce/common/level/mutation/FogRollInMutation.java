package com.pvzce.common.level.mutation;

import com.pvzce.api.content.FogData;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.server.level.LevelServer;

/**
 * The right-hand side of the board goes dark: "迷雾降临".
 *
 * <p>World 4's fog, rolled onto a lawn that never declared any. What the mutation contributes is one
 * number - where the boundary stands - and the whole of the rest is the level's own mechanic:
 *
 * <ul>
 *   <li>it installs {@code pvzce:fog} on this level ({@code LevelServer.installMechanic}), so the
 *       mechanic's own tick keeps republishing the span as the fog-retreat buff and the lamps the
 *       player plants change it. A mutation that pushed its own state packet once would leave a
 *       plantern lighting nothing;</li>
 *   <li>the span itself goes through {@code LevelServer.setFogOverride}, which is the one place
 *       "how much fog does this board have" is answered.</li>
 * </ul>
 *
 * <p>Nothing in the simulation reads it. The zombies behind the boundary walk, bite and are bitten
 * exactly as before - the fog is what the player can see, which is the reading the player asked for
 * when the mechanic was designed.
 */
final class FogRollInMutation implements Mutation, MutationManager.SaveHandle {
    /**
     * Where the boundary stands, as a fraction of the board's width.
     *
     * <p>A little past the middle: the near half stays playable, and the far half - where zombies
     * appear - is dark. Half of a nine-column lawn would put the line at 4.5, which is the world 4
     * levels' own opening value.
     */
    private static final float START_FRACTION = 0.5F;
    /** How dark it gets at the far edge. The same ceiling world 4 uses. */
    private static final float MAX_ALPHA = 0.94F;

    @Override
    public Identifier id() {
        return PvzceIds.MUTATION_FOG_ROLL_IN;
    }

    @Override
    public MutationEffects clientEffects() {
        // The client builds its world overlays once per level, so "there is fog now" is what tells
        // it to build them again. The span itself travels on the mechanic's own sync.
        return MutationEffects.FOG;
    }

    @Override
    public Object apply(LevelServer level, Mutation.Roll roll) {
        float start = Math.max(0F, level.width() * START_FRACTION);
        FogData fog = new FogData(start, level.width(), MAX_ALPHA);
        level.setFogOverride(fog);
        // Installed rather than simulated: the level's own fog mechanic is what republishes the
        // span every ten ticks, so a lamp lit later, or the retreat buff, is picked up by the
        // client without this mutation ever hearing about it. The reading is checked because a
        // level that declared fog already owns it - the override is the mutation's half.
        boolean installed = level.installMechanic(
                TypedMechanic.of(PvzceIds.MECHANIC_FOG, fog));
        // Sent whether or not the mechanic was already there: installing it fills the level's own
        // state slot without sending (that is what a declared fog does, and there is nobody to tell
        // at that point), and this mutation does its work mid-run with a client watching.
        com.pvzce.common.level.mechanic.FogMechanic.publish(level, true);
        return new Applied(installed);
    }

    /**
     * Nothing is put back on a restore.
     *
     * <p>The span is a fold of the override and the level's own block, and the override is not in
     * the save - so a resumed run has to reinstall both halves. That is exactly what
     * {@link #apply} does, which is why the default ({@code applyFromSave = apply}) is right here
     * and the saved state is only used to remember whether the mechanic was ours to remove.
     */
    @Override
    public Object applyFromSave(LevelServer level, Mutation.Roll roll) {
        boolean installed = pendingInstalled;
        Object state = apply(level, roll);
        return state instanceof Applied applied ? new Applied(applied.installed() && installed)
                : state;
    }

    @Override
    public com.pvzce.common.nbt.CompoundTag saveState() {
        com.pvzce.common.nbt.CompoundTag tag = new com.pvzce.common.nbt.CompoundTag();
        tag.putInt("Installed", savingState != null && savingState.installed() ? 1 : 0);
        return tag;
    }

    @Override
    public void loadState(com.pvzce.common.nbt.CompoundTag tag) {
        // No block at all means a save from a build that did not record it, and then the mechanic
        // is assumed to be the mutation's own: leaving one installed on a lawn that never declared
        // fog is visible, while removing one a level declared would break that level's own fog.
        this.pendingInstalled = tag == null || !tag.contains("Installed")
                || tag.getInt("Installed") != 0;
    }

    @Override
    public void revert(LevelServer level, Mutation.Roll roll, Object state) {
        level.setFogOverride(null);
        if (state instanceof Applied applied && applied.installed()
                && level.hasMechanic(PvzceIds.MECHANIC_FOG)) {
            level.removeMechanic(PvzceIds.MECHANIC_FOG);
        }
        // The level's own fog (a world 4 level) is untouched by the line above: the mechanic was
        // already there and `installed` is false. Its span goes back to the level's block because
        // the override is cleared - and the mechanic republishes that on its next check.
        com.pvzce.common.level.mechanic.FogMechanic.publish(level, true);
    }

    @Override
    public java.util.Optional<String> announcement(LevelServer level, Mutation.Roll roll,
                                                  Object state) {
        return java.util.Optional.of("迷雾降临：右边看不清了，僵尸走到亮处才会现形");
    }

    /** What this activation remembers: whether the fog mechanic was installed by it. */
    private record Applied(boolean installed) {
    }

    /** The state as it stands, set by the manager around {@link #saveState}. */
    private Applied savingState;
    /** What {@link #loadState} read, waiting for {@link #applyFromSave} to consume it. */
    private boolean pendingInstalled = true;

    @Override
    public void savingState(Object state) {
        this.savingState = state instanceof Applied applied ? applied : null;
    }
}
