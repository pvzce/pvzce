package com.pvzce.client.gui.components;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.GuiText;
import com.pvzce.client.renderer.EntityTextures;
import com.pvzce.client.renderer.SceneTileRenderer;
import com.pvzce.client.animation.AnimationManager;
import com.pvzce.client.renderer.SpriteRenderer;

import java.util.List;

/**
 * A selection list whose rows show a sprite and a readable name.
 *
 * <p>The editor and the wave table used to print {@code GuiText.shortId(id)} -
 * {@code pvzce:pea_shooter} became {@code pea_shooter}, {@code pvzce:water}
 * became {@code water} - so choosing content meant reading English identifiers
 * and knowing what texture each one resolves to. Every row here draws the same
 * texture the game will draw, plus the language file's name.
 *
 * <p>{@link Kind} decides which texture prefix a row uses, because the answer
 * genuinely differs per registry: a plant's sprite is {@code textures/entities/},
 * a scene tile's is {@code textures/scene/}. Passing the wrong one used to be a
 * silent pink-box fallback.
 */
public final class PaletteList extends AbstractSelectionList<PaletteList.Item> {
    /** Which texture family an entry's icon comes from. */
    public enum Kind {
        /** Plants, zombies, projectiles: {@code textures/entities/<path>}. */
        ENTITY,
        /** Terrain: {@code textures/scene/<path>}. */
        SCENE,
        /** Resources: {@code textures/resource/<path>}. */
        RESOURCE,
        /** UI-only entries (game rules): no icon, a coloured swatch instead. */
        PLAIN
    }

    /**
     * One palette row.
     *
     * @param id       content id, or a synthetic id for UI-only rows
     * @param icon     texture to draw, or {@code null} for {@link Kind#PLAIN}
     * @param name     display name (from the language file, already resolved)
     * @param subtitle optional second line, e.g. the cost or the rule value
     */
    public record Item(Identifier id, Identifier icon, String name, String subtitle) {
        public static Item of(Kind kind, Identifier id, String subtitle) {
            return new Item(id, iconFor(kind, id), GuiLang.name(id), subtitle);
        }

        /** As {@link #of}, but prefers the game's animation art for entity rows. */
        public static Item of(PvzceClient client, Kind kind, Identifier id, String subtitle) {
            return new Item(id, iconFor(client, kind, id), GuiLang.name(id), subtitle);
        }
    }

    private static final int MIN_ICON = 18;

    private final Kind kind;
    private final int iconSize;

    public PaletteList(int x, int y, int width, int height, int entryHeight, Kind kind) {
        // A taller row buys a bigger icon; 0.7 leaves room for the row padding and
        // the highlight bar, so the sprite never touches the selection outline.
        super(x, y, width, height, entryHeight, (client, item, ix, iy) -> {
        });
        this.kind = kind;
        this.iconSize = Math.max(MIN_ICON, (int) (entryHeight * 0.7F));
        setEntryRenderer(this::renderRow);
    }

    public Kind kind() {
        return kind;
    }

    public void setItems(List<Item> items) {
        setEntries(items);
    }

    /** The selected item's id, or {@code null} when nothing is selected. */
    public Identifier selectedId() {
        Item selected = selected();
        return selected == null ? null : selected.id();
    }

    /** Resolves the icon for a kind; {@link Kind#PLAIN} rows have none. */
    public static Identifier iconFor(Kind kind, Identifier id) {
        if (id == null || kind == null || kind == Kind.PLAIN) {
            return null;
        }
        return switch (kind) {
            case ENTITY -> EntityTextures.forEntity(id);
            case SCENE -> SceneTileRenderer.sceneTexture(id.toString());
            case RESOURCE -> EntityTextures.forResource(id);
            case PLAIN -> null;
        };
    }

    /**
     * The icon for an entity row: the game's own art when the content is animated.
     *
     * <p>{@code textures/entities/<id>.png} is the pre-controller sprite and looks
     * nothing like what the board draws, which is why the editor's plant and zombie
     * palettes looked like placeholder art. Content with an animation resource gets a
     * texture taken from that resource instead; anything without one keeps the sprite,
     * which is still the only picture it has.
     */
    public static Identifier entityIcon(AnimationManager animations, Identifier id) {
        if (id == null) {
            return null;
        }
        if (animations != null) {
            Identifier animated = animations.iconTexture(id).orElse(null);
            if (animated != null) {
                return animated;
            }
        }
        return EntityTextures.forEntity(id);
    }

    /**
     * Resolves a row's icon against the animation resources, falling back to
     * {@link #iconFor} for the kinds that have no animation.
     */
    public static Identifier iconFor(PvzceClient client, Kind kind, Identifier id) {
        if (kind == Kind.ENTITY) {
            return entityIcon(client == null ? null : client.animations(), id);
        }
        return iconFor(kind, id);
    }

    private void renderRow(PvzceClient client, Item item, int x, int y) {
        int entryHeight = entryHeight();
        float iconY = y + (entryHeight - iconSize) / 2F;
        int textX = x;
        if (item.icon() != null) {
            client.drawTexture(item.icon(), x, iconY, iconSize, iconSize, 0.1F, 1F, 1F, 1F, 1F);
            textX = x + iconSize + 6;
        } else {
            // A rule row: the swatch is what the eye lands on, so the two columns
            // line up with the icon rows above and below it.
            SpriteRenderer.solid(x, iconY, iconSize, iconSize, 0.1F, 0.28F, 0.34F, 0.42F, 1F);
            textX = x + iconSize + 6;
        }

        boolean subtitle = item.subtitle() != null && !item.subtitle().isBlank();
        String name = item.name() == null || item.name().isBlank() ? GuiText.shortId(item.id()) : item.name();
        float nameScale = subtitle ? 0.78F : 0.9F;
        // Vertically centre the text block in the row, whether it is one line or two.
        float nameY = subtitle
                ? y + entryHeight / 2F + 1F
                : y + (entryHeight - client.font().lineHeight(nameScale)) / 2F;
        client.font().draw(name, textX, nameY, nameScale, 1F, 1F, 1F, 1F);
        if (subtitle) {
            client.font().draw(item.subtitle(), textX, y + entryHeight / 2F - 11F, 0.68F,
                    0.78F, 0.84F, 0.88F, 1F);
        }
    }
}
