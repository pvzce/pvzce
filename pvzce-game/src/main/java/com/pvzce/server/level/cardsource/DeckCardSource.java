package com.pvzce.server.level.cardsource;

import com.pvzce.api.content.PlantDef;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.network.packet.ResourceDeltaS2C;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.common.network.packet.SlotSyncS2C;
import com.pvzce.server.PvzcePlayer;
import com.pvzce.common.core.Slot;
import com.pvzce.server.level.LevelServer;

import java.util.List;

/**
 * The ordinary card bar: the level's own cards plus the player's picks, paid for with sun,
 * gated by cooldowns.
 *
 * <p>The behaviour here was {@code LevelServer.syncSlots}' else-branch and the tail of
 * {@code placePlantInternal}; it moved rather than changed. Two details are worth keeping
 * in mind because they are the reason that code looks the way it does:
 *
 * <ul>
 *   <li>Only a card whose cooldown is <em>running</em> streams every tick - the bar
 *       animates while it recharges - while ready cards get a keepalive every 20 ticks.
 *       Sending every card every tick was 780 packets per second for values that were not
 *       changing.</li>
 *   <li>The price is read from the card's own {@code costSun}, falling back to the plant
 *       definition, because a slot's price and the content's price may differ.</li>
 * </ul>
 */
public final class DeckCardSource implements CardSource {
    public static final String KIND = "deck";

    private final PvzcePlayer player;

    public DeckCardSource(Context context) {
        this.player = context.player();
        // A freshly built bar is ready to use; the cooldown starts when a card is spent.
        this.player.replaceSlots(PvzcePlayer.deckSlots(
                context.selectedSlots().isEmpty() ? context.def().slots() : context.selectedSlots()));
    }

    @Override
    public List<Slot> slots() {
        return player.slots();
    }

    @Override
    public void tick(LevelServer level, LevelServer.ServerBridge bridge, int tickCount) {
        for (Slot slot : player.slots()) {
            int before = slot.cooldownLeft();
            slot.tick();
            boolean animating = before > 0;
            boolean justBecameReady = before > 0 && slot.cooldownLeft() == 0;
            if (animating || justBecameReady || tickCount % 20 == 0) {
                bridge.send(new SlotSyncS2C(level.toSlotInfo(slot)));
            }
        }
    }

    @Override
    public boolean spend(LevelServer level, LevelServer.ServerBridge bridge, Slot slot, PlantDef plant) {
        int cost = slot.costSun() > 0 ? slot.costSun() : plant.cost().amountOf(PvzceIds.SUN);
        if (!player.team().consume(PvzceIds.SUN, cost)) {
            bridge.send(new ServerMessageS2C("阳光不足！"));
            return false;
        }
        // The card's cooldown as this level charges it, which is the card's own number scaled
        // by pvzce:seed_cooldown_multiplier. Both the charge and the bar's recharge come from
        // the same call, so a level that recharges cards faster cannot end up drawing the
        // sweep over a longer wait than the one the player actually has.
        slot.startCooldown(level.effectiveCooldownTicks(slot));
        return true;
    }

    @Override
    public void afterSpend(LevelServer level, LevelServer.ServerBridge bridge, Slot slot) {
        bridge.send(new ResourceDeltaS2C(player.team().id().toString(), PvzceIds.SUN.toString(),
                player.team().resourcesOf(PvzceIds.SUN)));
        bridge.send(new SlotSyncS2C(level.toSlotInfo(slot)));
    }

    @Override
    public boolean dealsItsOwnCards() {
        return false;
    }

    @Override
    public String kind() {
        return KIND;
    }
}
