package com.pvzce.client.gui.editor.pages;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.EditBox;
import com.pvzce.client.gui.editor.EditorContext;
import com.pvzce.client.gui.editor.EditorPage;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.RhythmCharts;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The rhythm page: one track, one tempo, four levels written at once.
 *
 * <p>The mode's content is four level files that differ in exactly one list, so the editor's job is
 * not "edit a chart" but "write the four of them from one set of numbers". That is what this page
 * is: a track picker, a BPM, a density per tier, and a button. The charts themselves are generated
 * by {@link RhythmCharts}, which the game also uses, so what the page writes is what the game reads
 * - there is no second generator hiding in the editor.
 *
 * <p><b>Why a page and not a chart table.</b> The alternative was a note-by-note editor like the
 * wave page's. It would be a much bigger screen for a much worse result: a chart is a musical
 * object, and hand-placing eight hundred notes in a table is not how anyone writes one. The four
 * tiers are the same track at four densities, which is one number each.
 */
public final class RhythmPage implements EditorPage {
    private EditBox musicBox;
    private EditBox bpmBox;
    private EditBox prefixBox;
    private final EditBox[] densityBoxes = new EditBox[RhythmCharts.TIERS.length];
    private EditBox damageBox;
    private EditBox sunBox;
    /** Draw-only labels, as {text, y, x}: the fields would otherwise be five bare numbers. */
    private final java.util.List<String[]> labels = new java.util.ArrayList<>();

    @Override
    public String id() {
        return "rhythm";
    }

    @Override
    public String label() {
        return GuiLang.raw("pvzce.editor.page.rhythm", "rhythm");
    }

    @Override
    public int order() {
        return 65;
    }

    @Override
    public boolean hasPalette() {
        return false;
    }

    /**
     * Nothing is read back from the level.
     *
     * <p>This page writes four <em>new</em> levels rather than editing the one that is open - a
     * chart is generated, not authored field by field - so there is no block of the current draft
     * it owns and nothing for {@code writeTo} to put back. Leaving both empty is what keeps the
     * page from claiming a slice of a file it does not have.
     */
    @Override
    public void readFrom(EditorContext context) {
    }

    @Override
    public void writeTo(EditorContext context) {
    }

    @Override
    public void build(EditorContext context) {
        EditorContext.Rect area = context.content();
        int rowH = Math.max(18, Math.min(26, area.height() / 16));
        int labelW = Math.max(70, area.width() / 8);
        int fieldW = Math.max(90, area.width() / 3 - labelW);
        int x = area.x() + 12 + labelW;
        int y = area.bottom() - rowH - 12;

        labels.add(new String[] {GuiLang.raw("pvzce.editor.rhythm.music", "曲目"),
                Integer.toString(y), Integer.toString(area.x() + 12)});
        musicBox = context.own(new EditBox(x, y, fieldW, rowH, () -> { }));
        musicBox.setValue(defaultMusic());
        y -= rowH + 8;
        labels.add(new String[] {GuiLang.raw("pvzce.editor.rhythm.bpm", "BPM"),
                Integer.toString(y), Integer.toString(area.x() + 12)});
        bpmBox = context.own(new EditBox(x, y, fieldW, rowH, () -> { }));
        bpmBox.setValue("120");
        y -= rowH + 8;
        labels.add(new String[] {GuiLang.raw("pvzce.editor.rhythm.prefix", "关卡名前缀"),
                Integer.toString(y), Integer.toString(area.x() + 12)});
        prefixBox = context.own(new EditBox(x, y, fieldW, rowH, () -> { }));
        prefixBox.setValue("rhythm");
        y -= rowH + 8;
        labels.add(new String[] {GuiLang.raw("pvzce.editor.rhythm.damage", "每次命中的伤害"),
                Integer.toString(y), Integer.toString(area.x() + 12)});
        damageBox = context.own(new EditBox(x, y, fieldW, rowH, () -> { }));
        damageBox.setValue("300");
        y -= rowH + 8;
        labels.add(new String[] {GuiLang.raw("pvzce.editor.rhythm.sun", "PERFECT 补阳光"),
                Integer.toString(y), Integer.toString(area.x() + 12)});
        sunBox = context.own(new EditBox(x, y, fieldW, rowH, () -> { }));
        sunBox.setValue("1");
        y -= rowH + 8;

        int dx = x + fieldW + 24;
        for (int i = 0; i < RhythmCharts.TIERS.length; i++) {
            int row = area.bottom() - rowH - 12 - (rowH + 8) * i;
            labels.add(new String[] {GuiLang.raw("pvzce.editor.rhythm.density", "密度")
                    + " · " + RhythmCharts.TIERS[i].suffix(),
                    Integer.toString(row), Integer.toString(x + fieldW + 6)});
            densityBoxes[i] = context.own(new EditBox(dx, row,
                    fieldW, rowH, () -> { }));
            densityBoxes[i].setValue(String.valueOf(RhythmCharts.TIERS[i].density()));
        }
        // The button is deliberately the last thing and deliberately loud: the page has one
        // action, and it writes four files.
        context.own(new Button(x, area.y() + 12, fieldW * 2 + 12, rowH + 8,
                GuiLang.raw("pvzce.editor.rhythm.generate", "生成四档关卡"),
                () -> generate(context)));
    }

    /** The first music event the pack has, so the field starts on something real. */
    private static String defaultMusic() {
        for (Identifier id : BuiltInRegistries.SOUND_EVENTS.keySet()) {
            if (id.path().startsWith("music/")) {
                return id.toString();
            }
        }
        return "pvzce:music/grasswalk";
    }

    /**
     * Writes the four levels next to the one being edited.
     *
     * <p>Beside it rather than into a directory of their own: a rhythm tier is a level like any
     * other, and where it lives is what decides which page of the level list it appears on. The
     * name comes from the prefix field, so four runs of the button do not overwrite each other.
     */
    private void generate(EditorContext context) {
        double bpm = parse(bpmBox, 120D);
        int damage = (int) parse(damageBox, 300D);
        int sun = (int) parse(sunBox, 1D);
        double[] densities = new double[densityBoxes.length];
        for (int i = 0; i < densityBoxes.length; i++) {
            densities[i] = parse(densityBoxes[i], RhythmCharts.TIERS[i].density());
        }
        String prefix = prefixBox.value().isBlank() ? "rhythm" : prefixBox.value().trim();
        String music = musicBox.value().trim();

        Identifier current = context.levelId();
        String theme = "yard";
        String category = "minigame";
        if (current != null) {
            String[] parts = current.path().split("/");
            if (parts.length >= 2) {
                theme = parts[0];
                category = parts[1];
            }
        }
        Path dir = context.client().gameDir()
                .resolve("datapacks/user_levels/data/pvzce/levels")
                .resolve(theme).resolve(category);
        try {
            Files.createDirectories(dir);
            int written = 0;
            for (int i = 0; i < RhythmCharts.TIERS.length; i++) {
                String name = prefix + "_" + RhythmCharts.TIERS[i].suffix();
                Path file = dir.resolve(name + ".json");
                Files.writeString(file, RhythmCharts.levelJson(
                        Identifier.of("pvzce", theme + "/" + category + "/" + name),
                        RhythmCharts.TIERS[i].displayName(), music, bpm, densities[i],
                        RhythmCharts.TIERS[i].rows(), RhythmCharts.TIERS[i].cols(),
                        RhythmCharts.TIERS[i].waves(), damage, sun));
                written++;
            }
            context.setStatus(GuiLang.raw("pvzce.editor.rhythm.done", "已写出 ")
                    + written + GuiLang.raw("pvzce.editor.rhythm.done_suffix", " 张谱面到 ")
                    + dir);
        } catch (java.io.IOException e) {
            context.setStatus(GuiLang.raw("pvzce.editor.rhythm.failed", "写谱面失败：")
                    + e.getMessage());
        }
    }

    /**
     * Draws the field names.
     *
     * <p>Drawn rather than widgetised because that is what the neighbouring pages do - see
     * {@code InfoPage}'s reward labels - and because a label is text that never moves or takes a
     * click: a widget for it would be a widget that exists to be skipped.
     */
    @Override
    public void render(EditorContext context) {
        for (String[] label : labels) {
            context.client().fonts().body().draw(label[0], Float.parseFloat(label[2]),
                    Float.parseFloat(label[1]) + 4F, 0.85F, 0.86F, 0.88F, 0.86F, 1F);
        }
    }

    private static double parse(EditBox box, double fallback) {
        try {
            return Double.parseDouble(box.value().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
