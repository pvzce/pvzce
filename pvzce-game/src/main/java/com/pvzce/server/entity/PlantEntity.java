package com.pvzce.server.entity;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.content.capability.TypedCapability;
import com.pvzce.api.entity.EntityKind;
import com.pvzce.api.entity.EntityLayers;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.Team;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Data-driven plant entity: a thin shell that owns position/health/age and
 * forwards every behaviour to its {@link PlantCapability} instances.
 *
 * <p>There is deliberately no {@code behavior} string here any more. Adding a
 * plant behaviour is a new capability type (or new data), never a new branch in
 * this class, and a plant can combine capabilities (a shooter that also produces
 * sun) without either one knowing about the other.
 */
public class PlantEntity extends PvzceEntity {
    private final PlantDef def;
    private final List<Instance> capabilities = new ArrayList<>();
    private int age;

    public PlantEntity(PlantDef def, Team team, int gridX, int gridY) {
        super(def.id(), team, gridX + 0.5F, gridY + 0.5F, def.health());
        this.def = def;
        for (TypedCapability<PlantCapability> entry : def.resolvedCapabilities()) {
            capabilities.add(new Instance(entry.type(), entry.value().instantiate()));
        }
    }

    public PlantDef def() {
        return def;
    }

    public int age() {
        return age;
    }

    @Override
    public String entityKind() {
        return EntityKind.PLANT;
    }

    @Override
    public int layer() {
        return EntityLayers.PLANT;
    }

    @Override
    public void tick(LevelServer level) {
        if (removed) {
            return;
        }
        age++;
        for (Instance instance : capabilities) {
            instance.capability.tick(this, level);
            if (removed) {
                break;
            }
        }
    }

    /** Called by the level right after the plant is committed to the field. */
    public void onPlaced(LevelServer level) {
        for (Instance instance : capabilities) {
            instance.capability.onPlaced(this, level);
            if (removed) {
                break;
            }
        }
    }

    /** True when placing this plant consumes it immediately (coffee bean). */
    public boolean consumesOnPlace() {
        return capabilities.stream().anyMatch(instance -> instance.capability.consumesOnPlace());
    }

    /** Instant activation (energy bean, coffee bean, glove); a no-op when nothing can charge. */
    public void boost() {
        for (Instance instance : capabilities) {
            instance.capability.boost(this);
        }
    }

    /**
     * Remaining arm-up ticks of a timed/proximity charge, or zero when the plant
     * has no explosive capability. Convenience for tests and the HUD.
     */
    public int armTicksLeft() {
        com.pvzce.common.capability.plant.ExplosiveCapability explosive =
                capability(com.pvzce.common.capability.plant.ExplosiveCapability.class);
        return explosive == null ? 0 : explosive.fuseLeft();
    }

    /** The live capability instance of the given type, or {@code null}. */
    public <T extends PlantCapability> T capability(Class<T> type) {
        for (Instance instance : capabilities) {
            if (type.isInstance(instance.capability)) {
                return type.cast(instance.capability);
            }
        }
        return null;
    }

    @Override
    public CompoundTag saveState() {
        CompoundTag tag = saveBaseState();
        tag.putInt("age", age);
        CompoundTag saved = new CompoundTag();
        for (Instance instance : capabilities) {
            CompoundTag capabilityTag = new CompoundTag();
            instance.capability.save(capabilityTag);
            saved.put(instance.type.toString(), capabilityTag);
        }
        tag.put("capabilities", saved);
        return tag;
    }

    @Override
    public void restoreState(CompoundTag tag) {
        restoreBaseState(tag);
        age = tag.getInt("age");
        CompoundTag saved = tag.getCompound("capabilities");
        for (Instance instance : capabilities) {
            CompoundTag capabilityTag = saved.getCompound(instance.type.toString());
            instance.capability.load(capabilityTag);
        }
    }

    /** Convenience for callers that only know the definition id (HUD, tests). */
    public Identifier definitionId() {
        return def.id();
    }

    private record Instance(Identifier type, PlantCapability capability) {
    }
}
