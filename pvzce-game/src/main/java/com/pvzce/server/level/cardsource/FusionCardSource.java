package com.pvzce.server.level.cardsource;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.Slot;
import com.pvzce.server.level.LevelServer;

import java.util.List;

/** Fusion produces ground packets, never slots. */
public final class FusionCardSource implements CardSource {
    public FusionCardSource(Context context) { context.player().replaceSlots(List.of()); }
    @Override public List<Slot> slots() { return List.of(); }
    @Override public void tick(LevelServer level, LevelServer.ServerBridge bridge, int tick) { }
    @Override public boolean spend(LevelServer level, LevelServer.ServerBridge bridge, Slot slot, PlantDef plant) { return false; }
    @Override public void afterSpend(LevelServer level, LevelServer.ServerBridge bridge, Slot slot) { }
    @Override public boolean receiveCard(LevelServer level, LevelServer.ServerBridge bridge, Identifier card) { return false; }
    @Override public boolean dealsItsOwnCards() { return true; }
    @Override public String kind() { return "fusion"; }
}
