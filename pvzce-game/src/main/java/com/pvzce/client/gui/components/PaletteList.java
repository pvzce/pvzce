package com.pvzce.client.gui.components;

import com.pvzce.api.entity.EntityKind;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.GuiText;
import com.pvzce.client.renderer.EntityTextures;
import com.pvzce.client.renderer.SceneTileRenderer;
import com.pvzce.client.renderer.SpriteRenderer;

import java.util.List;

/**
 * A selection list whose rows show the thing itself and a readable name.
 *
 * <p>The editor and the wave table used to print {@code GuiText.shortId(id)} -
 * {@code pvzce:pea_shooter} became {@code pea_shooter}, {@code pvzce:water}
 * became {@code water} - so choosing content meant reading English identifiers
 * and knowing what texture each one resolves to. Every row here draws what the game
 * will draw, plus the language file's name.
 *
 * <p><b>A row's picture is one of three things</b> ({@link Item.Icon}): a single PNG, a live
 * <em>rig</em>, or nothing at all. The rig case is the one that used to be broken: a plant or a
 * zombie has no single texture - its art is a controller file whose parts are separate PNGs hung
 * off bones - so every plant and zombie row in every palette drew the missing-texture
 * checkerboard. {@link EntityPreview} draws those, and {@link #iconFor} is where "which of the
 * three is this row" is decided, once.
 */
public final class PaletteList extends AbstractSelectionList<PaletteList.Item> {
    /** Which texture family an entry's icon comes from. */
    public enum Kind {
        /** Plants, zombies, projectiles: a rig when the content has one, else its sprite. */
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
     * @param icon     what to draw in the icon slot, or {@code null} for a swatch
     * @param name     display name (from the language file, already resolved)
     * @param subtitle optional second line, e.g. the cost or the rule value
     */
    public record Item(Identifier id, Icon icon, String name, String subtitle) {
        /**
         * What an icon slot draws.
         *
         * <p>Two shapes, because the game genuinely has two kinds of picture: content that is one
         * PNG, and content that is a skeleton. A caller that had only an {@code Identifier} had to
         * pick one meaning for it, and picking "a PNG" is what made 77 content ids draw a
         * missing-texture tile.
         */
        public sealed interface Icon {
            /** One texture, drawn as a square. */
            record Texture(Identifier texture) implements Icon {
            }

            /** A live rig, drawn in its own little viewport. */
            record Rig(String kind, Identifier contentId) implements Icon {
            }

            /** The texture behind this icon, or {@code null} when it is a rig. */
            default Identifier texture() {
                return this instanceof Texture t ? t.texture() : null;
            }

            /** The content behind this icon, or {@code null} when it is a texture. */
            default Identifier rigId() {
                return this instanceof Rig r ? r.contentId() : null;
            }
        }

        public static Item of(Kind kind, Identifier id, String subtitle) {
            return of(kind, defaultCategory(kind), id, subtitle);
        }

        /**
         * As {@link #of}, for a row whose registry its icon family does not pin down.
         *
         * <p>{@link Kind#ENTITY} covers plants, zombies and projectiles, whose language keys live
         * in three different categories - so a palette that lists zombies has to say so, or every
         * row shows a raw id. The category doubles as the entity's {@link EntityKind}, which is
         * what a rig preview needs; that is why the two arguments exist rather than one.
         */
        public static Item of(Kind kind, String category, Identifier id, String subtitle) {
            return new Item(id, iconFor(null, kind, category, id), GuiLang.name(category, id), subtitle);
        }

        /** As {@link #of}, resolving entity rows against the client's animation resources. */
        public static Item of(PvzceClient client, Kind kind, Identifier id, String subtitle) {
            return of(client, kind, defaultCategory(kind), id, subtitle);
        }

        public static Item of(PvzceClient client, Kind kind, String category, Identifier id,
                              String subtitle) {
            return new Item(id, iconFor(client, kind, category, id), GuiLang.name(category, id), subtitle);
        }

        /** The category a kind implies, for the callers that only ever list one registry. */
        private static String defaultCategory(Kind kind) {
            return switch (kind) {
                case ENTITY -> "plant";
                case SCENE -> "scene_element";
                case RESOURCE -> "resource";
                case PLAIN -> null;
            };
        }
    }

    private static final int MIN_ICON = 18;

    private final Kind kind;
    private final int iconSize;
    private final EntityPreview previews;

    public PaletteList(PvzceClient client, int x, int y, int width, int height, int entryHeight,
                       Kind kind) {
        // A taller row buys a bigger icon; 0.7 leaves room for the row padding and
        // the highlight bar, so the sprite never touches the selection outline.
        super(x, y, width, height, entryHeight, (renderClient, item, ix, iy) -> {
        });
        this.kind = kind;
        this.iconSize = Math.max(MIN_ICON, (int) (entryHeight * 0.7F));
        this.previews = new EntityPreview(client);
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

    /** Drops every live rig this list was holding; call it when the page that owns it goes away. */
    public void releasePreviews() {
        previews.release();
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
     * What a row's icon slot draws: a rig for skeleton content, a texture for everything else.
     *
     * <p>The order matters and is the whole fix. A plant or a zombie <em>declares</em> a texture,
     * but for all 77 built-in ones that path is the directory its part sprites live in, so
     * preferring the declared texture guarantees a miss. Content whose art really is one PNG
     * (projectiles, resources, a mod's flat sprite) has no animation file and falls through to the
     * texture; anything with neither draws the row's own swatch rather than a checkerboard.
     *
     * <p>Whether the content <em>is</em> skeleton content is answered from the data alone, so the
     * decision is testable without a window. Whether a rig can be <em>drawn</em> needs the
     * animation manager, and that is asked at draw time - a client with none falls back to the
     * swatch, which is what {@link EntityPreview#draw} answering {@code false} means.
     */
    public static Item.Icon iconFor(PvzceClient client, Kind kind, String category, Identifier id) {
        if (id == null || kind == null || kind == Kind.PLAIN) {
            return null;
        }
        if (kind == Kind.ENTITY && com.pvzce.common.core.EntityArt.animationFile(id) != null) {
            return new Item.Icon.Rig(rigKind(category), id);
        }
        Identifier texture = iconFor(kind, id);
        if (texture == null) {
            return null;
        }
        // A texture that is really a directory: the row is better off with its own swatch than
        // with the engine's missing-texture tile, and this is exactly the case that shipped.
        if (client != null && kind == Kind.ENTITY && !client.hasTexture(texture)) {
            return null;
        }
        return new Item.Icon.Texture(texture);
    }

    /** The entity kind a language category names; rigs are built per kind. */
    private static String rigKind(String category) {
        if (category == null) {
            return EntityKind.PLANT;
        }
        return switch (category) {
            case "zombie" -> EntityKind.ZOMBIE;
            case "projectile" -> EntityKind.PROJECTILE;
            case "resource" -> EntityKind.RESOURCE;
            default -> EntityKind.PLANT;
        };
    }

    private void renderRow(PvzceClient client, Item item, int x, int y) {
        int entryHeight = entryHeight();
        float iconY = y + (entryHeight - iconSize) / 2F;
        int textX = x;
        Item.Icon icon = item.icon();
        boolean drawn = icon != null && switch (icon) {
            case Item.Icon.Texture texture ->
                    drawTexture(client, texture.texture(), x, iconY);
            case Item.Icon.Rig rig ->
                    previews.draw(rig.contentId(), rig.kind(), x, iconY, iconSize, iconSize);
        };
        if (drawn) {
            textX = x + iconSize + 6;
        } else {
            // A rule row, or content with no picture at all: the swatch is what the eye lands on,
            // so the two columns line up with the icon rows above and below it.
            SpriteRenderer.solid(x, iconY, iconSize, iconSize, 0.1F, 0.28F, 0.34F, 0.42F, 1F);
            textX = x + iconSize + 6;
        }

        boolean subtitle = item.subtitle() != null && !item.subtitle().isBlank();
        String name = item.name() == null || item.name().isBlank() ? GuiText.shortId(item.id()) : item.name();
        float nameScale = subtitle ? 0.78F : 0.9F;
        // Vertically centre the text block in the row, whether it is one line or two.
        float nameY = subtitle
                ? y + entryHeight / 2F + 1F
                : y + (entryHeight - client.fonts().body().lineHeight(nameScale)) / 2F;
        client.fonts().body().draw(name, textX, nameY, nameScale, 1F, 1F, 1F, 1F);
        if (subtitle) {
            client.fonts().body().draw(item.subtitle(), textX, y + entryHeight / 2F - 11F, 0.68F,
                    0.78F, 0.84F, 0.88F, 1F);
        }
    }

    /** Draws one icon texture, answering whether it was really there. */
    private boolean drawTexture(PvzceClient client, Identifier texture, float x, float y) {
        if (texture == null || !client.hasTexture(texture)) {
            return false;
        }
        client.drawTexture(texture, x, y, iconSize, iconSize, 0.1F, 1F, 1F, 1F, 1F);
        return true;
    }
}
