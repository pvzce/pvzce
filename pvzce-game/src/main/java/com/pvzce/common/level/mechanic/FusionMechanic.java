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
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.level.FusionState;
import com.pvzce.common.level.FusionDrops;
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
                FieldSpec.decimal("drop_multiplier", "pvzce.mechanic.fusion.field.drop_multiplier", 0F, 5F),
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
        List<Identifier> recovered = recoverAbilities(level, abilities, data.tutorial() && work.tutorialStep == 0);
        boolean lost = recovered.size() < abilities.size();
        dropAbilities(level, recovered, plant.cellX(), plant.cellY());
        plant.remove();
        if (work.tutorialStep == 0) work.tutorialStep = 1;
        feedback(level, recovered.isEmpty() ? "分解完成，唯一能力已损失。"
                : lost ? "分解完成，损失一份能力；点击地面能力收集。" : "分解完成，点击地面能力收集。", true);
        return true;
    }

    /** Both a planted specimen and a packet yield the same recipe and lose at most one copy. */
    private static List<Identifier> recoverAbilities(LevelServer level, List<Identifier> recipe, boolean lossless) {
        List<Identifier> recovered = new ArrayList<>(recipe);
        boolean lost = !lossless && level.random().nextFloat() < data(level).lossChance();
        if (lost) recovered.remove(level.random().nextInt(recovered.size()));
        return recovered;
    }

    private static void dropAbilities(LevelServer level, List<Identifier> abilities, float x, float y) {
        dropAbilities(level, abilities, x, y, -1);
    }

    private static void dropAbilities(LevelServer level, List<Identifier> abilities, float x, float y, int firstId) {
        if (abilities.isEmpty()) return;
        Workshop work = workshop(level);
        float span = Math.min(level.width() - 1F, (abilities.size() - 1) * PvzceConstants.FUSION_DROP_SPACING);
        float left = Math.max(0.5F, Math.min(level.width() - 0.5F - span, x - span / 2F));
        for (int i = 0; i < abilities.size(); i++) {
            float offset = abilities.size() < 2 ? 0F : span * i / (abilities.size() - 1);
            int id = i == 0 && firstId >= 0 ? firstId : work.nextDrop++;
            work.drops.add(new FusionState.Drop(id, abilities.get(i).toString(), left + offset, y));
        }
    }

    private static boolean recycle(LevelServer level, int entityId) {
        CardDropEntity packet = level.entities().stream()
                .filter(entity -> entity.id() == entityId && entity instanceof CardDropEntity && !entity.isRemoved())
                .map(entity -> (CardDropEntity) entity).findFirst().orElse(null);
        if (packet == null || !packet.team().equals(level.plantPlayer().team())) return false;
        Identifier content = SlotResolver.resolve(packet.card()).map(SlotResolver.ResolvedCard::content).orElse(packet.card());
        var plant = BuiltInRegistries.PLANTS.get(content);
        if (plant == null || PlantRecipes.recipe(plant).isEmpty()) {
            feedback(level, "这张卡没有可分解的植物能力。", false);
            return false;
        }
        if (!level.consumeCardDrop(packet)) return false;
        // Packets always pay the normal loss chance, including during a tutorial.
        List<Identifier> recipe = PlantRecipes.recipe(plant);
        List<Identifier> recovered = recoverAbilities(level, recipe, false);
        boolean lost = recovered.size() < recipe.size();
        for (Identifier ability : recovered) workshop(level).inventory.merge(ability, 1, Integer::sum);
        if (workshop(level).tutorialStep == 3) workshop(level).tutorialStep = 1;
        feedback(level, lost ? "卡片已分解，损失一份能力。" : "卡片已分解，能力已收进库存。", true);
        sound(level, PvzceSounds.EFFECT_SHOVEL);
        return true;
    }

    public static void defeated(LevelServer level, ZombieEntity zombie) {
        FusionData data = data(level);
        if (data == null || !GameStateS2C.RUNNING.equals(level.gameState()) || zombie.unearned()) return;
        int count = FusionDrops.count(zombie.def(), data.dropMultiplier(), level.random().nextFloat());
        if (count == 0) return;
        List<Identifier> abilities = PlantRecipes.abilities();
        if (abilities.isEmpty()) return;
        List<Identifier> dropped = new ArrayList<>();
        for (int i = 0; i < count; i++) dropped.add(abilities.get(level.random().nextInt(abilities.size())));
        dropAbilities(level, dropped, zombie.cellX(), zombie.cellY());
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
            case "recycle" -> { return recycle(level, request.dropId()); }
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
                if (ability == null || !PlantRecipes.abilities().contains(ability)) return false;
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
            case "random" -> { return randomFill(level); }
            case "fuse" -> { return fuse(level, data); }
            default -> { return false; }
        }
        work.message = ""; work.success = true;
        sync(level, data);
        return true;
    }

    private static boolean randomFill(LevelServer level) {
        Workshop work = workshop(level);
        Map<Identifier, Integer> available = new LinkedHashMap<>(work.inventory);
        for (Identifier ability : work.tray) available.merge(ability, 1, Integer::sum);
        boolean teachingRecipe = work.tutorialStep < 3;
        List<List<Identifier>> recipes = PlantRecipes.availableRecipes(available,
                        id -> level.ownedCards().test(id) && (!teachingRecipe || plantable(level, id))).stream()
                .filter(recipe -> recipe.size() <= PvzceConstants.FUSION_TRAY_CAPACITY).toList();
        if (recipes.isEmpty()) {
            feedback(level, "现有材料无法凑出可用的背包植物配方。", false);
            return false;
        }
        List<Identifier> recipe = recipes.get(level.random().nextInt(recipes.size()));
        // Commit only after finding a whole recipe: a failed draw never disturbs the current tray.
        work.inventory.clear(); work.inventory.putAll(available); work.tray.clear();
        for (Identifier ability : recipe) {
            change(work.inventory, ability, -1); work.tray.add(ability);
        }
        if (work.tutorialStep == 1) work.tutorialStep = 2;
        feedback(level, "随机加料完成，点击“合成植物”进行合成。", true);
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
            if (ability != null && entry.getInt("Amount") > 0) {
                for (Identifier component : PlantRecipes.components(ability)) {
                    work.inventory.merge(component, entry.getInt("Amount"), Integer::sum);
                }
            }
        }
        for (Tag item : tag.getList("Tray").values()) if (item instanceof CompoundTag entry) {
            Identifier ability = savedAbility(entry);
            if (ability != null) for (Identifier component : PlantRecipes.components(ability)) {
                if (work.tray.size() < PvzceConstants.FUSION_TRAY_CAPACITY) work.tray.add(component);
                else work.inventory.merge(component, 1, Integer::sum);
            }
        }
        List<FusionState.Drop> savedDrops = new ArrayList<>();
        for (Tag item : tag.getList("Drops").values()) if (item instanceof CompoundTag entry && savedAbility(entry) != null) {
            savedDrops.add(new FusionState.Drop(entry.getInt("Id"), entry.getString("Ability"), entry.getFloat("X"), entry.getFloat("Y")));
            work.nextDrop = Math.max(work.nextDrop, entry.getInt("Id") + 1);
        }
        // Reserve every saved id before splitting, so new components cannot collide with later drops.
        for (FusionState.Drop drop : savedDrops) {
            List<Identifier> components = PlantRecipes.components(Identifier.tryParse(drop.ability()));
            if (components.size() == 1) work.drops.add(drop);
            else dropAbilities(level, components, drop.x(), drop.y(), drop.id());
        }
    }
    private static Identifier savedAbility(CompoundTag tag) {
        Identifier ability = Identifier.tryParse(tag.getString("Ability"));
        return ability != null && BuiltInRegistries.PLANT_CAPABILITIES.get(ability) != null ? ability : null;
    }
}
