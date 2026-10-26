package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.SeedCardRenderer;
import com.pvzce.client.gui.almanac.AlmanacEntries;
import com.pvzce.client.gui.components.EntityPreview;
import com.pvzce.client.gui.components.NinePatch;
import com.pvzce.client.input.ScrollRegion;
import com.pvzce.client.renderer.font.Fonts;
import com.pvzce.client.renderer.font.TextStyle;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SlotResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * An 800x600 almanac, contain-fitted as one composition, including its text and hit targets.
 * The shipped backgrounds supply the wood and stone frames; the shelf and detail text occupy
 * their original openings. Registries supply the catalogue, including entries beyond one sheet.
 */
public final class AlmanacScreen extends Screen {
    private static final float NATIVE_WIDTH = 800F;
    private static final float NATIVE_HEIGHT = 600F;
    private static final Identifier BACKGROUND_INDEX = art("index_background");
    private static final Identifier BACKGROUND_PLANTS = art("plant_background");
    private static final Identifier BACKGROUND_ZOMBIES = art("zombie_background");
    private static final Identifier CARD_PLANT = art("plant_card");
    private static final Identifier CARD_ZOMBIE = art("zombie_card");
    private static final Identifier ZOMBIE_WINDOW = art("zombie_window_fill");
    private static final Identifier ZOMBIE_FRAME = art("zombie_window_frame");
    private static final Identifier BUTTON_INDEX = art("button_index");
    private static final Identifier BUTTON_INDEX_LIT = art("button_index_highlight");
    private static final Identifier BUTTON_CLOSE = art("button_close");
    private static final Identifier BUTTON_CLOSE_LIT = art("button_close_highlight");
    private static final Identifier LOCK_BADGE = Identifier.withDefaultNamespace("textures/gui/icon/lock");
    private static final Identifier GROUND_DAY =
            Identifier.withDefaultNamespace("textures/gui/screen/level/almanac_groundday");
    private static final Identifier WOOD_BUTTON =
            Identifier.withDefaultNamespace("textures/gui/screen/seeds/seed_chooser_button");

    private static final float GRID_X = 26F;
    private static final float GRID_Y = 90F;
    private static final float DETAIL_X = 458F;
    private static final float DETAIL_Y = 80F;
    private static final float DETAIL_W = 324F;
    private static final float FOOTER_Y = 568F;
    private static final float FOOTER_H = 26F;

    /** Sheet geometry must stop before the detail frame and the footer. */
    private record Shelf(int columns, int rows, float cardW, float cardH, float pitchX, float pitchY) {
        int capacity() {
            return columns * rows;
        }
    }

    private static final Shelf PLANT_SHELF = new Shelf(8, 6, 46F, 70F, 52F, 78F);
    private static final Shelf ZOMBIE_SHELF = new Shelf(5, 5, 74F, 74F, 85F, 80F);

    private static final float[] GOLD = {0.96F, 0.73F, 0.22F};
    private static final float[] GREEN = {0.25F, 0.88F, 0.08F};
    private static final float[] INK = {0.30F, 0.14F, 0.07F};
    private static final float[] BLUE_INK = {0.12F, 0.16F, 0.32F};
    private static final float[] RED_INK = {0.52F, 0.19F, 0.11F};
    private static final float[] PALE = {0.96F, 0.93F, 0.84F};
    private static final TextStyle HEADING = TextStyle.outline(0.10F, 0.07F, 0.04F, 1F, 0.6F);

    private final List<AlmanacEntries.Catalogue> catalogues = new ArrayList<>();
    private final EntityPreview previews;
    /** -1 is the index. */
    private int page = -1;
    private int selected;
    private AlmanacEntries.Page wanted;
    private float scale = 1F;
    private float originX;
    private float originY;
    /** Offset within long detail prose, in native pixels. */
    private float textScroll;
    private float maxTextScroll;

    private record Hit(float x, float y, float w, float h, Runnable action) {
        boolean contains(float nx, float ny) {
            return nx >= x && nx <= x + w && ny >= y && ny <= y + h;
        }
    }

    private final List<Hit> hits = new ArrayList<>();

    public AlmanacScreen(PvzceClient client) {
        super(client);
        previews = new EntityPreview(client, ZOMBIE_SHELF.capacity() + 1);
    }

    private static Identifier art(String name) {
        return Identifier.withDefaultNamespace("textures/gui/almanac/" + name);
    }

    @Override
    protected void init() {
        if (client.music() != null) {
            client.music().ensureMenu("pvzce:music/choose_your_seeds");
        }
        if (client.connection() != null) {
            client.connection().send(new com.pvzce.common.network.packet.RequestLevelListC2S(client.currentWorld()));
        }
        catalogues.clear();
        catalogues.addAll(AlmanacEntries.all());
        if (wanted != null) {
            AlmanacEntries.Page target = wanted;
            wanted = null;
            openPage(target);
        }
    }

    @Override
    protected void onRemoved() {
        previews.release();
    }

    private AlmanacEntries.Catalogue current() {
        return page < 0 ? null : catalogues.get(page);
    }

    public void showIndex() {
        page = -1;
        selected = 0;
        wanted = null;
        resetDetail();
        previews.release();
    }

    private void openPage(AlmanacEntries.Page target) {
        for (int i = 0; i < catalogues.size(); i++) {
            if (catalogues.get(i).page() == target) {
                page = i;
                selected = 0;
                resetDetail();
                previews.release();
                return;
            }
        }
    }

    /** Remembers a request made before the first frame initializes the catalogue. */
    public void show(AlmanacEntries.Page target) {
        if (catalogues.isEmpty()) {
            wanted = target;
        } else {
            openPage(target);
        }
    }

    /** Also brings the selected entry's sheet into view. */
    public void selectEntry(int index) {
        var catalogue = current();
        if (catalogue != null && catalogue.size() > 0) {
            int next = Math.floorMod(index, catalogue.size());
            if (next / shelf().capacity() != selected / shelf().capacity()) {
                previews.release();
            }
            selected = next;
            resetDetail();
        }
    }

    private void resetDetail() {
        textScroll = 0F;
        maxTextScroll = 0F;
    }

    private Shelf shelf() {
        return current().page() == AlmanacEntries.Page.ZOMBIES ? ZOMBIE_SHELF : PLANT_SHELF;
    }

    @Override
    public void render() {
        client.beginGuiView();
        scale = Math.min(client.guiWidth() / NATIVE_WIDTH, client.guiHeight() / NATIVE_HEIGHT);
        originX = (client.guiWidth() - NATIVE_WIDTH * scale) / 2F;
        originY = (client.guiHeight() - NATIVE_HEIGHT * scale) / 2F;
        hits.clear();
        client.drawSolid(0, 0, client.guiWidth(), client.guiHeight(), -2F, 0.09F, 0.05F, 0.03F, 1F);
        if (page < 0) {
            renderIndex();
        } else {
            renderCatalogue();
        }
        drawFooter();
    }

    private float sx(float x) {
        return originX + x * scale;
    }

    /** Native art uses a top-left origin; GUI uses a bottom-left origin. */
    private float sy(float y) {
        return originY + (NATIVE_HEIGHT - y) * scale;
    }

    private float sw(float length) {
        return length * scale;
    }

    private float nativeX(double x) {
        return (float) ((x - originX) / scale);
    }

    private float nativeY(double y) {
        return NATIVE_HEIGHT - (float) ((y - originY) / scale);
    }

    private void texture(Identifier id, float x, float y, float w, float h) {
        client.drawTexture(id, sx(x), sy(y + h), sw(w), sw(h), 0F, 1F, 1F, 1F, 1F);
    }

    private void renderIndex() {
        texture(BACKGROUND_INDEX, 0F, 0F, NATIVE_WIDTH, NATIVE_HEIGHT);
        title(GuiLang.raw("gui.pvzce.almanac.index_title", "Almanac - Index"), PALE, 108F, 584F);
        // These frames are already painted on the background. Keep their bevels and shadows.
        indexPanel(AlmanacEntries.Page.PLANTS, 26F, 172F, 365F, 250F);
        indexPanel(AlmanacEntries.Page.ZOMBIES, 410F, 169F, 365F, 258F);
        drawEntity(AlmanacEntries.Page.PLANTS, Identifier.withDefaultNamespace("sunflower"),
                149F, 221F, 116F, 116F);
        drawEntity(AlmanacEntries.Page.ZOMBIES, firstOf(AlmanacEntries.Page.ZOMBIES),
                518F, 208F, 148F, 136F);
        indexButton(AlmanacEntries.Page.PLANTS, 130F, 349F, GOLD,
                GuiLang.raw("gui.pvzce.almanac.view_plants", "View Plants"));
        indexButton(AlmanacEntries.Page.ZOMBIES, 488F, 349F, GREEN,
                GuiLang.raw("gui.pvzce.almanac.view_zombies", "View Zombies"));
        texturedButton(BUTTON_INDEX, BUTTON_INDEX_LIT, 321F, 490F, 158F,
                GuiLang.raw("gui.pvzce.almanac.view_resources", "View Resources"),
                () -> openPage(AlmanacEntries.Page.RESOURCES));
    }

    private void indexPanel(AlmanacEntries.Page target, float x, float y, float w, float h) {
        if (hovering(x, y, w, h)) {
            client.drawSolid(sx(x + 18F), sy(y + h - 18F), sw(w - 36F), sw(h - 36F),
                    0.1F, 1F, 0.85F, 0.45F, 0.045F);
        }
        hits.add(new Hit(x, y, w, h, () -> openPage(target)));
    }

    private Identifier firstOf(AlmanacEntries.Page target) {
        return catalogues.stream().filter(c -> c.page() == target).findFirst().orElseThrow().at(0);
    }

    private void indexButton(AlmanacEntries.Page target, float x, float y, float[] ink, String label) {
        boolean zombies = target == AlmanacEntries.Page.ZOMBIES;
        if (zombies) {
            stoneButton(x, y, 204F, 36F);
        } else {
            texture(WOOD_BUTTON, x, y, 156F, 36F);
        }
        centred(client.fonts().button(), label, x + 5F, zombies ? 194F : 146F, y + 25F, 21F,
                hovering(x, y, zombies ? 204F : 156F, 36F) ? PALE : ink, HEADING);
        hits.add(new Hit(x, y, zombies ? 204F : 156F, 36F, () -> openPage(target)));
    }

    private void renderCatalogue() {
        var catalogue = current();
        boolean zombies = catalogue.page() == AlmanacEntries.Page.ZOMBIES;
        texture(zombies ? BACKGROUND_ZOMBIES : BACKGROUND_PLANTS, 0F, 0F, NATIVE_WIDTH, NATIVE_HEIGHT);
        title(pageTitle(catalogue.page()), zombies ? GREEN : GOLD, 70F, 660F);
        if (catalogue.size() == 0) {
            text(GuiLang.raw("gui.pvzce.almanac.empty", "Nothing here yet"), 40F, 180F, 18F, INK);
            return;
        }
        renderShelf(catalogue);
        renderDetail(catalogue.page(), catalogue.at(selected));
    }

    private void renderShelf(AlmanacEntries.Catalogue catalogue) {
        Shelf shelf = shelf();
        int first = selected / shelf.capacity() * shelf.capacity();
        int end = Math.min(catalogue.size(), first + shelf.capacity());
        for (int index = first; index < end; index++) {
            int local = index - first;
            float x = GRID_X + local % shelf.columns() * shelf.pitchX();
            float y = GRID_Y + local / shelf.columns() * shelf.pitchY();
            drawShelfCard(catalogue.page(), catalogue.at(index), x, y, shelf, index == selected);
            int entry = index;
            hits.add(new Hit(x, y, shelf.cardW(), shelf.cardH(), () -> selectEntry(entry)));
        }
        if (catalogue.size() > shelf.capacity()) {
            int sheets = (catalogue.size() + shelf.capacity() - 1) / shelf.capacity();
            float y = 568F;
            sheetButton(199F, y, "‹", -1);
            centred(client.fonts().bodyMedium(), (first / shelf.capacity() + 1) + " / " + sheets,
                    246F, 90F, y + 18F, 13F, INK, TextStyle.NONE);
            sheetButton(337F, y, "›", 1);
        }
    }

    private void stoneButton(float x, float y, float w, float h) {
        String prefix = "textures/gui/button/button_" + (hovering(x, y, w, h) ? "down_" : "");
        NinePatch.drawThreeSlice(client, Identifier.withDefaultNamespace(prefix + "left"),
                Identifier.withDefaultNamespace(prefix + "middle"), Identifier.withDefaultNamespace(prefix + "right"),
                sx(x), sy(y + h), sw(w), sw(h), 0.1F,
                sw(NinePatch.BUTTON_LEFT * h / NinePatch.BUTTON_NATIVE_HEIGHT),
                sw(NinePatch.BUTTON_RIGHT * h / NinePatch.BUTTON_NATIVE_HEIGHT), 1F, 1F, 1F, 1F);
    }

    private void sheetButton(float x, float y, String label, int direction) {
        stoneButton(x, y, 46F, FOOTER_H);
        centred(client.fonts().button(), label, x, 46F, y + 18F, 20F, PALE, TextStyle.NONE);
        hits.add(new Hit(x, y, 46F, FOOTER_H, () -> changeSheet(direction)));
    }

    private void drawShelfCard(AlmanacEntries.Page target, Identifier id, float x, float y,
                               Shelf shelf, boolean selectedCard) {
        float w = shelf.cardW(), h = shelf.cardH();
        boolean zombies = target == AlmanacEntries.Page.ZOMBIES;
        if (selectedCard || hovering(x, y, w, h)) {
            client.drawSolid(sx(x - 2F), sy(y + h + 2F), sw(w + 4F), sw(h + 4F),
                    0.1F, 1F, 0.82F, 0.22F, selectedCard ? 1F : 0.55F);
        }
        if (zombies) {
            texture(ZOMBIE_WINDOW, x, y, w, h);
            drawEntity(target, id, x + 5F, y + 5F, w - 10F, h - 10F);
            texture(ZOMBIE_FRAME, x, y, w, h);
        } else {
            Identifier icon = previewTexture(target, id);
            int amount = target == AlmanacEntries.Page.PLANTS ? plantCost(id) : resourceValue(id);
            SeedCardRenderer.draw(client, SeedCardRenderer.CardModel.of(icon,
                            SeedCardRenderer.CardKind.PLANT, amount),
                    sx(x), sy(y + h), sw(w), sw(h));
        }
        if (!isUnlocked(target, id)) {
            client.drawSolid(sx(x), sy(y + h), sw(w), sw(h), 0.3F, 0.05F, 0.04F, 0.03F, 0.55F);
            texture(LOCK_BADGE, x + (w - 18F) / 2F, y + (h - 18F) / 2F, 18F, 18F);
        }
    }

    private void renderDetail(AlmanacEntries.Page target, Identifier id) {
        boolean zombies = target == AlmanacEntries.Page.ZOMBIES;
        texture(zombies ? CARD_ZOMBIE : CARD_PLANT, DETAIL_X, DETAIL_Y, DETAIL_W, zombies ? 484F : 478F);
        // Openings measured in the original 324px card coordinates, independent of HD texture size.
        float imageX = DETAIL_X + 64F;
        float imageY = DETAIL_Y + (zombies ? 44F : 22F);
        float imageW = 196F;
        float imageH = zombies ? 189F : 146F;
        float crop = (1F - imageH / imageW) / 2F;
        client.drawTextureRegion(GROUND_DAY, 0F, crop, 1F, 1F - crop,
                sx(imageX), sy(imageY + imageH), sw(imageW), sw(imageH), 0F, 1F, 1F, 1F, 1F);
        drawEntity(target, id, imageX + 7F, imageY + 5F, imageW - 14F, imageH - 10F);
        centred(client.fonts().button(), GuiLang.name(target.category(), id),
                DETAIL_X + 24F, 276F, DETAIL_Y + (zombies ? 270F : 204F), 22F,
                zombies ? GREEN : GOLD, HEADING);
        renderDetailText(target, id);
    }

    /** Long descriptions scroll inside the paper, leaving the name and cost band fixed. */
    private void renderDetailText(AlmanacEntries.Page target, Identifier id) {
        boolean zombies = target == AlmanacEntries.Page.ZOMBIES;
        float x = DETAIL_X + 27F, y = DETAIL_Y + (zombies ? 291F : 225F);
        float w = 265F, h = zombies ? 153F : 200F;
        float size = 14F;
        var body = client.fonts().body();
        List<String> description = wrap(GuiLang.contentOr(target.category(), id, "desc", ""), w - 12F, size);
        List<String> stats = new ArrayList<>();
        for (String line : statLines(target, id)) {
            stats.addAll(wrap(line, w - 12F, size));
        }
        List<String> flavor = wrap(GuiLang.contentOr(target.category(), id, "flavor", ""), w - 12F, size);
        float step = body.lineHeight(fontScale(body, size)) / scale + 3F;
        float contentH = (description.size() + stats.size() + flavor.size()) * step + 16F;
        maxTextScroll = Math.max(0F, contentH - h);
        textScroll = Math.min(textScroll, maxTextScroll);
        client.clipping().push(sx(x), sy(y + h), sw(w), sw(h));
        try {
            float cursor = y + 13F - textScroll;
            cursor = lines(description, x + 2F, cursor, size, step, BLUE_INK);
            cursor = lines(stats, x + 2F, cursor + 8F, size, step, RED_INK);
            lines(flavor, x + 2F, cursor + 8F, size, step, INK);
        } finally {
            client.clipping().pop();
        }
        if (maxTextScroll > 0F) {
            float track = h - 8F;
            float thumb = Math.max(16F, track * h / contentH);
            client.drawSolid(sx(x + w - 3F), sy(y + h - 4F), sw(2F), sw(track),
                    0.3F, 0.30F, 0.17F, 0.09F, 0.18F);
            float top = y + 4F + (track - thumb) * textScroll / maxTextScroll;
            client.drawSolid(sx(x + w - 3F), sy(top + thumb), sw(2F), sw(thumb),
                    0.4F, 0.30F, 0.17F, 0.09F, 0.65F);
        }
        if (target == AlmanacEntries.Page.PLANTS) {
            String cost = stat("cost", "Cost") + " " + plantCost(id);
            String recharge = stat("recharge", "Recharge") + " "
                    + GuiLang.raw("gui.pvzce.almanac.seconds", "{0}s")
                    .replace("{0}", trim(plantCooldown(id) / 60F));
            text(cost, x + 2F, DETAIL_Y + 442F, 12F, RED_INK);
            float rechargeW = width(recharge, 12F);
            text(recharge, x + w - rechargeW, DETAIL_Y + 442F, 12F, RED_INK);
        }
        if (!isUnlocked(target, id)) {
            centred(client.fonts().bodyMedium(), GuiLang.raw("gui.pvzce.almanac.locked", "Not yet unlocked"),
                    DETAIL_X + 24F, 276F, DETAIL_Y + 474F, 11F, INK, TextStyle.NONE);
        }
    }

    private List<String> wrap(String value, float width, float size) {
        if (value.isBlank()) return List.of();
        var font = client.fonts().body();
        return font.wrapLines(value, sw(width), fontScale(font, size));
    }

    private float lines(List<String> lines, float x, float y, float size, float step, float[] ink) {
        for (String line : lines) {
            text(line, x, y, size, ink);
            y += step;
        }
        return y;
    }

    private List<String> statLines(AlmanacEntries.Page target, Identifier id) {
        return switch (target) {
            case PLANTS -> List.of(stat("health", "Health") + " " + BuiltInRegistries.PLANTS.get(id).health());
            case ZOMBIES -> {
                var zombie = BuiltInRegistries.ZOMBIES.get(id);
                yield List.of(stat("toughness", "Toughness") + " " + zombie.health(),
                        stat("speed", "Speed") + " " + trim(zombie.moveSpeed())
                                + GuiLang.raw("gui.pvzce.almanac.cells_per_second", " cells/s"),
                        stat("bite", "Bite") + " " + zombie.biteDamage());
            }
            case RESOURCES -> List.of(stat("value", "Value") + " " + resourceValue(id));
        };
    }

    private String stat(String key, String fallback) {
        return GuiLang.raw("gui.pvzce.almanac.stat." + key, fallback) + ":";
    }

    private static String trim(float value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private int plantCost(Identifier id) {
        return SlotResolver.resolve(id).orElseThrow().costSun();
    }

    private int plantCooldown(Identifier id) {
        return SlotResolver.resolve(id).orElseThrow().cooldownTicks();
    }

    private int resourceValue(Identifier id) {
        return BuiltInRegistries.RESOURCES.get(id).defaultValue();
    }

    private boolean isUnlocked(AlmanacEntries.Page target, Identifier id) {
        if (target != AlmanacEntries.Page.PLANTS) return true;
        Identifier card = BuiltInRegistries.SLOT_TYPES.containsKey(id) ? id
                : BuiltInRegistries.SLOT_TYPES.keySet().stream()
                .filter(slot -> id.equals(SlotResolver.resolve(slot).map(SlotResolver.ResolvedCard::content).orElse(null)))
                .findFirst().orElse(id);
        return SlotResolver.owns(client.profile().unlocked(), client.profile().unlockAll(), card);
    }

    private Identifier previewTexture(AlmanacEntries.Page target, Identifier id) {
        if (target == AlmanacEntries.Page.RESOURCES) return BuiltInRegistries.RESOURCES.get(id).icon();
        return SlotResolver.resolve(id).flatMap(SlotResolver.ResolvedCard::icon).orElse(null);
    }

    private void drawEntity(AlmanacEntries.Page target, Identifier id, float x, float y, float w, float h) {
        if (previews.draw(id, target.entityKind(), sx(x), sy(y + h), sw(w), sw(h))) return;
        if (target == AlmanacEntries.Page.ZOMBIES || id == null) return;
        Identifier sprite = previewTexture(target, id);
        if (sprite == null) return;
        var texture = client.textures().getOrLoad(sprite);
        float fit = Math.min(w / texture.width(), h / texture.height());
        float dw = texture.width() * fit, dh = texture.height() * fit;
        texture(sprite, x + (w - dw) / 2F, y + (h - dh) / 2F, dw, dh);
    }

    private String pageTitle(AlmanacEntries.Page target) {
        return GuiLang.raw("gui.pvzce.almanac.page_title_" + target.name().toLowerCase(java.util.Locale.ROOT),
                "Almanac - " + target.name());
    }

    private void title(String label, float[] ink, float x, float w) {
        centred(client.fonts().button(), label, x, w, 52F, 30F, ink, HEADING);
    }

    private void drawFooter() {
        if (page >= 0) {
            texturedButton(BUTTON_INDEX, BUTTON_INDEX_LIT, 32F, FOOTER_Y, 164F,
                    GuiLang.raw("gui.pvzce.almanac.index_button", "Almanac Index"), this::showIndex);
        }
        texturedButton(BUTTON_CLOSE, BUTTON_CLOSE_LIT, 678F, FOOTER_Y, 89F,
                GuiLang.raw("gui.pvzce.almanac.close", "Close"), this::requestClose);
    }

    private void texturedButton(Identifier normal, Identifier lit, float x, float y, float w,
                                String label, Runnable action) {
        texture(hovering(x, y, w, FOOTER_H) ? lit : normal, x, y, w, FOOTER_H);
        // Leave the arrow / X printed on the texture uncovered.
        float inset = normal == BUTTON_INDEX ? 25F : 6F;
        float reserve = normal == BUTTON_CLOSE ? 27F : 6F;
        centred(client.fonts().button(), label, x + inset, w - inset - reserve, y + 18F,
                15F, BLUE_INK, TextStyle.NONE);
        hits.add(new Hit(x, y, w, FOOTER_H, action));
    }

    private boolean hovering(float x, float y, float w, float h) {
        var window = client.window();
        if (window == null) return false;
        float nx = nativeX(client.guiMouseX(window.cursorX()));
        float ny = nativeY(client.guiMouseY(window.cursorY()));
        return nx >= x && nx <= x + w && ny >= y && ny <= y + h;
    }

    /** Font em, positions and wrapping all scale with the same 800x600 canvas. */
    private float fontScale(Fonts.FontRole font, float nativeSize) {
        return sw(nativeSize) / font.lineHeight(1F);
    }

    private void text(String label, float x, float baseline, float size, float[] ink) {
        var font = client.fonts().body();
        font.draw(label, sx(x), sy(baseline), fontScale(font, size), ink[0], ink[1], ink[2], 1F);
    }

    private float width(String label, float size) {
        var font = client.fonts().body();
        return font.width(label, fontScale(font, size)) / scale;
    }

    private void centred(Fonts.FontRole font, String label, float x, float w, float baseline,
                          float size, float[] ink, TextStyle style) {
        float fs = fontScale(font, size);
        float measured = font.width(label, fs);
        if (measured > sw(w)) fs *= sw(w) / measured;
        font.drawCentered(label, sx(x + w / 2F), sy(baseline), fs, ink[0], ink[1], ink[2], 1F, style);
    }

    @Override
    protected void onMouseClicked(double guiX, double guiY, int button) {
        if (button != 0) return;
        float nx = nativeX(guiX), ny = nativeY(guiY);
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.contains(nx, ny)) {
                hit.action().run();
                return;
            }
        }
    }

    private boolean overDetail(double guiX, double guiY) {
        float x = nativeX(guiX), y = nativeY(guiY);
        return page >= 0 && x >= DETAIL_X + 24F && x <= DETAIL_X + 300F
                && y >= DETAIL_Y + (current().page() == AlmanacEntries.Page.ZOMBIES ? 285F : 220F)
                && y <= DETAIL_Y + 444F;
    }

    @Override
    protected void onMouseScrolled(double guiX, double guiY, double amount) {
        if (overDetail(guiX, guiY)) {
            textScroll = Math.max(0F, Math.min(maxTextScroll, textScroll - (float) amount * 24F));
        } else if (page >= 0 && amount != 0) {
            select(amount > 0 ? -1 : 1);
        }
    }

    @Override
    protected ScrollRegion onScrollRegionAt(double guiX, double guiY) {
        if (page < 0) return null;
        return overDetail(guiX, guiY) ? ScrollRegion.dragsContent(ScrollRegion.Axis.VERTICAL, sw(24F))
                : ScrollRegion.mirrorsWheel(ScrollRegion.Axis.VERTICAL, ScrollRegion.DEFAULT_STEP);
    }

    @Override
    public void keyPressed(int key) {
        if (page >= 0) {
            switch (key) {
                case org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT -> { select(1); return; }
                case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT -> { select(-1); return; }
                case org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN -> { select(shelf().columns()); return; }
                case org.lwjgl.glfw.GLFW.GLFW_KEY_UP -> { select(-shelf().columns()); return; }
                case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN -> { changeSheet(1); return; }
                case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP -> { changeSheet(-1); return; }
                case org.lwjgl.glfw.GLFW.GLFW_KEY_HOME -> { selectEntry(0); return; }
                case org.lwjgl.glfw.GLFW.GLFW_KEY_END -> { selectEntry(current().size() - 1); return; }
                case org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE -> { showIndex(); return; }
                default -> { }
            }
        }
        super.keyPressed(key);
    }

    private void select(int delta) {
        selectEntry(selected + delta);
    }

    private void changeSheet(int delta) {
        int capacity = shelf().capacity();
        int count = current().size();
        int sheets = (count + capacity - 1) / capacity;
        if (sheets == 0) return;
        int sheet = Math.floorMod(selected / capacity + delta, sheets);
        selectEntry(Math.min(sheet * capacity + selected % capacity, count - 1));
    }

    /** -1 on the index, otherwise the catalogue being read. */
    public int pageIndex() {
        return page;
    }

    public Identifier currentEntry() {
        var catalogue = current();
        return catalogue == null ? null : catalogue.at(selected);
    }

    public int entryCount() {
        var catalogue = current();
        return catalogue == null ? 0 : catalogue.size();
    }
}
