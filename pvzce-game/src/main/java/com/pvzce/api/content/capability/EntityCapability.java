package com.pvzce.api.content.capability;
import com.pvzce.common.nbt.CompoundTag;
/** Common instance and persistence contract; typed hooks express host-specific requirements. */
public interface EntityCapability {
    /** Keeps duplicate ability instances independent while retaining the original first key. */
    static String stateKey(com.pvzce.api.util.Identifier type, java.util.Map<com.pvzce.api.util.Identifier,Integer> counts) {
        int occurrence = counts.merge(type, 1, Integer::sum);
        return occurrence == 1 ? type.toString() : type + "#" + occurrence;
    }
    default EntityCapability instantiate() { return this; }
    default void save(CompoundTag tag) {}
    default void load(CompoundTag tag) {}
}
