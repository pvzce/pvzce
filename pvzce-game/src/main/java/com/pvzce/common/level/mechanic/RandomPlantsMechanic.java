package com.pvzce.common.level.mechanic;

import com.pvzce.api.content.LevelBelt;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlacementDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.ProjectileRef;
import com.pvzce.api.content.RandomPlantsData;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.content.capability.TypedCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.EntityKind;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.capability.PlantCapabilities;
import com.pvzce.common.capability.plant.ShooterCapability;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.level.RandomPlantsState;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ProjectileEntity;
import com.pvzce.server.level.LevelServer;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Immutable, level-local recipes. Sampling never writes into a content registry. */
public final class RandomPlantsMechanic implements LevelMechanic<RandomPlantsData> {
    public record Payload(String kind, Identifier id) {}

    public record Recipe(PlantDef plant, Payload shot, Payload product, float launchSpeed) {}

    @Override public MapCodec<RandomPlantsData> codec() { return RandomPlantsData.MAP_CODEC; }

    public static RandomPlantsData data(LevelDef def) {
        return LevelMechanics.data(def, PvzceIds.MECHANIC_RANDOM_PLANTS, RandomPlantsData.class);
    }

    /** Stable ids and encoded configurations, rather than hash-map or registration order. */
    public static Map<Identifier, Recipe> recipes(RandomPlantsData data) {
        Map<String, SortedMap<String, TypedCapability<PlantCapability>>> pool = new TreeMap<>();
        List<Identifier> plants = BuiltInRegistries.PLANTS.keySet().stream()
                .sorted(Comparator.comparing(Identifier::toString)).toList();
        for (Identifier id : plants) {
            for (var capability : BuiltInRegistries.PLANTS.get(id).resolvedCapabilities()) {
                String encoded = canonical(PlantCapabilities.CODEC.encodeStart(JsonOps.INSTANCE, capability).getOrThrow());
                pool.computeIfAbsent(capability.type().toString(), key -> new TreeMap<>())
                        .putIfAbsent(encoded, capability);
            }
        }
        if (pool.isEmpty()) throw new IllegalStateException("random_plants needs plant capabilities");
        List<Payload> payloads = new ArrayList<>();
        BuiltInRegistries.PLANTS.keySet().forEach(id -> payloads.add(new Payload(EntityKind.PLANT, id)));
        BuiltInRegistries.ZOMBIES.keySet().forEach(id -> payloads.add(new Payload(EntityKind.ZOMBIE, id)));
        BuiltInRegistries.PROJECTILES.keySet().forEach(id -> payloads.add(new Payload(EntityKind.PROJECTILE, id)));
        BuiltInRegistries.RESOURCES.keySet().forEach(id -> payloads.add(new Payload(EntityKind.RESOURCE, id)));
        payloads.sort(Comparator.comparing(p -> p.kind() + ":" + p.id()));

        Map<Identifier, Recipe> result = new LinkedHashMap<>();
        for (Identifier id : plants) {
            Random random = new Random(data.seed() ^ stableHash(id.toString()));
            List<String> types = new ArrayList<>(pool.keySet());
            Collections.shuffle(types, random);
            int count = 1 + random.nextInt(Math.min(PvzceConstants.RANDOM_PLANT_MAX_ABILITIES, types.size()));
            List<TypedCapability<PlantCapability>> abilities = new ArrayList<>();
            for (String type : types.subList(0, count)) {
                List<TypedCapability<PlantCapability>> variants = new ArrayList<>(pool.get(type).values());
                abilities.add(variants.get(random.nextInt(variants.size())));
            }
            PlantDef original = BuiltInRegistries.PLANTS.get(id);
            PlantDef changed = withAbilities(original, abilities);
            result.put(id, new Recipe(changed, payloads.get(random.nextInt(payloads.size())),
                    payloads.get(random.nextInt(payloads.size())),
                    PvzceConstants.RANDOM_LAUNCH_MIN_SPEED + random.nextFloat()
                            * (PvzceConstants.RANDOM_LAUNCH_MAX_SPEED - PvzceConstants.RANDOM_LAUNCH_MIN_SPEED)));
        }
        // A registry-wide safety recipe depends only on the seed and registry, never on card source.
        List<Identifier> candidates = plants.stream().filter(id -> {
            PlantDef def = result.get(id).plant();
            return def.upgrade().isEmpty() && def.placement().equals(PlacementDef.PLANTABLE_DEF);
        }).collect(Collectors.toCollection(ArrayList::new));
        Collections.shuffle(candidates, new Random(data.seed()));
        if (candidates.size() < PvzceConstants.RANDOM_BELT_MIN_ATTACKERS)
            throw new IllegalStateException("random_plants needs three ordinary plant definitions");
        Identifier shooter = PvzceIds.id("shooter");
        var attack = new TypedCapability<PlantCapability>(shooter,
                new ShooterCapability(ShooterCapability.DEFAULT_INTERVAL,
                        List.of(new ProjectileRef(PvzceIds.PEASHOOTER_PEA, PvzceConstants.RANDOM_LAUNCH_DAMAGE, 1)),
                        Optional.empty(), 0, 0F, false, EntityAnimations.SHOOT));
        for (Identifier id : candidates.subList(0, PvzceConstants.RANDOM_BELT_MIN_ATTACKERS)) {
            Recipe recipe = result.get(id);
            List<TypedCapability<PlantCapability>> abilities = new ArrayList<>(recipe.plant().resolvedCapabilities());
            // A persistent shooter must not sleep, consume itself, or explode on a timer.
            abilities.removeIf(c -> c.type().equals(shooter) || c.type().equals(PvzceIds.id("nocturnal"))
                    || c.type().equals(PvzceIds.id("explosive")) || c.type().equals(PvzceIds.id("freeze_all"))
                    || c.value().consumesOnPlace());
            if (abilities.size() == PvzceConstants.RANDOM_PLANT_MAX_ABILITIES) abilities.removeLast();
            abilities.add(attack);
            result.put(id, new Recipe(withAbilities(recipe.plant(), abilities), recipe.shot(),
                    recipe.product(), recipe.launchSpeed()));
        }
        return Collections.unmodifiableMap(result);
    }

    private static String canonical(JsonElement value) {
        if (value.isJsonObject()) {
            List<String> keys = new ArrayList<>(value.getAsJsonObject().keySet());
            Collections.sort(keys);
            return keys.stream().map(key -> new JsonPrimitive(key) + ":"
                    + canonical(value.getAsJsonObject().get(key))).collect(Collectors.joining(",", "{", "}"));
        }
        if (value.isJsonArray()) {
            List<String> values = new ArrayList<>();
            value.getAsJsonArray().forEach(entry -> values.add(canonical(entry)));
            return "[" + String.join(",", values) + "]";
        }
        return value.toString();
    }

    private static boolean attacks(Recipe recipe) {
        var abilities = recipe.plant().resolvedCapabilities();
        return abilities.stream().anyMatch(c -> c.value() instanceof ShooterCapability shooter && !shooter.shots().isEmpty())
                && abilities.stream().noneMatch(c -> c.value().consumesOnPlace() || c.type().equals(PvzceIds.id("nocturnal"))
                    || c.type().equals(PvzceIds.id("explosive")) || c.type().equals(PvzceIds.id("freeze_all")));
    }

    /** The belt adds missing attackers; it never changes an already rolled plant's abilities. */
    public static LevelBelt belt(LevelDef def, LevelBelt original) {
        RandomPlantsData data = data(def);
        if (data == null) return original;
        Map<Identifier, Recipe> recipes = recipes(data);
        List<LevelBelt.BeltCard> cards = new ArrayList<>(original.cards());
        Set<Identifier> attackers = new HashSet<>();
        for (var card : cards) {
            var resolved = SlotResolver.resolve(card.card()).orElse(null);
            if (card.weight() <= 0 || card.maxCount() == 0 || resolved == null) continue;
            Recipe recipe = recipes.get(resolved.content());
            if (recipe != null && attacks(recipe)) attackers.add(resolved.content());
        }
        for (var entry : recipes.entrySet()) {
            if (attackers.size() >= PvzceConstants.RANDOM_BELT_MIN_ATTACKERS) break;
            if (attacks(entry.getValue()) && entry.getValue().plant().placement().equals(PlacementDef.PLANTABLE_DEF)
                    && entry.getValue().plant().upgrade().isEmpty() && attackers.add(entry.getKey()))
                cards.add(new LevelBelt.BeltCard(entry.getKey(), 1, LevelBelt.BeltCard.UNLIMITED));
        }
        if (attackers.size() < PvzceConstants.RANDOM_BELT_MIN_ATTACKERS)
            throw new IllegalStateException("random_plants conveyor needs three attacking plant cards");
        return new LevelBelt(original.intervalTicks(), original.capacity(), original.initialCards(), cards);
    }

    private static long stableHash(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) hash = (hash ^ value.charAt(i)) * 0x100000001b3L;
        return hash;
    }

    private static PlantDef withAbilities(PlantDef def, List<TypedCapability<PlantCapability>> abilities) {
        return new PlantDef(def.id(), def.cost(), def.health(), def.placement(), abilities, Optional.empty(),
                def.sounds(), def.animations(), def.texture(), def.renderScale(), def.order(), def.upgrade());
    }

    public static Map<Identifier, Recipe> recipes(LevelServer level) {
        RandomPlantsData data = data(level.def());
        return data == null ? Map.of() : level.mechanicState(PvzceIds.MECHANIC_RANDOM_PLANTS,
                () -> recipes(data));
    }

    public static PlantDef plant(LevelServer level, PlantDef original) {
        Recipe recipe = recipes(level).get(original.id());
        return recipe == null ? original : recipe.plant();
    }

    public static boolean fire(LevelServer level, PlantEntity source, ProjectileEntity original) {
        Recipe recipe = recipes(level).get(source.defId());
        if (recipe == null) return false;
        level.spawnRandomPayload(recipe.shot(), source, original.cellX(), original.cellY(),
                original.height(), original.damage(), 1, recipe.launchSpeed(),
                original.direction() * original.vectorX(), original.direction() * original.vectorY());
        return true;
    }

    public static boolean produce(LevelServer level, PlantEntity source, int amount, float x, float y) {
        Recipe recipe = recipes(level).get(source.defId());
        if (recipe == null) return false;
        level.spawnRandomPayload(recipe.product(), source, x, y, source.height(),
                PvzceConstants.RANDOM_LAUNCH_DAMAGE, amount, 0F, 1F, 0F);
        return true;
    }

    @Override public void sendState(LevelServer level, RandomPlantsData data, LevelServer.ServerBridge bridge) {
        var cards = recipes(level).values().stream().map(r -> new RandomPlantsState.Card(
                r.plant().id().toString(), r.plant().resolvedCapabilities().stream().map(c -> c.type().toString()).toList(),
                r.shot().kind(), r.shot().id().toString(), r.product().kind(), r.product().id().toString(), r.launchSpeed())).toList();
        bridge.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_RANDOM_PLANTS,
                RandomPlantsState.CODEC, new RandomPlantsState(data.seed(), cards)));
    }

    @Override public List<String> validate(LevelDef def, RandomPlantsData data) {
        try {
            recipes(data);
            LevelBelt belt = LevelMechanics.data(def, PvzceIds.MECHANIC_CONVEYOR, LevelBelt.class);
            if (belt != null) belt(def, belt);
            return List.of();
        }
        catch (IllegalStateException exception) { return List.of(exception.getMessage()); }
    }
}
