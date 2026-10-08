package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.FusionData;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.PlantRecipes;
import com.pvzce.common.level.FusionState;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.nbt.Tag;
import com.pvzce.common.network.packet.EffectEventS2C;
import com.pvzce.common.network.packet.FusionActionC2S;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.network.packet.ResourceDeltaS2C;
import com.pvzce.server.entity.CardDropEntity;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.server.level.cardsource.CardSource;
import com.pvzce.server.level.cardsource.FusionCardSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One authoritative inventory and tray, shared by shovelling, killing, buying and crafting. */
public final class FusionMechanic implements LevelMechanic<FusionData> {
    private static final String SAVE_KEY = "Fusion";
    private static final class Workshop {
        final Map<Identifier, Integer> inventory = new LinkedHashMap<>();
        final List<Identifier> tray = new ArrayList<>();
        final List<FusionState.Drop> drops = new ArrayList<>();
        int nextDrop;
        int tutorialStep = 4;
        String message = "";
        boolean success = true;
    }
    private static Workshop workshop(LevelServer level) {
        return level.mechanicState(PvzceIds.MECHANIC_FUSION, Workshop::new);
    }
    public static FusionData data(LevelServer level) {
        return LevelMechanics.data(level.def(), PvzceIds.MECHANIC_FUSION, FusionData.class);
    }
    public static boolean teaching(LevelServer level) {
        return data(level) != null && workshop(level).tutorialStep < 4;
    }
    @Override public MapCodec<FusionData> codec() { return FusionData.MAP_CODEC; }
    @Override public boolean cardSource() { return true; }
    @Override public boolean dealsItsOwnCards() { return true; }
    @Override public CardSource createCardSource(CardSource.Context context, FusionData data) {
        return new FusionCardSource(context);
    }
    @Override public void onLevelCreated(LevelServer level, FusionData data) {
        workshop(level).tutorialStep = data.tutorial() ? 0 : 4;
        level.plantPlayer().team().unlockResource(PvzceIds.SUN);
    }
    @Override public List<FieldSpec> editorFields() {
        return List.of(FieldSpec.bool("tutorial", "pvzce.mechanic.fusion.field.tutorial"),
                FieldSpec.integer("price", "pvzce.mechanic.fusion.field.price", 1, 10000));
    }
    @Override public List<String> validate(LevelDef def, FusionData data) {
        return def.slots().isEmpty() ? List.of() : List.of("fusion supplies ground packets and requires an empty slots list");
    }

    /** Called before the ordinary shovel refund. Losing one ability may leave nothing. */
    public static boolean decompose(LevelServer level, PlantEntity plant) {
        FusionData data = data(level);
        if (data == null) return false;
        Workshop work = workshop(level);
        List<Identifier> abilities = new ArrayList<>(PlantRecipes.recipe(plant.def()));
        if (abilities.isEmpty()) {
            feedback(level, "这株植物没有可分解的能力。", false);
            return true;
        }
        Identifier lost = null;
        if (!(data.tutorial() && work.tutorialStep == 0) && level.random().nextFloat() < data.lossChance()) {
            lost = abilities.remove(level.random().nextInt(abilities.size()));
        }
        for (Identifier ability : abilities) work.inventory.merge(ability, 1, Integer::sum);
        plant.remove();
        if (work.tutorialStep == 0) work.tutorialStep = 1;
        feedback(level, lost == null ? "分解完成，能力已收进库存。" : "分解完成，损失一份能力。", true);
        return true;
    }

    public static void defeated(LevelServer level, ZombieEntity zombie) {
        FusionData data = data(level);
        if (data == null || !GameStateS2C.RUNNING.equals(level.gameState()) || zombie.unearned()) return;
        if (level.random().nextFloat() >= data.dropChance()) return;
        List<Identifier> abilities = PlantRecipes.abilities();
        if (abilities.isEmpty()) return;
        Workshop work = workshop(level);
        Identifier ability = abilities.get(level.random().nextInt(abilities.size()));
        work.drops.add(new FusionState.Drop(work.nextDrop++, ability.toString(),
                Math.max(0.5F, Math.min(level.width() - 0.5F, zombie.cellX())), zombie.cellY()));
        sync(level, data);
    }

    /** Finishes the lesson only after a real, accepted planting. */
    public static void planted(LevelServer level) {
        if (data(level) != null && workshop(level).tutorialStep == 3) {
            workshop(level).tutorialStep = 4;
            feedback(level, "教程完成！击败僵尸收集能力，合成生产能力可获得向日葵。", true);
        }
    }

    public static boolean action(LevelServer level, FusionActionC2S request) {
        FusionData data = data(level);
        if (data == null || !GameStateS2C.RUNNING.equals(level.gameState())) return false;
        Workshop work = workshop(level);
        Identifier ability = Identifier.tryParse(request.ability());
        switch (request.action()) {
            case "add" -> {
                if (ability == null || work.inventory.getOrDefault(ability, 0) <= 0
                        || work.tray.size() >= PvzceConstants.FUSION_TRAY_CAPACITY) return false;
                change(work.inventory, ability, -1); work.tray.add(ability);
                if (work.tutorialStep == 1) work.tutorialStep = 2;
            }
            case "remove" -> {
                if (!work.tray.remove(ability)) return false;
                work.inventory.merge(ability, 1, Integer::sum);
            }
            case "clear" -> {
                for (Identifier entry : work.tray) work.inventory.merge(entry, 1, Integer::sum);
                work.tray.clear();
            }
            case "buy" -> {
                if (ability == null || BuiltInRegistries.PLANT_CAPABILITIES.get(ability) == null) return false;
                if (!level.plantPlayer().team().consume(PvzceIds.SUN, data.price())) {
                    feedback(level, "阳光不足，先合成生产能力并种下向日葵。", false); return false;
                }
                work.inventory.merge(ability, 1, Integer::sum);
                level.send(new ResourceDeltaS2C(level.plantPlayer().team().id().toString(), PvzceIds.SUN.toString(),
                        level.plantPlayer().team().resourcesOf(PvzceIds.SUN)));
            }
            case "collect" -> {
                List<FusionState.Drop> collected = work.drops.stream()
                        .filter(drop -> request.dropId() == -1 || drop.id() == request.dropId()).toList();
                if (collected.isEmpty()) return false;
                for (FusionState.Drop drop : collected) work.inventory.merge(Identifier.tryParse(drop.ability()), 1, Integer::sum);
                work.drops.removeAll(collected);
                sound(level, PvzceSounds.UI_COLLECT);
            }
            case "fuse" -> { return fuse(level, data); }
            default -> { return false; }
        }
        work.message = ""; work.success = true;
        sync(level, data);
        return true;
    }

    private static boolean fuse(LevelServer level, FusionData data) {
        Workshop work = workshop(level);
        if (work.tray.isEmpty()) { feedback(level, "先放入至少一份能力。", false); return false; }
        List<Identifier> candidates = PlantRecipes.candidates(work.tray, level.ownedCards());
        if (work.tutorialStep == 2) {
            // A lesson cannot wait for planting a water-only or unsupported upgrade card.
            // Ordinary crafting still draws from the entire owned recipe group.
            candidates = candidates.stream().filter(id -> plantable(level, id)).toList();
        }
        if (candidates.isEmpty()) {
            work.tray.remove(level.random().nextInt(work.tray.size()));
            for (Identifier entry : work.tray) work.inventory.merge(entry, 1, Integer::sum);
            work.tray.clear();
            feedback(level, "没有匹配的背包植物：随机损失一份能力，其余已返还。", false);
            return true;
        }
        // Pick an unoccupied packet cell before spending anything. Plant occupancy is irrelevant.
        for (int x = 0; x < level.width(); x++) {
            for (int y = 0; y < level.height(); y++) {
                final int column = x, row = y;
                boolean taken = level.entities().stream().anyMatch(entity -> entity instanceof CardDropEntity packet
                        && !packet.isRemoved() && !packet.held() && packet.gridX() == column && packet.gridY() == row);
                if (taken) continue;
                Identifier card = candidates.get(level.random().nextInt(candidates.size()));
                CardDropEntity packet = new CardDropEntity(card, level.plantPlayer().team(), x, y, PvzceConstants.FUSION_CARD_LIFETIME_TICKS);
                packet.setHeight(level.surfaceHeight(packet.surfaceId(), packet.cellX(), packet.cellY()));
                level.addEntity(packet);
                work.tray.clear();
                if (work.tutorialStep == 2) work.tutorialStep = 3;
                feedback(level, "合成成功！点击待种卡片或草坪上的种子卡进行种植。", true);
                sound(level, PvzceSounds.UI_CHIME);
                return true;
            }
        }
        feedback(level, "草坪上的种子卡已满，先种下一张再合成。", false);
        return false;
    }

    private static boolean plantable(LevelServer level, Identifier card) {
        var plant = BuiltInRegistries.PLANTS.get(card);
        for (int x = 0; x < level.width(); x++) for (int y = 0; y < level.height(); y++) {
            if (level.canPlacePlant(plant, x, y)) return true;
        }
        return false;
    }

    private static void change(Map<Identifier, Integer> map, Identifier id, int delta) {
        int amount = map.getOrDefault(id, 0) + delta;
        if (amount <= 0) map.remove(id); else map.put(id, amount);
    }
    private static void feedback(LevelServer level, String message, boolean success) {
        Workshop work = workshop(level); work.message = message; work.success = success;
        if (!success) sound(level, PvzceSounds.UI_BUZZER);
        sync(level, data(level));
    }
    private static void sound(LevelServer level, Identifier sound) {
        level.send(new EffectEventS2C("", 0F, 0F, sound.toString(), 1F, 1F));
    }
    private static List<FusionState.Count> counts(Map<Identifier, Integer> map) {
        return map.entrySet().stream().map(e -> new FusionState.Count(e.getKey().toString(), e.getValue())).toList();
    }
    public static FusionState snapshot(LevelServer level, FusionData data) {
        Workshop work = workshop(level);
        Map<Identifier, Integer> tray = new LinkedHashMap<>();
        for (Identifier ability : work.tray) tray.merge(ability, 1, Integer::sum);
        return new FusionState(counts(work.inventory), counts(tray), work.drops,
                work.tutorialStep, data.price(), work.message, work.success);
    }
    private static void sync(LevelServer level, FusionData data) {
        level.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_FUSION, FusionState.CODEC, snapshot(level, data)));
    }
    @Override public void sendState(LevelServer level, FusionData data, LevelServer.ServerBridge bridge) {
        bridge.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_FUSION, FusionState.CODEC, snapshot(level, data)));
    }
    @Override public void collectSave(LevelServer level, FusionData data, CompoundTag root) {
        Workshop work = workshop(level); CompoundTag tag = new CompoundTag();
        tag.putInt("Step", work.tutorialStep); tag.putInt("NextDrop", work.nextDrop);
        tag.putString("Message", work.message); tag.putInt("Success", work.success ? 1 : 0);
        ListTag bag = new ListTag();
        for (var entry : work.inventory.entrySet()) {
            CompoundTag count = new CompoundTag(); count.putString("Ability", entry.getKey().toString());
            count.putInt("Amount", entry.getValue()); bag.add(count);
        }
        tag.put("Inventory", bag);
        ListTag tray = new ListTag();
        for (Identifier entry : work.tray) { CompoundTag item = new CompoundTag(); item.putString("Ability", entry.toString()); tray.add(item); }
        tag.put("Tray", tray);
        ListTag drops = new ListTag();
        for (FusionState.Drop drop : work.drops) {
            CompoundTag item = new CompoundTag(); item.putInt("Id", drop.id()); item.putString("Ability", drop.ability());
            item.putFloat("X", drop.x()); item.putFloat("Y", drop.y()); drops.add(item);
        }
        tag.put("Drops", drops); root.put(SAVE_KEY, tag);
    }
    @Override public void applySave(LevelServer level, FusionData data, CompoundTag root) {
        if (!root.contains(SAVE_KEY)) return;
        Workshop work = workshop(level); CompoundTag tag = root.getCompound(SAVE_KEY);
        work.inventory.clear(); work.tray.clear(); work.drops.clear();
        work.tutorialStep = Math.max(0, Math.min(4, tag.getInt("Step"))); work.nextDrop = Math.max(0, tag.getInt("NextDrop"));
        work.message = tag.getString("Message"); work.success = tag.getInt("Success") != 0;
        for (Tag item : tag.getList("Inventory").values()) if (item instanceof CompoundTag entry) {
            Identifier ability = savedAbility(entry);
            if (ability != null && entry.getInt("Amount") > 0) work.inventory.put(ability, entry.getInt("Amount"));
        }
        for (Tag item : tag.getList("Tray").values()) if (item instanceof CompoundTag entry) {
            Identifier ability = savedAbility(entry);
            if (ability != null && work.tray.size() < PvzceConstants.FUSION_TRAY_CAPACITY) work.tray.add(ability);
        }
        for (Tag item : tag.getList("Drops").values()) if (item instanceof CompoundTag entry && savedAbility(entry) != null) {
            work.drops.add(new FusionState.Drop(entry.getInt("Id"), entry.getString("Ability"), entry.getFloat("X"), entry.getFloat("Y")));
            work.nextDrop = Math.max(work.nextDrop, entry.getInt("Id") + 1);
        }
    }
    private static Identifier savedAbility(CompoundTag tag) {
        Identifier ability = Identifier.tryParse(tag.getString("Ability"));
        return ability != null && BuiltInRegistries.PLANT_CAPABILITIES.get(ability) != null ? ability : null;
    }
}
