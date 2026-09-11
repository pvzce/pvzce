package com.pvzce.common.core;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.ResourceDef;
import com.pvzce.api.content.SlotDef;
import com.pvzce.api.content.ToolDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.server.Slot;

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
        // Legacy level JSON may list a plant id directly instead of a slot id.
        PlantDef plant = BuiltInRegistries.PLANTS.get(slotId);
        if (plant != null) {
            return Optional.of(new ResolvedCard(slotId, slotId, Slot.Kind.PLANT,
                    plant.cost().amountOf(PvzceIds.SUN), plant.cost().cooldownTicks(), Slot.UNLIMITED_USES,
                    fallbackIcon(Slot.Kind.PLANT, slotId)));
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

    private static void reportUnknown(Identifier slotId) {
        synchronized (REPORTED) {
            if (REPORTED.add(slotId)) {
                System.err.println("[PVZCE] Level lists unknown card '" + slotId
                        + "': no slot, plant, tool or resource with that id. The card is skipped.");
            }
        }
    }

    /** Forgets which ids have been reported; used by reload so warnings reappear. */
    public static void resetReported() {
        synchronized (REPORTED) {
            REPORTED.clear();
        }
    }

    private static Optional<Identifier> fallbackIcon(Slot.Kind kind, Identifier content) {
        if (kind == Slot.Kind.RESOURCE) {
            ResourceDef resource = BuiltInRegistries.RESOURCES.get(content);
            if (resource != null) {
                return Optional.of(resource.icon());
            }
            return Optional.of(Identifier.withDefaultNamespace("textures/resource/" + content.path()));
        }
        return Optional.of(Identifier.withDefaultNamespace("textures/entities/" + content.path()));
    }

    private SlotResolver() {
    }
}
