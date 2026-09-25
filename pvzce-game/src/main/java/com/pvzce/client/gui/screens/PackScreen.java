package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.AbstractSelectionList;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.layout.GuiLayout;
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
 * <p>Shaped like {@code ModsScreen} - a list on the left, the selected entry's detail on the
 * right - because the question is the same one ("what is installed, and what is this one?"), and
 * two pages that answer it should not be two different layouts.
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
    private static final int LEFT_X = 24;
    /** One line per pack: the state tick, the name, its kind and its file count. */
    private static final int ROW_HEIGHT = 34;
    /**
     * The hint's size, and the page's only text size that the layout measures with.
     *
     * <p>The list's top edge is derived from the footer (back button + hint), so the two cannot
     * overlap however short the window is.
     */
    private static final float HINT_SCALE = 0.8F;

    private int leftWidth;
    private int rightX;
    private int rightWidth;
    /** The bottom of the title band: the top of the list and of the detail text. */
    private int listTop;
    private float titleScale;
    private float titleY;
    private float hintY;
    /** The footer line that carries this page's notice, or the last thing the server said. */
    private float statusY;
    private int actionHeight;

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
        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        int titleReserve = Math.max(38, Math.min(58, guiH / 5));
        // A 45/55 split rather than the mod list's fixed left column: at the default window this
        // page's right side carries a description and the content counts, and the mod list's
        // 260-unit floor would leave it 107 units to draw them in.
        leftWidth = Math.max(190, Math.min(420, guiW * 45 / 100));
        rightX = LEFT_X + leftWidth + 16;
        rightWidth = Math.max(120, guiW - rightX - 16);

        titleScale = Math.min(1.8F, Math.max(1.0F, guiH / 140F));
        titleY = guiH - titleReserve + 4F;
        listTop = guiH - titleReserve - 4;

        // The footer: the back button, then the hint line, then the line this page has to say.
        // The list gets whatever is left, because the page has no fixed number of rows - and the
        // status line lives down here rather than in the detail column, which has no room to
        // spare on a short window.
        int buttonHeight = GuiLayout.fitHeight(guiH, 36, 1, 0, 0);
        addWidget(new Button(LEFT_X, 8, 140, buttonHeight,
                GuiLang.raw("pvzce.back", "Back"), this::requestClose));
        float lineHeight = client.fonts().body().lineHeight(HINT_SCALE);
        hintY = 8 + buttonHeight + 6;
        statusY = hintY + (int) lineHeight + 4;
        int listY = (int) statusY + (int) lineHeight + 8;
        packList = new AbstractSelectionList<>(LEFT_X, listY, leftWidth,
                Math.max(60, listTop - listY), ROW_HEIGHT, this::renderRow);
        addWidget(packList);

        // The detail pane's two actions, along the bottom of the right column.
        actionHeight = GuiLayout.fitHeight(guiH, 40, 1, 0, 0);
        int toggleWidth = Math.max(90, Math.min(160, rightWidth / 3));
        toggleButton = new Button(rightX, 8, toggleWidth, actionHeight, "", this::applyToggle);
        addWidget(toggleButton);
        addWidget(new Button(rightX + toggleWidth + 6, 8,
                Math.max(90, Math.min(170, rightWidth - toggleWidth - 6)), actionHeight,
                GuiLang.raw("gui.pvzce.packs.reload", "Reload"), this::requestReload));

        rescan();
    }

    @Override
    public boolean blurredBackdrop() {
        return true;
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
            case BUILT_IN -> GuiLang.raw("gui.pvzce.packs.heading.builtin", "Built-in");
            case RESOURCEPACK -> GuiLang.raw("gui.pvzce.packs.heading.resourcepacks", "Resource packs");
            case DATAPACK -> GuiLang.raw("gui.pvzce.packs.heading.datapacks", "Data packs");
        };
    }

    private static String kindLabel(AvailablePack.Kind kind) {
        return switch (kind) {
            case BUILT_IN -> GuiLang.raw("gui.pvzce.packs.kind.builtin", "built-in");
            case RESOURCEPACK -> GuiLang.raw("gui.pvzce.packs.kind.resourcepack", "resource pack");
            case DATAPACK -> GuiLang.raw("gui.pvzce.packs.kind.datapack", "data pack");
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
            toggleButton.setLabel(GuiLang.raw("gui.pvzce.packs.builtin_on", "always on"));
        } else {
            toggleButton.setLabel(GuiLang.raw(
                    selected.enabled() ? "gui.pvzce.packs.disable" : "gui.pvzce.packs.enable",
                    selected.enabled() ? "Disable" : "Enable"));
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
        showNotice(GuiLang.raw("gui.pvzce.packs.reloading", "Reloading…"));
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
        if (!renderBlurredBackdrop(0.10F, 0.11F, 0.14F, 0.62F)) {
            renderBackground(0.08F, 0.1F, 0.12F);
        }
        client.fonts().button().draw(GuiLang.raw("gui.pvzce.packs.title", "Packs"),
                LEFT_X + 10, titleY, titleScale, 1F, 1F, 1F, 1F);
        client.fonts().body().draw(GuiLang.raw("gui.pvzce.packs.hint",
                "A change takes effect after a reload"), LEFT_X + 10, hintY, HINT_SCALE,
                0.7F, 0.75F, 0.7F, 1F);
        renderStatus();
        for (var widget : widgets) {
            widget.render(client);
        }
        renderDetail();
    }

    /**
     * The page's own line, or whatever the server last said.
     *
     * <p>The server's line is the one that carries load failures: the page cannot parse a pack
     * itself, so "why did nothing happen" is answered by the message the reload sent back. The
     * notice wins while it is set, because it is the newer of the two (the reload is running) and
     * the server's line is still on screen from the last answer.
     */
    private void renderStatus() {
        String server = client.recentServerMessage();
        if (!notice.isEmpty()) {
            client.fonts().body().draw(notice, LEFT_X + 10, statusY, HINT_SCALE, 1F, 0.9F, 0.5F, 1F);
        } else if (!server.isEmpty()) {
            client.fonts().body().draw(server, LEFT_X + 10, statusY, HINT_SCALE, 1F, 0.5F, 0.45F, 1F);
        }
    }

    /** One list row: the state tick, the name, its kind, and how many files it holds. */
    private void renderRow(PvzceClient client, PackRow row, int x, int y) {
        if (row.isHeading()) {
            client.fonts().body().draw(row.heading(), x + 2, y + ROW_HEIGHT * 0.3F, 0.95F,
                    0.85F, 0.85F, 0.6F, 1F);
            return;
        }
        AvailablePack pack = row.pack();
        boolean on = pack.enabled();
        float tint = on ? 1F : 0.55F;
        // √ and × rather than ✓ and ✗: the bundled face is a Chinese one, and these are the two
        // forms it is certain to carry (× is already drawn all over the editor).
        client.fonts().body().draw(on ? "√" : "×", x + 2, y + ROW_HEIGHT * 0.3F, 1F,
                on ? 0.45F : 0.85F, on ? 0.9F : 0.35F, on ? 0.45F : 0.35F, 1F);
        client.fonts().body().draw(pack.name(), x + 24, y + ROW_HEIGHT * 0.42F, 1F, tint, tint, tint, 1F);
        client.fonts().body().draw(kindLabel(pack.kind()), x + 24, y + ROW_HEIGHT * 0.1F, 0.7F,
                0.75F, 0.8F, 0.75F, 1F);
        String count = row.files() + " " + GuiLang.raw("gui.pvzce.packs.files", "files");
        float countScale = 0.75F;
        client.fonts().body().draw(count,
                LEFT_X + leftWidth - 14 - client.fonts().body().width(count, countScale),
                y + ROW_HEIGHT * 0.25F, countScale, 0.8F, 0.8F, 0.85F, 1F);
    }

    private void renderDetail() {
        if (selected == null) {
            return;
        }
        float y = listTop - client.fonts().body().lineHeight(1.4F);
        client.fonts().button().draw(selected.name(), rightX, y, 1.4F, 1F, 1F, 1F, 1F);
        y -= client.fonts().body().lineHeight(0.9F) + 8F;
        // Kind, directory and state on one line: a short window leaves the detail column about
        // four lines of room, and two of them spent on "which directory is this" would push the
        // content counts off the page.
        String kindLine = kindLabel(selected.kind())
                + (selected.path() == null ? "" : " · " + relativeDirectory(selected))
                + (selected.enabled() ? "" : " · " + GuiLang.raw("gui.pvzce.packs.state_off", "Disabled"));
        client.fonts().body().draw(kindLine, rightX, y, 0.85F, 0.75F, 0.85F, 0.75F, 1F);

        // The description is the pack author's text, so it is wrapped rather than drawn as one
        // line, and the summary hangs off whatever is left below it: on a short window the text
        // is cut with an ellipsis instead of the counts being drawn over the buttons.
        float lineHeight = client.fonts().body().lineHeight(0.85F) + 2F;
        float descriptionTop = y - 18F;
        float summaryHeadingMin = actionTop() + 30F;
        float afterDescription = drawWrappedText(selected.description(), rightX, descriptionTop,
                rightWidth, 0.85F, 0.9F, 0.9F, 0.9F, 1F, summaryHeadingMin + lineHeight);
        float summaryHeading = Math.max(afterDescription - 4F, summaryHeadingMin);
        client.fonts().body().draw(GuiLang.raw("gui.pvzce.packs.summary", "Contents"),
                rightX, summaryHeading, 0.9F, 0.85F, 0.85F, 0.6F, 1F);
        // The counts are read from the directory rather than from the stack, so they describe a
        // pack that is switched off just as well - which is what a player deciding whether to
        // switch it on is looking for.
        renderSummary(summaryHeading - 20F, actionTop() + 6F);
    }

    /** The per-content-directory counts, two columns wide, top down; whatever fits. */
    private void renderSummary(float top, float floor) {
        if (selectedSummary.isEmpty()) {
            client.fonts().body().draw(GuiLang.raw("gui.pvzce.packs.empty", "no content"),
                    rightX, top, 0.8F, 0.7F, 0.7F, 0.7F, 1F);
            return;
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(selectedSummary.entrySet());
        float columnWidth = Math.max(110F, rightWidth / 2F);
        float lineHeight = client.fonts().body().lineHeight(0.8F) + 4F;
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
            client.fonts().body().draw(text, rightX + (i % 2) * columnWidth,
                    top - (i / 2) * lineHeight, 0.8F, 0.85F, 0.85F, 0.85F, 1F);
        }
    }

    /** Where the detail text ends: the action buttons start here. */
    private float actionTop() {
        return 8F + actionHeight + 12F;
    }

    /** The pack's directory as the player would say it: {@code datapacks/user_levels}. */
    private String relativeDirectory(AvailablePack pack) {
        String gameDir = client.gameDir().toAbsolutePath().normalize().toString();
        String path = pack.path().toAbsolutePath().normalize().toString();
        return path.startsWith(gameDir) ? path.substring(gameDir.length() + 1) : path;
    }
}
