package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PortalData;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.nbt.Tag;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bidirectional portals for zombies (including balloons), straight shots and running mowers. */
public final class PortalMechanic implements LevelMechanic<PortalData> {

    public record State(List<PortalData.Pair> pairs) {
        public static final PacketStruct.Codec<State> CODEC = PacketStruct.<State>builder()
                .list(State::pairs, (p, buf) -> {
                    buf.writeInt(p.ax()); buf.writeInt(p.ay());
                    buf.writeInt(p.bx()); buf.writeInt(p.by());
                }, buf -> new PortalData.Pair(buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt()))
                .build(values -> new State(castPairs(values.get(0))));

        public State { pairs = List.copyOf(pairs); }
        @SuppressWarnings("unchecked")
        private static List<PortalData.Pair> castPairs(Object value) {
            return (List<PortalData.Pair>) value;
        }
    }

    private static final class Rig {
        final List<PortalData.Pair> pairs;
        final Map<Integer, Integer> immunityUntil = new HashMap<>();
        int ticks;
        boolean dirty = true;
        /** The leftmost column a relocation may use; 0 is the whole board. */
        final int relocateMinX;
        Rig(PortalData data) {
            pairs = new ArrayList<>(data.pairs());
            relocateMinX = data.relocateMinX();
            ticks = data.initialRelocateTicks();
        }
    }

    private static Rig rig(LevelServer level, PortalData data) {
        return level.mechanicState(PvzceIds.MECHANIC_PORTAL, () -> new Rig(data));
    }

    @Override public MapCodec<PortalData> codec() { return PortalData.MAP_CODEC; }

    @Override public void onLevelCreated(LevelServer level, PortalData data) { rig(level, data); }

    @Override
    public void tick(LevelServer level, PortalData data) {
        Rig rig = rig(level, data);
        rig.immunityUntil.values().removeIf(until -> until <= level.tickCount());
        if (data.relocateIntervalTicks() > 0) {
            if (--rig.ticks == PvzceConstants.PORTAL_WARNING_TICKS) {
                level.send(new com.pvzce.common.network.packet.ServerMessageS2C("传送门即将换位！"));
            }
            if (rig.ticks <= 0) {
                relocate(level, rig);
                rig.ticks = data.relocateIntervalTicks();
            }
        }
        if (rig.dirty) {
            rig.dirty = false;
            level.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_PORTAL, State.CODEC, new State(rig.pairs)));
        }
    }

    public record Exit(float x, int row) {}

    /** Crossing after movement, before collision; a fast shot cannot skip a portal. */
    public static Exit cross(LevelServer level, int id, float fromX, float toX, int row) {
        Rig rig = level.mechanicStateOrNull(PvzceIds.MECHANIC_PORTAL, Rig.class);
        if (rig == null || rig.immunityUntil.getOrDefault(id, 0) > level.tickCount() || fromX == toX) {
            return null;
        }
        boolean right = toX > fromX;
        PortalData.Pair nearest = null;
        boolean aEnd = false;
        float distance = Float.MAX_VALUE;
        for (PortalData.Pair p : rig.pairs) {
            for (int end = 0; end < 2; end++) {
                int x = end == 0 ? p.ax() : p.bx();
                int y = end == 0 ? p.ay() : p.by();
                float centre = x + 0.5F;
                boolean crossed = right ? fromX < centre && toX >= centre
                        : fromX > centre && toX <= centre;
                if (y == row && crossed && Math.abs(centre - fromX) < distance) {
                    nearest = p; aEnd = end == 0; distance = Math.abs(centre - fromX);
                }
            }
        }
        if (nearest == null) return null;
        int x = aEnd ? nearest.bx() : nearest.ax();
        int y = aEnd ? nearest.by() : nearest.ay();
        rig.immunityUntil.put(id, level.tickCount() + PvzceConstants.PORTAL_IMMUNITY_TICKS);
        return new Exit(x + (right ? PvzceConstants.PORTAL_EXIT_OFFSET : 1F - PvzceConstants.PORTAL_EXIT_OFFSET), y);
    }

    private static void relocate(LevelServer level, Rig rig) {
        if (rig.pairs.isEmpty()) return;
        int index = level.random().nextInt(rig.pairs.size());
        boolean a = level.random().nextBoolean();
        PortalData.Pair old = rig.pairs.get(index);
        int otherX = a ? old.bx() : old.ax();
        int otherY = a ? old.by() : old.ay();
        Set<String> occupied = new HashSet<>();
        for (PortalData.Pair pair : rig.pairs) {
            occupied.add(pair.ax() + "," + pair.ay());
            occupied.add(pair.bx() + "," + pair.by());
        }
        List<int[]> available = new ArrayList<>();
        for (int y = 0; y < level.height(); y++) {
            for (int x = rig.relocateMinX; x <= level.width(); x++) {
                if (x != otherX && y != otherY && !occupied.contains(x + "," + y)) {
                    available.add(new int[]{x, y});
                }
            }
        }
        if (available.isEmpty()) return;
        int[] cell = available.get(level.random().nextInt(available.size()));
        rig.pairs.set(index, a ? new PortalData.Pair(cell[0], cell[1], otherX, otherY)
                : new PortalData.Pair(otherX, otherY, cell[0], cell[1]));
        rig.dirty = true;
    }

    @Override public void sendState(LevelServer level, PortalData data, LevelServer.ServerBridge bridge) {
        bridge.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_PORTAL, State.CODEC,
                new State(rig(level, data).pairs)));
    }

    @Override public void collectSave(LevelServer level, PortalData data, CompoundTag root) {
        Rig rig = rig(level, data);
        CompoundTag saved = new CompoundTag();
        saved.putInt("Ticks", rig.ticks);
        ListTag pairs = new ListTag();
        for (PortalData.Pair p : rig.pairs) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("AX", p.ax()); entry.putInt("AY", p.ay());
            entry.putInt("BX", p.bx()); entry.putInt("BY", p.by());
            pairs.add(entry);
        }
        saved.put("Pairs", pairs);
        root.put("Portals", saved);
    }

    @Override public void applySave(LevelServer level, PortalData data, CompoundTag root) {
        CompoundTag saved = root.getCompound("Portals");
        if (saved == null) return;
        Rig rig = rig(level, data);
        List<PortalData.Pair> pairs = new ArrayList<>();
        for (Tag tag : saved.getList("Pairs").values()) {
            if (tag instanceof CompoundTag p) {
                pairs.add(new PortalData.Pair(p.getInt("AX"), p.getInt("AY"), p.getInt("BX"), p.getInt("BY")));
            }
        }
        if (!pairs.isEmpty()) { rig.pairs.clear(); rig.pairs.addAll(pairs); }
        rig.ticks = Math.max(1, saved.getInt("Ticks"));
        rig.dirty = true;
    }

    @Override public List<String> validate(LevelDef def, PortalData data) {
        List<String> errors = new ArrayList<>();
        Set<String> occupied = new HashSet<>();
        if (data.pairs().isEmpty()) errors.add("This level declares a portal mechanic with no pairs");
        if (data.relocateMinX() > def.width()) {
            errors.add("A portal relocation floor of " + data.relocateMinX()
                    + " is off a board " + def.width() + " cells wide");
        }
        for (PortalData.Pair pair : data.pairs()) {
            for (int[] cell : new int[][]{{pair.ax(), pair.ay()}, {pair.bx(), pair.by()}}) {
                if (cell[0] < 0 || cell[0] > def.width() || cell[1] < 0 || cell[1] >= def.height()) {
                    errors.add("A portal pair names an off-board cell " + cell[0] + "," + cell[1]);
                }
                if (!occupied.add(cell[0] + "," + cell[1])) errors.add("Two portals share a cell");
            }
        }
        return errors;
    }
}
