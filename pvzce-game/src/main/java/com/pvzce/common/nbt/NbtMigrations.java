package com.pvzce.common.nbt;

import com.pvzce.common.PvzceConstants;

/**
 * Save-version migration chain. Phase 2 keeps a single hop: legacy phase-1
 * saves (no DataVersion / no GameState) are intentionally not migrated.
 */
public final class NbtMigrations {
    private NbtMigrations() {
    }

    public static CompoundTag migrate(CompoundTag tag) {
        if (!tag.contains("DataVersion")) {
            tag.putInt("DataVersion", PvzceConstants.SAVE_DATA_VERSION);
        }
        while (tag.getInt("DataVersion") < PvzceConstants.SAVE_DATA_VERSION) {
            int from = tag.getInt("DataVersion");
            if (from == 1) {
                // Phase-1 demo format is discarded by LevelServer.restore (no GameState).
                tag.putInt("DataVersion", 2);
            } else {
                break;
            }
        }
        return tag;
    }
}
