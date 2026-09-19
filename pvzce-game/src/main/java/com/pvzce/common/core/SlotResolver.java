package com.pvzce.common.core;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.ResourceDef;
import com.pvzce.api.content.SlotDef;
import com.pvzce.api.content.ToolDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.Slot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The single answer to "what card does this slot id describe?".
 *
 * <p>The server (which builds the authoritative card bar) and the client (which
 * builds the seed chooser pool) used to answer it with two separate copies of the
 * same resolution logic. They disagreed about unknown ids - the client always
 * produced a card, the server silently dropped the slot - so a level that listed a
 * tool without a matching {@code slots/*.json} entry showed a usable-looking card
 * that the server then refused. Both sides now call {@link #resolve}, and unknown
 * ids are reported once instead of being papered over.
 */
public final class SlotResolver {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("PVZCE/Slots");
    private static final Set<Identifier> REPORTED = new HashSet<>();

    /**
     * A fully resolved card.
     *
     * @param slotId   the entry as written in the level's {@code slots} list
     * @param content  the plant / tool / resource the card actually grants
     * @param kind     card kind used by the wire protocol
     * @param costSun  sun cost, resolved through plant or tool data when the slot omits it
     * @param cooldown card cooldown in ticks
     * @param uses     remaining uses for limited tools, or {@link Slot#UNLIMITED_USES}
     * @param icon     icon resource, or empty when the content has none
     */
    public record ResolvedCard(Identifier slotId, Identifier content, Slot.Kind kind, int costSun,
                               int cooldownTicks, int uses, Optional<Identifier> icon) {
        public ResolvedCard withCooldown(int cooldown) {
            return new ResolvedCard(slotId, content, kind, costSun, cooldown, uses, icon);
        }
    }

    public static Optional<ResolvedCard> resolve(Identifier slotId) {
        if (slotId == null) {
            return Optional.empty();
        }
        SlotDef slot = BuiltInRegistries.SLOT_TYPES.get(slotId);
        if (slot != null) {
            Slot.Kind kind = switch (slot.kind()) {
                case PLANT -> Slot.Kind.PLANT;
                case RESOURCE -> Slot.Kind.RESOURCE;
                case TOOL -> Slot.Kind.TOOL;
            };
            int cost = slot.cost().amountOf(PvzceIds.SUN);
            int cooldown = slot.cost().cooldownTicks();
            int uses = Slot.UNLIMITED_USES;
            if (kind == Slot.Kind.PLANT) {
                PlantDef plant = BuiltInRegistries.PLANTS.get(slot.content());
                if (plant != null && cost == 0) {
                    cost = plant.cost().amountOf(PvzceIds.SUN);
                }
                if (plant != null && cooldown == 0) {
                    cooldown = plant.cost().cooldownTicks();
                }
            } else if (kind == Slot.Kind.TOOL) {
                ToolDef tool = BuiltInRegistries.TOOLS.get(slot.content());
                if (tool != null) {
                    uses = tool.uses();
                    if (cooldown == 0) {
                        cooldown = tool.cooldownTicks();
                    }
                }
            }
            return Optional.of(new ResolvedCard(slotId, slot.content(), kind, cost, cooldown, uses,
                    slot.icon().isPresent() ? slot.icon() : fallbackIcon(kind, slot.content())));
        }
        reportUnknown(slotId);
        return Optional.empty();
    }

    /** Resolves a whole slot list in order, skipping (and reporting) unusable ids. */
    public static java.util.List<ResolvedCard> resolveAll(java.util.List<Identifier> slotIds) {
        java.util.List<ResolvedCard> cards = new java.util.ArrayList<>();
        if (slotIds == null) {
            return java.util.List.of();
        }
        for (Identifier slotId : slotIds) {
            resolve(slotId).ifPresent(cards::add);
        }
        return java.util.List.copyOf(cards);
    }

    /** True when the level's slot list can actually be turned into cards. */
    public static boolean isResolvable(Identifier slotId) {
        return BuiltInRegistries.SLOT_TYPES.containsKey(slotId) || BuiltInRegistries.PLANTS.containsKey(slotId);
    }

    /**
     * True when this card is earned rather than always available.
     *
     * <p>Plants and tools are the backpack's business; resource cards are not,
     * because a level without its sun card could not be played at all. An unknown
     * card answers {@code false}, so a data error surfaces as "the server dropped
     * it" rather than as "the player has not unlocked it yet".
     *
     * <p>Lives here, next to {@link #resolve}, because the server's
     * {@code PlayerProfile} and the client's backpack screen both have to answer
     * it, and a second copy is exactly how "locked" and "unavailable" drift apart.
     */
    public static boolean requiresUnlock(Identifier card) {
        ResolvedCard resolved = resolve(card).orElse(null);
        return resolved != null
                && (resolved.kind() == Slot.Kind.PLANT || resolved.kind() == Slot.Kind.TOOL);
    }

    /**
     * True when a backpack with these unlocks owns this card.
     *
     * <p>The server's {@code PlayerProfile} and the client's {@code ClientProfile} both ask
     * this, and they had each written the same thirteen lines out: a sandbox world owns
     * everything, a resource card is never gated, and a plant counts as owned when either its
     * slot id or the content that slot grants is in the unlocked set - a level may name a
     * plant directly instead of its slot, and "I own the peashooter" must not depend on which
     * spelling the level happened to use.
     *
     * @param unlocked  the ids in the player's backpack
     * @param unlockAll the sandbox flag: everything is owned, including future content
     * @param card      a slot id or the content id it grants
     */
    public static boolean owns(java.util.Set<Identifier> unlocked, boolean unlockAll, Identifier card) {
        if (card == null) {
            return false;
        }
        if (!requiresUnlock(card) || unlockAll) {
            return true;
        }
        if (unlocked.contains(card)) {
            return true;
        }
        ResolvedCard resolved = resolve(card).orElse(null);
        return resolved != null && unlocked.contains(resolved.content());
    }

    private static void reportUnknown(Identifier slotId) {
        synchronized (REPORTED) {
            if (REPORTED.add(slotId)) {
                LOGGER.warn("Level lists unknown card '{}': no slot with that id."
                        + " The card is skipped.", slotId);
            }
        }
    }

    /** Forgets which ids have been reported; used by reload so warnings reappear. */
    public static void resetReported() {
        synchronized (REPORTED) {
            REPORTED.clear();
        }
    }

    /**
     * The sprite for a card whose slot definition names no icon.
     *
     * <p>Forwarded to {@link EntityArt#sprite} rather than built here: the fallback
     * used to be "prefix + content path", which stopped being true the moment the
     * shipped art was grouped by kind. The card in the bar and the entity on the
     * board now resolve through the same answer.
     */
    private static Optional<Identifier> fallbackIcon(Slot.Kind kind, Identifier content) {
        if (kind == Slot.Kind.RESOURCE) {
            ResourceDef resource = BuiltInRegistries.RESOURCES.get(content);
            if (resource != null) {
                return Optional.of(resource.icon());
            }
        }
        return Optional.ofNullable(EntityArt.sprite(content));
    }

    private SlotResolver() {
    }
}
