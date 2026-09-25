package com.pvzce.server.level.cardsource;

import com.pvzce.api.content.LevelBelt;
import com.pvzce.api.content.PlantDef;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.level.mechanic.ConveyorMechanic;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.PvzcePlayer;
import com.pvzce.common.core.Slot;
import com.pvzce.server.level.ConveyorBelt;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A conveyor belt as the level's card source: the bar is a projection of the belt, cards
 * are free, and there are no cooldowns.
 *
 * <p>The queue and its clock stay in {@link ConveyorBelt}; this class is the adapter that
 * makes it a {@link CardSource}, and it is where the three things a belt does differently
 * live:
 *
 * <ul>
 *   <li>Spending a card takes it off the belt by id and charges nothing. The id is the
 *       card's own, never its position, so a click that raced the belt names a card that
 *       is no longer there and is refused instead of planting whatever slid forward.</li>
 *   <li>Syncing replaces the whole bar, because a spent card has to leave the client's bar
 *       and an upsert cannot say that.</li>
 *   <li>Cards print no price at all. That distinction lives in {@code SlotInfo.NO_PRICE}.</li>
 * </ul>
 */
public final class BeltCardSource implements CardSource {
    public static final String KIND = "conveyor";

    private final PvzcePlayer player;
    private final ConveyorBelt belt;
    private final Random random;

    public BeltCardSource(Context context, LevelBelt def) {
        this.player = context.player();
        this.random = context.random();
        this.belt = new ConveyorBelt(def, this.random);
        rebuildBar();
    }

    /** The belt itself, for tests and for the save block. */
    public ConveyorBelt belt() {
        return belt;
    }

    @Override
    public List<Slot> slots() {
        return player.slots();
    }

    @Override
    public void tick(LevelServer level, LevelServer.ServerBridge bridge, int tickCount) {
        belt.tick(random);
        if (belt.isChanged()) {
            belt.clearChanged();
            rebuildBar();
            bridge.send(syncPacket(level));
        }
    }

    @Override
    public boolean spend(LevelServer level, LevelServer.ServerBridge bridge, Slot slot, PlantDef plant) {
        // Free, and no cooldown: a belt card is handed to the player rather than bought.
        if (belt.take(slot.index()) == null) {
            bridge.send(new ServerMessageS2C("这张卡已经不在传送带上了。"));
            return false;
        }
        return true;
    }

    @Override
    public void afterSpend(LevelServer level, LevelServer.ServerBridge bridge, Slot slot) {
        belt.clearChanged();
        rebuildBar();
        bridge.send(syncPacket(level));
    }

    @Override
    public void save(CompoundTag root) {
        // Belt contents are part of the run: a resumed conveyor level must come back
        // holding the cards it was holding, not a freshly rolled batch.
        root.put("Belt", belt.save());
    }

    @Override
    public void restore(LevelServer level, CompoundTag root) {
        belt.restore(root.getCompound("Belt"));
        rebuildBar();
    }

    @Override
    public void collectSave(CompoundTag target) {
        // The queue, the ids it has handed out and the clock until the next card. The *pool* is
        // not saved: it is derived from the player's backpack and this belt's own shape, so
        // rebuilding it from the same inputs is what makes a resumed belt carry the rules the
        // player was playing under (a smaller backpack yields a smaller pool, which is correct).
        target.put("Belt", belt.save());
    }

    @Override
    public void applySave(LevelServer level, CompoundTag source) {
        CompoundTag saved = source == null ? null : source.getCompound("Belt");
        if (saved == null) {
            // Nothing to put back (a save from a build that did not write this): the freshly built
            // belt stands, which is a belt that has just been dealt rather than an empty bar.
            return;
        }
        belt.restore(saved);
        rebuildBar();
    }

    @Override
    public boolean dealsItsOwnCards() {
        return true;
    }

    @Override
    public String kind() {
        return KIND;
    }

    /** Rebuilds the player's bar from the belt; the bar is a projection, not a second copy. */
    private void rebuildBar() {
        List<Slot> rebuilt = new ArrayList<>();
        for (ConveyorBelt.Card card : belt.cards()) {
            SlotResolver.ResolvedCard resolved = SlotResolver.resolve(card.cardId()).orElse(null);
            if (resolved == null) {
                continue;
            }
            // No cooldown at all: a belt card is handed to the player, so there is nothing
            // to recharge and the bar has no clock of its own.
            rebuilt.add(new Slot(card.id(), resolved.kind(), resolved.content(), 0, 0,
                    Slot.UNLIMITED_USES, 0));
        }
        player.replaceSlots(rebuilt);
    }

    private MechanicSyncS2C syncPacket(LevelServer level) {
        return MechanicSyncS2C.of(PvzceIds.MECHANIC_CONVEYOR, ConveyorMechanic.BarState.CODEC,
                new ConveyorMechanic.BarState(level.slotInfos()));
    }
}
