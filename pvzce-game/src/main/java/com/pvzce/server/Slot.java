package com.pvzce.server;

import com.pvzce.api.util.Identifier;

import java.util.Locale;

/** Player card slot: a plant card, a resource card or a tool card. */
public final class Slot {
    public enum Kind {
        PLANT, RESOURCE, TOOL;

        /** The wire spelling (also what seed options and level JSON use). */
        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind fromJson(String value) {
            if (value == null) {
                return PLANT;
            }
            for (Kind kind : values()) {
                if (kind.json().equalsIgnoreCase(value)) {
                    return kind;
                }
            }
            return PLANT;
        }
    }

    /** Sentinel for "this card can be used any number of times". */
    public static final int UNLIMITED_USES = -1;

    private final int index;
    private final Kind kind;
    private final Identifier defId;
    private final int costSun;
    private int cooldownLeft;
    /** Remaining uses for limited tools, or {@link #UNLIMITED_USES}. */
    private int usesLeft;

    public Slot(int index, Kind kind, Identifier defId, int costSun, int cooldownLeft) {
        this(index, kind, defId, costSun, cooldownLeft, UNLIMITED_USES);
    }

    public Slot(int index, Kind kind, Identifier defId, int costSun, int cooldownLeft, int usesLeft) {
        this.index = index;
        this.kind = kind;
        this.defId = defId;
        this.costSun = costSun;
        this.cooldownLeft = cooldownLeft;
        this.usesLeft = usesLeft;
    }

    public int index() {
        return index;
    }

    public Kind kind() {
        return kind;
    }

    public Identifier defId() {
        return defId;
    }

    public int costSun() {
        return costSun;
    }

    public int cooldownLeft() {
        return cooldownLeft;
    }

    public int usesLeft() {
        return usesLeft;
    }

    public boolean hasUsesLeft() {
        return usesLeft == UNLIMITED_USES || usesLeft > 0;
    }

    public void startCooldown(int ticks) {
        cooldownLeft = Math.max(cooldownLeft, ticks);
    }

    public void clearCooldown() {
        cooldownLeft = 0;
    }

    /** Spends one use of a limited card. */
    public void consumeUse() {
        if (usesLeft > 0) {
            usesLeft--;
        }
    }

    public void restoreUses(int uses) {
        this.usesLeft = uses;
    }

    public void tick() {
        if (cooldownLeft > 0) {
            cooldownLeft--;
        }
    }

    public boolean ready() {
        return cooldownLeft <= 0 && hasUsesLeft();
    }
}
