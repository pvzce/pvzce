package com.pvzce.client.gui.editor;

import com.pvzce.client.gui.editor.pages.CanvasPage;
import com.pvzce.client.gui.editor.pages.CardsPage;
import com.pvzce.client.gui.editor.pages.DialoguePage;
import com.pvzce.client.gui.editor.pages.InfoPage;
import com.pvzce.client.gui.editor.pages.MechanicPages;
import com.pvzce.client.gui.editor.pages.MusicPage;
import com.pvzce.client.gui.editor.pages.RulePage;
import com.pvzce.client.gui.editor.pages.UnlockPage;
import com.pvzce.client.gui.editor.pages.WavePage;
import com.pvzce.client.gui.screens.EditorScreen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The editor's page list: what the game ships plus what a mod registered.
 *
 * <p>Every page is an {@link EditorPage} of its own - the editor has no page bodies left on the
 * screen - so this is the whole table. Ordering happens here rather than in the registry so the
 * built-in pages and a mod's page compete on the same {@link EditorPage#order()} scale; pages
 * that do not apply to the level being edited are dropped by {@link EditorPage#visibleFor},
 * which is how a conveyor level hides the card page.
 *
 * <p>The three canvas tabs share one {@link CanvasPage.Model}, made by the screen: the file has
 * one {@code scene} and one {@code initial_entities} block, and the tabs are three views of the
 * same board.
 */
public final class BuiltInEditorPages {
    public static List<EditorPage> create(EditorScreen screen, EditorContext context,
                                          CanvasPage.Model canvas) {
        List<EditorPage> pages = new ArrayList<>();
        pages.add(CanvasPage.terrain(canvas));
        pages.add(CanvasPage.plant(canvas));
        pages.add(CanvasPage.zombie(canvas));
        pages.add(RulePage.create());
        pages.addAll(MechanicPages.create(context));
        pages.add(new WavePage());
        pages.add(new CardsPage());
        pages.add(new MusicPage());
        pages.add(new DialoguePage());
        pages.add(new UnlockPage());
        pages.add(new InfoPage());
        pages.addAll(EditorPages.external());
        pages.removeIf(page -> !page.visibleFor(context));
        pages.sort(Comparator.comparingInt(EditorPage::order).thenComparing(EditorPage::id));
        return List.copyOf(pages);
    }

    private BuiltInEditorPages() {
    }
}
