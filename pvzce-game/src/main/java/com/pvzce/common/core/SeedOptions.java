package com.pvzce.common.core;

import com.pvzce.api.content.LevelDef;
import com.pvzce.common.network.packet.SeedOption;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the client-facing seed-pool option list for a level.
 *
 * <p>Shares {@link SlotResolver} with the server's card-bar builder, so the pool
 * the player chooses from and the bar the server actually grants can no longer
 * disagree (they used to, for any slot id without a matching {@code slots/*.json}).
 */
public final class SeedOptions {
    private SeedOptions() {
    }

    public static List<SeedOption> forLevel(LevelDef def) {
        List<SeedOption> result = new ArrayList<>();
        for (SlotResolver.ResolvedCard resolved : SlotResolver.resolveAll(def.slots())) {
            result.add(new SeedOption(resolved.slotId().toString(), resolved.kind().json(),
                    resolved.content().toString(),
                    resolved.icon().map(Object::toString).orElse(""), resolved.costSun()));
        }
        return List.copyOf(result);
    }
}
