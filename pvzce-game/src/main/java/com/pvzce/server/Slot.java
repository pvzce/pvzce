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
    /**
     * How long this card takes to come back, before the level's multiplier.
     *
     * <p>The card's own number, read from its definition when the bar was built, and zero
     * for a card that has no cooldown at all. The level scales it at the moment the card is
     * spent ({@code LevelServer.effectiveCooldownTicks}), so a {@code /gamerule} change
     * reaches every card that is still on the bar instead of only the ones dealt after it.
     */
    private final int cooldownTicks;
    private int cooldownLeft;
    /** Remaining uses for limited tools, or {@link #UNLIMITED_USES}. */
    private int usesLeft;

    public Slot(int index, Kind kind, Identifier defId, int costSun, int cooldownLeft,
                int usesLeft, int cooldownTicks) {
        this.index = index;
        this.kind = kind;
        this.defId = defId;
        this.costSun = costSun;
        this.cooldownLeft = cooldownLeft;
        this.usesLeft = usesLeft;
        this.cooldownTicks = Math.max(0, cooldownTicks);
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

    /** This card's own cooldown in ticks, before the level's multiplier; 0 = none. */
    public int cooldownTicks() {
        return cooldownTicks;
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
