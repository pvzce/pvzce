package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

/**
 * Status effects a projectile or plant can apply to a zombie. Mods add their own
 * by extending the switch in {@code ZombieEntity.applyStatus}; the data side only
 * ever references these names, so the old free-form strings such as
 * {@code "slow_30_4s"} (which encoded the magnitude and duration in the id
 * itself, and silently did nothing when misspelled) are gone.
 */
public enum ZombieStatus {
    SLOW,
    IMMOBILIZED;

    public static final Codec<ZombieStatus> CODEC = Codec.STRING.flatXmap(
            name -> {
                try {
                    return DataResult.success(valueOf(name.trim().toUpperCase(java.util.Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    return DataResult.error(() -> "Unknown zombie status: " + name);
                }
            },
            status -> DataResult.success(status.name().toLowerCase(java.util.Locale.ROOT)));
}
