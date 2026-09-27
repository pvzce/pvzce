package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

import java.util.Optional;

/**
 * A card-slot definition: plant card, resource card, tool card or zombie card.
 * {@code content} points at a plant/resource/tool/zombie registry id; {@code cost}
 * may override the referenced content's own cost. {@code icon} optionally
 * overrides the card icon texture (top-left origin, same texture id space as
 * other resources).
 */
public record SlotDef(Identifier id, Kind kind, Identifier content, ResourceCost cost,
                      Optional<Identifier> icon) {
    /** Backwards-compatible constructor: derive the card icon from the content id. */
    public SlotDef(Identifier id, Kind kind, Identifier content, ResourceCost cost) {
        this(id, kind, content, cost, Optional.empty());
    }

    public enum Kind {
        PLANT("plant"), RESOURCE("resource"), TOOL("tool"), ZOMBIE("zombie");

        private final String json;

        Kind(String json) {
            this.json = json;
        }

        public String json() {
            return json;
        }

        public static final Codec<Kind> CODEC = Codec.STRING.xmap(name -> {
            for (Kind kind : values()) {
                if (kind.json.equalsIgnoreCase(name)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Unknown card kind: " + name);
        }, Kind::json);
    }

    public static final Codec<SlotDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(SlotDef::id),
            Kind.CODEC.optionalFieldOf("kind", Kind.PLANT).forGetter(SlotDef::kind),
            Identifier.CODEC.fieldOf("content").forGetter(SlotDef::content),
            ResourceCost.CODEC.optionalFieldOf("cost", ResourceCost.FREE).forGetter(SlotDef::cost),
            Identifier.CODEC.optionalFieldOf("icon").forGetter(SlotDef::icon)
    ).apply(i, SlotDef::new));
}
