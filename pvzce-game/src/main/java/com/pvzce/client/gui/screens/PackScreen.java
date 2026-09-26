package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.AbstractSelectionList;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.layout.MenuPageCanvas;
import com.pvzce.client.gui.layout.MenuPageCanvas.Canvas;
import com.pvzce.common.resource.PackSelection;
import com.pvzce.common.resource.PvzceResourceManager.AvailablePack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The packs page: what is in this game directory, what each pack holds, and which ones are on.
 *
 * <p>Shaped like {@code ModsScreen} - a list on the left, the selected entry's detail on the right
 * - because the question is the same one ("what is installed, and what is this one?"), and two
 * pages that answer it should not be two different layouts. What it now shares with the shop
 * instead of with the mod list is its <em>surface</em>: both are {@link MenuPageCanvas} pages, so
 * the two list-and-detail columns stand on wooden panels rather than on dark rectangles drawn
 * straight over a screenshot of the title screen. That was the visible defect here - text over a
 * photograph, and a list whose background ended where its last row did.
 *
 * <h2>Switching a pack off is a file plus an order</h2>
 *
 * <p>The button writes {@code config/pvzce-packs.json} and sends a
 * {@link com.pvzce.common.network.packet.ReloadPacksC2S}; the server reloads first and its answer
 * is what makes the client rebuild its own caches (see {@code PvzceClient.requestPackReload}).
 * The page reloads nothing itself and shows the <em>loaded</em> state rather than the state it
 * just wrote: the two agree only once the server has answered.
 */
public final class PackScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("PVZCE/Packs");

    /**
     * The page's composition, in canvas units.
     *
     * <p>Two panels side by side under the title band, with the footer under them. The left one is
     * narrower than the mod list's: this page's right side carries a description, the file counts
     * and two buttons, which a 45/55 split cannot hold at a small window - the same reason the
     * first version of this page used 45/55 in GUI units.
     */
    private static final float HEADER_TITLE_X = 28F;
    private static final float HEADER_BASELINE = 40F;
    private static final float HEADER_TITLE_SIZE = 1.9F;

    private static final float PANEL_TOP = 72F;
    private static final float PANEL_BOTTOM = 512F;
    private static final float PANEL_H = PANEL_BOTTOM - PANEL_TOP;
    private static final float LEFT_X = 24F;
    private static final float LEFT_W = MenuPageCanvas.NATIVE_WIDTH * 44F / 100F;
    private static final float RIGHT_X = LEFT_X + LEFT_W + 16F;
    private static final float RIGHT_W = MenuPageCanvas.NATIVE_WIDTH - RIGHT_X - 24F;

    /** One line per pack: the state tick, the name, its kind and its file count. */
    private static final int ROW_HEIGHT = 56;

    private static final float FOOTER_Y = 524F;
    private static final float FOOTER_BUTTON_H = 36F;

    private Canvas canvas = new Canvas(1F, 0F, 0F);
    private AbstractSelectionList<PackRow> packList;
    private Button toggleButton;
    private final PackSelection selection;
    /** The pack the detail pane describes, and the counts its row was built with. */
    private AvailablePack selected;
    private Map<String, Integer> selectedSummary = Map.of();
    /** The row index a click on a group heading snaps back to. */
    private int lastPackIndex;
    /** A line this page has to say (the reload is running, the list could not be written). */
    private String notice = "";
    /** The reload count when this page last asked for one; {@code -1} when it is not waiting. */
    private int reloadsAtRequest = -1;

    public PackScreen(PvzceClient client) {
        super(client);
        this.selection = PackSelection.load(client.gameDir());
    }

    @Override
    protected void init() {
        canvas = MenuPageCanvas.fit(client);

        packList = new AbstractSelectionList<>(
                Math.round(canvas.interiorX(LEFT_X)),
                Math.round(canvas.interiorBottom(PANEL_TOP, PANEL_H)),
                Math.round(canvas.interiorWidth(LEFT_W)),
                Math.round(canvas.interiorHeight(PANEL_H)),
                ROW_HEIGHT, this::renderRow);
        addWidget(packList);

        // The detail pane's two actions, along its bottom, and the page's back button in the
        // footer. All three are placed by the canvas, so they sit in the same place relative to
        // the panels at every window size - and the pair starts inside the frame's own border,
        // which is 9 source pixels wide, rather than at the panel's outer edge.
        float actionWidth = (RIGHT_W - 18F) / 2F;
        toggleButton = canvas.widgetAt(client, RIGHT_X + 9F + actionWidth / 2F, PANEL_BOTTOM - 52F,
                actionWidth, FOOTER_BUTTON_H, "", this::applyToggle);
        addWidget(toggleButton);
        addWidget(canvas.widgetAt(client, RIGHT_X + 9F + actionWidth * 1.5F + 8F,
                PANEL_BOTTOM - 52F, actionWidth, FOOTER_BUTTON_H,
                GuiLang.raw("gui.pvzce.packs.reload", "重新加载"), this::requestReload));
        addWidget(canvas.widgetAt(client, MenuPageCanvas.NATIVE_WIDTH / 2F, FOOTER_Y,
                190F, FOOTER_BUTTON_H, GuiLang.raw("pvzce.back", "返回"), this::requestClose));

        rescan();
    }

    @Override
    public boolean blurredBackdrop() {
        // Off: the page paints its own field (the title screen's background under a flat dim).
        return false;
    }

    /**
     * One row: either a group heading or a pack.
     *
     * <p>A heading is a row because the list is the only scrolling thing on the page; it carries
     * no pack, and {@link #tick()} refuses to leave the selection on one.
     */
    private record PackRow(String heading, AvailablePack pack, Map<String, Integer> summary) {
        static PackRow heading(String heading) {
            return new PackRow(heading, null, Map.of());
        }

        static PackRow of(AvailablePack pack, Map<String, Integer> summary) {
            return new PackRow(null, pack, summary);
        }

        boolean isHeading() {
            return pack == null;
        }

        int files() {
            int total = 0;
            for (int count : summary.values()) {
                total += count;
            }
            return total;
        }
    }

    // ------------------------------------------------------------------
    // Scanning
    // ------------------------------------------------------------------

    /**
     * Re-reads the pack directories and rebuilds the list.
     *
     * <p>Each pack's files are counted while its row is built, so the summary in the detail pane
     * and the count on the row are one reading of the disk rather than two - and so that neither
     * of them walks a directory tree once per frame.
     */
    private void rescan() {
        if (client.resources() == null) {
            return;
        }
        List<AvailablePack> found;
        try {
            found = client.resources().scanAvailable(client.gameDir());
        } catch (IOException e) {
            LOGGER.warn("Could not scan the pack directories", e);
            showNotice(GuiLang.raw("gui.pvzce.packs.scan_failed", "Could not read the pack directories"));
            return;
        }
        String keep = selected == null ? null : key(selected);
        List<PackRow> rows = new ArrayList<>();
        AvailablePack.Kind group = null;
        for (AvailablePack pack : found) {
            if (pack.kind() != group) {
                group = pack.kind();
                rows.add(PackRow.heading(headingFor(group)));
            }
            rows.add(PackRow.of(pack, summaryOf(pack)));
        }
        packList.setEntries(rows);

        // Stay on the pack the player was looking at: the list is rebuilt from disk, and a
        // selection that landed on another row would silently change what the buttons act on.
        int wanted = -1;
        for (int i = 0; i < rows.size(); i++) {
            PackRow row = rows.get(i);
            if (row.isHeading()) {
                continue;
            }
            if (wanted < 0) {
                wanted = i;
            }
            if (keep != null && key(row.pack()).equals(keep)) {
                wanted = i;
                break;
            }
        }
        packList.select(wanted);
        lastPackIndex = Math.max(0, wanted);
        select(wanted < 0 ? null : rows.get(wanted));
    }

    private Map<String, Integer> summaryOf(AvailablePack pack) {
        try {
            return client.resources().contentSummary(pack);
        } catch (IOException e) {
            // A pack that cannot be listed still gets a row; only its counts are missing, and
            // showing zeroes would be a claim rather than an absence.
            LOGGER.warn("Could not count the contents of pack " + pack.name(), e);
            return Map.of();
        }
    }

    private void select(PackRow row) {
        selected = row == null ? null : row.pack();
        selectedSummary = row == null ? Map.of() : row.summary();
        refreshToggleButton();
    }

    /** Identifies a pack across a rescan; a resource pack and a data pack may share a directory name. */
    private static String key(AvailablePack pack) {
        return pack.kind() + "/" + pack.name();
    }

    private static String headingFor(AvailablePack.Kind kind) {
        return switch (kind) {
            case BUILT_IN -> GuiLang.raw("gui.pvzce.packs.heading.builtin", "内置");
            case RESOURCEPACK -> GuiLang.raw("gui.pvzce.packs.heading.resourcepacks", "资源包");
            case DATAPACK -> GuiLang.raw("gui.pvzce.packs.heading.datapacks", "数据包");
        };
    }

    private static String kindLabel(AvailablePack.Kind kind) {
        return switch (kind) {
            case BUILT_IN -> GuiLang.raw("gui.pvzce.packs.kind.builtin", "内置包");
            case RESOURCEPACK -> GuiLang.raw("gui.pvzce.packs.kind.resourcepack", "资源包");
            case DATAPACK -> GuiLang.raw("gui.pvzce.packs.kind.datapack", "数据包");
        };
    }

    // ------------------------------------------------------------------
    // Interaction
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        PackRow row = packList == null ? null : packList.selected();
        if (row != null && row.isHeading()) {
            // A heading is a label, not a choice: a click on it snaps the selection back to the
            // pack that was selected, or the detail pane would have to describe a group.
            packList.select(lastPackIndex);
        } else if (row != null) {
            lastPackIndex = packList.selectedIndex();
            select(row);
        }
        if (reloadsAtRequest >= 0 && client.contentReloads() != reloadsAtRequest) {
            // The server answered and the client has rebuilt its caches, so the loaded state now
            // is what the file said; this is the first moment the list can show it.
            reloadsAtRequest = -1;
            notice = "";
            rescan();
        }
    }

    private void refreshToggleButton() {
        if (toggleButton == null) {
            return;
        }
        boolean builtIn = selected != null && PackSelection.isBuiltIn(selected.name());
        // The built-in pack offers no button at all: "cannot be disabled" is a property of the
        // pack, and a button that refuses after the click is a worse way to say so than one that
        // never offered.
        toggleButton.setActive(selected != null && !builtIn);
        if (selected == null) {
            toggleButton.setLabel("");
        } else if (builtIn) {
            toggleButton.setLabel(GuiLang.raw("gui.pvzce.packs.builtin_on", "始终启用"));
        } else {
            toggleButton.setLabel(GuiLang.raw(
                    selected.enabled() ? "gui.pvzce.packs.disable" : "gui.pvzce.packs.enable",
                    selected.enabled() ? "停用" : "启用"));
        }
    }

    /** The toggle button: writes the list, then asks the server to rebuild its side. */
    private void applyToggle() {
        if (selected == null) {
            return;
        }
        if (PackSelection.isBuiltIn(selected.name())) {
            showNotice(GuiLang.raw("gui.pvzce.packs.builtin_locked",
                    "The built-in pack cannot be disabled"));
            return;
        }
        selection.setEnabled(selected.name(), !selected.enabled());
        if (!selection.save()) {
            showNotice(GuiLang.raw("gui.pvzce.packs.write_failed", "Could not write the pack list"));
            return;
        }
        requestReload();
    }

    /** The reload button: the same round trip with nothing changed, for a pack edited on disk. */
    private void requestReload() {
        reloadsAtRequest = client.contentReloads();
        showNotice(GuiLang.raw("gui.pvzce.packs.reloading", "正在重新加载…"));
        client.requestPackReload();
    }

    private void showNotice(String line) {
        notice = line;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    public void render() {
        client.beginGuiView();
        canvas = MenuPageCanvas.fit(client);
        MenuPageCanvas.renderPageBackground(client, this, 0.22F);
        canvas.header(client, MenuPageCanvas.NATIVE_WIDTH);
        canvas.text(client, client.fonts().button(), GuiLang.raw("gui.pvzce.packs.title", "数据包"),
                HEADER_TITLE_X, HEADER_BASELINE, HEADER_TITLE_SIZE, 1F, 1F, 1F, 1F);
        canvas.textRight(client, client.fonts().body(),
                GuiLang.raw("gui.pvzce.packs.hint", "改动在重新加载后生效"),
                MenuPageCanvas.NATIVE_WIDTH - 28F, HEADER_BASELINE, 0.85F, 0.72F, 0.76F, 0.72F, 1F);

        canvas.panel(client, LEFT_X, PANEL_TOP, LEFT_W, PANEL_H);
        canvas.panel(client, RIGHT_X, PANEL_TOP, RIGHT_W, PANEL_H);
        renderDetail();
        for (var widget : widgets) {
            widget.render(client);
        }
        renderStatus();
    }

    /**
     * The page's own line, or whatever the server last said, under the title band.
     *
     * <p>The server's line is the one that carries load failures: the page cannot parse a pack
     * itself, so "why did nothing happen" is answered by the message the reload sent back. The
     * notice wins while it is set, because it is the newer of the two (the reload is running) and
     * the server's line is still on screen from the last answer.
     */
    private void renderStatus() {
        String server = client.recentServerMessage();
        String line = notice.isEmpty() ? server : notice;
        if (line.isEmpty()) {
            return;
        }
        canvas.text(client, client.fonts().body(), line, LEFT_X + 4F, PANEL_TOP - 16F, 0.85F,
                1F, notice.isEmpty() ? 0.55F : 0.9F, notice.isEmpty() ? 0.5F : 0.6F, 1F);
    }

    /** One list row: the state tick, the name, its kind, and how many files it holds. */
    private void renderRow(PvzceClient client, PackRow row, int x, int y) {
        float unit = canvas.scale();
        if (row.isHeading()) {
            client.fonts().body().draw(row.heading(), x, y + ROW_HEIGHT * 0.3F, 0.95F * unit,
                    0.88F, 0.82F, 0.58F, 1F);
            return;
        }
        AvailablePack pack = row.pack();
        boolean on = pack.enabled();
        float tint = on ? 1F : 0.55F;
        // √ and × rather than ✓ and ✗: the bundled face is a Chinese one, and these are the two
        // forms it is certain to carry (× is already drawn all over the editor).
        client.fonts().body().draw(on ? "√" : "×", x, y + ROW_HEIGHT * 0.3F, 1.1F * unit,
                on ? 0.45F : 0.85F, on ? 0.9F : 0.35F, on ? 0.45F : 0.35F, 1F);
        client.fonts().body().draw(pack.name(), x + 24F * unit, y + ROW_HEIGHT * 0.46F,
                1.05F * unit, tint, tint, tint, 1F);
        client.fonts().body().draw(kindLabel(pack.kind()), x + 24F * unit, y + ROW_HEIGHT * 0.14F,
                0.72F * unit, 0.75F, 0.8F, 0.75F, 1F);
        String count = row.files() + " " + GuiLang.raw("gui.pvzce.packs.files", "个文件");
        client.fonts().body().draw(count,
                x + packList.width() - 14F * unit
                        - client.fonts().body().width(count, 0.78F * unit),
                y + ROW_HEIGHT * 0.28F, 0.78F * unit, 0.8F, 0.8F, 0.85F, 1F);
    }

    /**
     * The right panel: what the selected pack is, and what is in it.
     *
     * <p>Drawn inside the panel's interior rather than from the panel's own left edge - the frame
     * costs nine source pixels a side, and text that starts under the frame reads as clipped.
     */
    private void renderDetail() {
        if (selected == null) {
            return;
        }
        float unit = canvas.scale();
        float textX = canvas.interiorX(RIGHT_X) + 6F * unit;
        float textW = canvas.interiorWidth(RIGHT_W) - 12F * unit;
        // Top down from the panel's inner top edge: the name, then the kind line, then the
        // author's description, then the counts above the buttons.
        float y = canvas.y(PANEL_TOP) - canvas.scaled(46F);
        client.fonts().button().draw(selected.name(), textX, y, 1.5F * unit, 1F, 1F, 1F, 1F);

        y -= client.fonts().body().lineHeight(0.85F * unit) + 10F * unit;
        String kindLine = kindLabel(selected.kind())
                + (selected.path() == null ? "" : " · " + relativeDirectory(selected))
                + (selected.enabled() ? "" : " · " + GuiLang.raw("gui.pvzce.packs.state_off", "已停用"));
        client.fonts().body().draw(kindLine, textX, y, 0.85F * unit, 0.75F, 0.85F, 0.75F, 1F);

        // The description is the pack author's text, so it wraps rather than running off the
        // panel; the summary hangs off whatever is left below it.
        float lineHeight = client.fonts().body().lineHeight(0.85F * unit) + 2F * unit;
        float summaryHeadingMin = canvas.y(PANEL_BOTTOM) + canvas.scaled(96F);
        float afterDescription = drawWrappedText(selected.description(), textX, y - 18F * unit,
                textW, 0.85F * unit, 0.9F, 0.9F, 0.9F, 1F, summaryHeadingMin + lineHeight);
        float summaryHeading = Math.max(afterDescription - 4F * unit, summaryHeadingMin);
        client.fonts().body().draw(GuiLang.raw("gui.pvzce.packs.summary", "内容摘要"),
                textX, summaryHeading, 0.9F * unit, 0.85F, 0.85F, 0.6F, 1F);
        // The counts are read from the directory rather than from the stack, so they describe a
        // pack that is switched off just as well - which is what a player deciding whether to
        // switch it on is looking for.
        renderSummary(textX, textW, summaryHeading - 20F * unit,
                canvas.y(PANEL_BOTTOM) + canvas.scaled(70F), unit);
    }

    /** The per-content-directory counts, two columns wide, top down; whatever fits. */
    private void renderSummary(float textX, float textW, float top, float floor, float unit) {
        if (selectedSummary.isEmpty()) {
            client.fonts().body().draw(GuiLang.raw("gui.pvzce.packs.empty", "没有内容"),
                    textX, top, 0.8F * unit, 0.7F, 0.7F, 0.7F, 1F);
            return;
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(selectedSummary.entrySet());
        float columnWidth = textW / 2F;
        float lineHeight = client.fonts().body().lineHeight(0.8F * unit) + 4F * unit;
        int fits = 0;
        while (fits < entries.size() && top - (fits / 2) * lineHeight >= floor) {
            fits++;
        }
        for (int i = 0; i < fits; i++) {
            Map.Entry<String, Integer> entry = entries.get(i);
            // The cut is marked on the last row that was drawn rather than with a row of its own:
            // there is by definition no room for another row.
            String text = entry.getKey() + " " + entry.getValue()
                    + (i == fits - 1 && fits < entries.size() ? " …" : "");
            client.fonts().body().draw(text, textX + (i % 2) * columnWidth,
                    top - (i / 2) * lineHeight, 0.8F * unit, 0.85F, 0.85F, 0.85F, 1F);
        }
    }

    /** The pack's directory as the player would say it: {@code datapacks/user_levels}. */
    private String relativeDirectory(AvailablePack pack) {
        String gameDir = client.gameDir().toAbsolutePath().normalize().toString();
        String path = pack.path().toAbsolutePath().normalize().toString();
        return path.startsWith(gameDir) ? path.substring(gameDir.length() + 1) : path;
    }
}
