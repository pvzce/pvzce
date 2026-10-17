package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.components.AbstractSelectionList;
import com.pvzce.client.gui.components.AbstractWidget;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.Dialog;
import com.pvzce.client.gui.components.EditBox;
import com.pvzce.common.network.packet.CreateWorldC2S;
import com.pvzce.common.network.packet.RequestProfileC2S;
import com.pvzce.testutil.ClientHarness;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlayerPickerDialogTest {
    private static class Menu extends Screen {
        Menu(PvzceClient client) { super(client); }
        @Override public void render() { }
    }

    private static Button button(Dialog dialog, String label) {
        String key = switch (label) {
            case "新玩家" -> "pvzce.player.create";
            case "取消" -> "pvzce.dialog.cancel";
            case "创建" -> "pvzce.player.create_confirm";
            case "确定" -> "pvzce.player.confirm";
            case "删除选中" -> "pvzce.player.delete";
            case "删除玩家" -> "pvzce.player.delete_confirm";
            default -> "pvzce.unlock_all";
        };
        String translated = label.equals("全解锁：关")
                ? GuiLang.raw(key, "全解锁") + "：关" : GuiLang.raw(key, label);
        return dialog.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b -> b.label().equals(translated)).findFirst().orElseThrow();
    }

    private static void click(Screen screen, Button button) {
        screen.dispatchMouseClicked(button.x() + button.width() / 2D,
                button.y() + button.height() / 2D, 0);
    }

    private static Dialog top(Screen screen) {
        return screen.dialogs().getLast();
    }

    private static EditBox nameBox(Dialog dialog) {
        return dialog.children().stream().filter(EditBox.class::isInstance)
                .map(EditBox.class::cast).findFirst().orElseThrow();
    }

    @Test
    void creatingRequiresItsOwnFormAndConfirmingThePickerIsTheOnlySwitch() throws Exception {
        try (ClientHarness h = ClientHarness.create("player-steps")) {
            Files.createDirectories(h.gameDir().resolve("saves/Alice"));
            for (int i = 0; i < 9; i++) {
                Files.createDirectories(h.gameDir().resolve("saves/Player" + i));
            }
            h.client().setCurrentWorld("Alice");
            Menu screen = new Menu(h.client());
            h.client().setScreenReplacing(screen);
            List<String> switched = new ArrayList<>();
            PlayerPickerDialog picker = PlayerPickerDialog.create(h.client(), switched::add, 960, 540);
            screen.showDialog(picker);
            assertTrue(picker.children().stream().noneMatch(EditBox.class::isInstance));
            click(screen, button(picker, "新玩家"));
            Dialog form = top(screen);
            assertNotSame(picker, form);
            nameBox(form).setValue("Cancelled");
            click(screen, button(form, "取消"));
            assertFalse(screen.hasTextInputFocused(), "closing creation releases its text focus");
            assertTrue(Files.notExists(h.gameDir().resolve("saves/Cancelled")));
            assertTrue(h.sentPackets().isEmpty());
            click(screen, button(picker, "新玩家"));
            form = top(screen);
            nameBox(form).setValue("Zed");
            click(screen, button(form, "全解锁：关"));
            click(screen, button(form, "创建"));
            assertSame(picker, top(screen));
            assertTrue(Files.isDirectory(h.gameDir().resolve("saves/Zed")));
            assertTrue(switched.isEmpty());
            assertEquals("Alice", h.client().currentWorld());
            assertTrue(h.sentPackets().contains(new CreateWorldC2S("Zed", true)));
            assertTrue(h.sentPackets().contains(new RequestProfileC2S("Alice")),
                    "the new profile must not remain in the current player's menu snapshot");
            AbstractSelectionList<?> list = (AbstractSelectionList<?>) picker.children().getFirst();
            assertEquals("Zed", list.selected());
            assertTrue(list.scrollOffset() > 0, "the newly created player must be visible in a long list");
            int firstVisible = list.scrollOffset() / list.entryHeight();
            assertTrue(list.selectedIndex() >= firstVisible
                    && list.selectedIndex() < firstVisible + list.visibleCount(),
                    "a partially filled viewport must still scroll far enough to reveal the last player");
            click(screen, button(picker, "确定"));
            assertEquals(List.of("Zed"), switched);
            assertTrue(screen.dialogs().isEmpty());
        }
    }

    @Test
    void blankOrDuplicateNamesNeverResetAnExistingPlayerAndDeleteNeedsConfirmation() throws Exception {
        try (ClientHarness h = ClientHarness.create("player-preserve")) {
            var save = h.gameDir().resolve("saves/Alice/keep.dat");
            Files.createDirectories(save.getParent());
            Files.writeString(save, "progress");
            h.client().setCurrentWorld("Alice");
            Menu screen = new Menu(h.client());
            h.client().setScreenReplacing(screen);
            PlayerPickerDialog picker = PlayerPickerDialog.create(h.client(), ignored -> fail(), 960, 540);
            screen.showDialog(picker);
            click(screen, button(picker, "新玩家"));
            Dialog form = top(screen);
            click(screen, button(form, "创建"));
            assertSame(form, top(screen));
            assertFalse(Files.exists(h.gameDir().resolve("saves/world")));
            nameBox(form).setValue("Alice");
            click(screen, button(form, "创建"));
            assertSame(form, top(screen));
            assertTrue(h.sentPackets().isEmpty());
            assertEquals("progress", Files.readString(save));
            click(screen, button(form, "取消"));
            click(screen, button(picker, "删除选中"));
            assertTrue(Files.exists(save));
            click(screen, button(top(screen), "取消"));
            assertTrue(Files.exists(save));
            click(screen, button(picker, "删除选中"));
            click(screen, button(top(screen), "删除玩家"));
            assertFalse(Files.exists(save.getParent()));
            assertSame(picker, top(screen));
            assertFalse(button(picker, "确定").isActive());
        }
    }

    @Test
    void bothDialogsHaveSeparatedControlsAndResizePreservesTypedNames() throws Exception {
        try (ClientHarness h = ClientHarness.create("player-layout")) {
            Menu screen = new Menu(h.client());
            h.client().setScreenReplacing(screen);
            PlayerPickerDialog picker = PlayerPickerDialog.create(h.client(), ignored -> { }, 960, 540);
            screen.showDialog(picker);
            click(screen, button(picker, "新玩家"));
            Dialog form = top(screen);
            nameBox(form).setValue("Draft");
            for (int[] size : new int[][]{{960, 540}, {427, 240}, {320, 240}, {640, 360}}) {
                picker.onResize(size[0], size[1]);
                form.onResize(size[0], size[1]);
                assertEquals("Draft", nameBox(form).value());
                for (Dialog dialog : List.of(picker, form)) {
                    assertTrue(dialog.x() >= 0 && dialog.y() >= 0);
                    assertTrue(dialog.x() + dialog.width() <= size[0]);
                    assertTrue(dialog.y() + dialog.height() <= size[1]);
                    for (AbstractWidget a : dialog.children()) {
                        assertTrue(a.x() >= dialog.x() && a.y() >= dialog.y());
                        assertTrue(a.x() + a.width() <= dialog.x() + dialog.width());
                        assertTrue(a.y() + a.height() <= dialog.y() + dialog.height());
                        for (AbstractWidget b : dialog.children()) {
                            if (a == b) continue;
                            assertFalse(a.x() < b.x() + b.width() && b.x() < a.x() + a.width()
                                    && a.y() < b.y() + b.height() && b.y() < a.y() + a.height(),
                                    "controls must not overlap inside " + dialog.title());
                        }
                    }
                }
            }
        }
    }
}
