package com.pvzce.server.level.cardsource;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.Slot;
import com.pvzce.common.PvzceIds;
import com.pvzce.server.PvzcePlayer;
import com.pvzce.server.level.LevelServer;

import java.util.List;

/** Fusion produces ground packets and keeps the ordinary shovel as its only tool slot. */
public final class FusionCardSource implements CardSource {
    private final PvzcePlayer player;
    public FusionCardSource(Context context) {
        player = context.player();
        player.replaceSlots(List.of(new Slot(0, Slot.Kind.TOOL, PvzceIds.id("shovel"), 0, 0, Slot.UNLIMITED_USES, 0)));
    }
    @Override public List<Slot> slots() { return player.slots(); }
    @Override public void tick(LevelServer level, LevelServer.ServerBridge bridge, int tick) { }
    @Override public boolean spend(LevelServer level, LevelServer.ServerBridge bridge, Slot slot, PlantDef plant) { return false; }
    @Override public void afterSpend(LevelServer level, LevelServer.ServerBridge bridge, Slot slot) { }
    @Override public boolean receiveCard(LevelServer level, LevelServer.ServerBridge bridge, Identifier card) { return false; }
    @Override public boolean dealsItsOwnCards() { return true; }
    @Override public String kind() { return "fusion"; }
}
