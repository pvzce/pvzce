package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.components.AbstractSelectionList;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.Dialog;
import com.pvzce.client.gui.components.EditBox;
import com.pvzce.common.network.packet.CreateWorldC2S;
import com.pvzce.common.network.packet.RequestProfileC2S;
import com.pvzce.common.util.WorldPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Select a saved player; creation and deletion each have their own modal step. */
public final class PlayerPickerDialog extends Dialog {
    private static final Logger LOGGER = LoggerFactory.getLogger("PVZCE/Client");
    private final PvzceClient client;
    private final Consumer<String> onSwitch;
    private final List<String> worlds = new ArrayList<>();
    private AbstractSelectionList<String> list;
    private Button useButton;
    private Button deleteButton;
    private int guiWidth;
    private int guiHeight;
    private int statusY;
    private String notice = "";

    private PlayerPickerDialog(PvzceClient client, Consumer<String> onSwitch, int guiWidth, int guiHeight) {
        super(0, 0, 1, 1, "谁要玩游戏？");
        this.client = client;
        this.onSwitch = onSwitch;
        inlineTitle();
        onClose(() -> client.currentScreen().removeDialog(this));
        onResize(guiWidth, guiHeight);
        refreshWorlds(client.currentWorld());
    }

    public static PlayerPickerDialog create(PvzceClient client, Consumer<String> onSwitch) {
        return create(client, onSwitch, client.guiWidth(), client.guiHeight());
    }

    /** Explicit dimensions also let the actual modal dispatch run in a windowless test. */
    static PlayerPickerDialog create(PvzceClient client, Consumer<String> onSwitch,
                                     int guiWidth, int guiHeight) {
        return new PlayerPickerDialog(client, onSwitch, guiWidth, guiHeight);
    }

    @Override
    public void onResize(int guiWidth, int guiHeight) {
        String selected = list == null ? null : list.selected();
        this.guiWidth = guiWidth;
        this.guiHeight = guiHeight;
        setSize(Math.min(520, guiWidth - 24), Math.min(430, guiHeight - 24));
        setPosition((guiWidth - width) / 2, (guiHeight - height) / 2);
        frameScale(Math.min(0.36F, height / 1100F));
        titleScale(Math.min(1.35F, height / 260F));
        clearChildren();
        int pad = Math.round(frameInset()) + 8;
        int row = Math.max(22, Math.min(36, height / 12));
        int gap = Math.max(6, Math.min(12, height / 40));
        int innerWidth = width - pad * 2;
        int bottom = y + pad;
        int upperRow = bottom + row + gap;
        statusY = upperRow + row + gap + 3;
        int listBottom = statusY + 19;
        int listTop = y + height - pad - 24;
        list = new AbstractSelectionList<>(x + pad, listBottom, innerWidth,
                Math.max(24, listTop - listBottom), Math.max(24, row),
                (c, world, rowX, rowY) -> c.fonts().body().draw(world, rowX + 5, rowY + 5,
                        Math.min(1.1F, height / 260F), 1F, 1F, 0.85F, 1F));
        list.setOnRowClick(ignored -> updateActions());
        list.setEntries(worlds);
        selectVisible(selected);
        addChild(list);
        int half = (innerWidth - gap) / 2;
        addButton(new Button(x + pad, upperRow, half, row,
                GuiLang.raw("pvzce.player.create", "新玩家"), this::openCreation));
        deleteButton = new Button(x + pad + half + gap, upperRow, innerWidth - half - gap, row,
                GuiLang.raw("pvzce.player.delete", "删除选中"), this::confirmDeletion);
        addButton(deleteButton);
        useButton = new Button(x + pad, bottom, half, row,
                GuiLang.raw("pvzce.player.confirm", "确定"), this::switchToSelected);
        addButton(useButton);
        addButton(new Button(x + pad + half + gap, bottom, innerWidth - half - gap, row,
                GuiLang.raw("pvzce.dialog.cancel", "取消"), this::close));
        updateActions();
    }

    private void refreshWorlds(String selection) {
        worlds.clear();
        Path saves = WorldPaths.savesDir(client.gameDir());
        if (Files.isDirectory(saves)) {
            try (Stream<Path> entries = Files.list(saves)) {
                entries.filter(Files::isDirectory).map(path -> path.getFileName().toString())
                        .sorted().forEach(worlds::add);
            } catch (IOException e) {
                LOGGER.warn("Failed to list players", e);
            }
        }
        list.setEntries(worlds);
        selectVisible(selection);
        updateActions();
    }

    private void selectVisible(String name) {
        int index = worlds.indexOf(name);
        list.select(index);
        if (index < 0) {
            return;
        }
        int first = list.scrollOffset() / list.entryHeight();
        int wanted = index < first ? index : Math.max(first, index - list.visibleCount() + 1);
        list.mouseScrolled(list.x(), list.y(), first - wanted);
    }

    private void updateActions() {
        boolean selected = list.selected() != null;
        useButton.setActive(selected);
        deleteButton.setActive(selected);
    }

    private void openCreation() {
        CreationDialog dialog = new CreationDialog();
        client.currentScreen().showDialog(dialog);
        dialog.nameBox.setFocused(true);
    }

    private void confirmDeletion() {
        String selected = list.selected();
        if (selected == null) {
            return;
        }
        ConfirmDialog confirmation = ConfirmDialog.create(client,
                GuiLang.raw("pvzce.player.delete_title", "删除这个玩家？"),
                GuiLang.raw("pvzce.player.delete_message", "将永久删除该玩家的全部存档：") + selected,
                GuiLang.raw("pvzce.player.delete_confirm", "删除玩家"),
                () -> deleteWorld(selected), null, guiWidth, guiHeight);
        confirmation.frameScale(0.26F);
        confirmation.closeOnEscape(true);
        confirmation.onClose(() -> client.currentScreen().removeDialog(confirmation));
        client.currentScreen().showDialog(confirmation);
    }

    private void deleteWorld(String selected) {
        try (Stream<Path> walk = Files.walk(WorldPaths.worldDir(client.gameDir(), selected))) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
            notice = "";
        } catch (IOException e) {
            LOGGER.error("Failed to delete player {}", selected, e);
            notice = GuiLang.raw("pvzce.player.delete_error", "删除失败，请稍后重试。");
        }
        refreshWorlds(null);
    }

    private void switchToSelected() {
        String world = list.selected();
        if (world != null && Files.isDirectory(WorldPaths.worldDir(client.gameDir(), world))) {
            close();
            onSwitch.accept(world);
        }
    }

    @Override
    public void render(PvzceClient client) {
        super.render(client);
        if (!visible) {
            return;
        }
        if (worlds.isEmpty()) {
            String empty = GuiLang.raw("pvzce.player.empty", "还没有玩家，点击“新玩家”创建。");
            float scale = Math.min(0.95F, (list.width() - 12F)
                    / Math.max(1F, client.fonts().body().width(empty, 1F)));
            client.fonts().body().draw(empty, list.x() + 6, list.y() + list.height() / 2F,
                    scale, 0.8F, 0.82F, 0.7F, 1F);
        }
        client.fonts().body().draw(notice, list.x(), statusY, 0.75F, 1F, 0.65F, 0.4F, 1F);
    }

    /** Only this modal owns a name field and the initial unlock option. */
    private final class CreationDialog extends Dialog {
        private EditBox nameBox;
        private Button unlockToggle;
        private boolean unlockAll;
        private int labelY;
        private int hintY;
        private int messageY;
        private String message = "";

        CreationDialog() {
            super(0, 0, 1, 1, GuiLang.raw("pvzce.player.new_title", "创建新玩家"));
            inlineTitle();
            onClose(() -> client.currentScreen().removeDialog(this));
            onResize(guiWidth, guiHeight);
        }

        @Override
        public void onResize(int guiWidth, int guiHeight) {
            String value = nameBox == null ? "" : nameBox.value();
            setSize(Math.min(480, guiWidth - 24), Math.min(300, guiHeight - 24));
            setPosition((guiWidth - width) / 2, (guiHeight - height) / 2);
            frameScale(Math.min(0.3F, height / 1000F));
            titleScale(Math.min(1.25F, height / 230F));
            clearChildren();
            int pad = Math.round(frameInset()) + 10;
            int row = Math.max(23, Math.min(34, height / 9));
            int gap = Math.max(6, Math.min(10, height / 28));
            int innerWidth = width - pad * 2;
            int bottom = y + pad;
            int top = y + height - pad;
            labelY = top - 4;
            nameBox = new EditBox(x + pad, top - row - 20, innerWidth, row, this::createWorld);
            nameBox.setValue(value);
            addChild(nameBox);
            hintY = nameBox.y() - 17;
            messageY = bottom + row + gap + 2;
            unlockToggle = new Button(x + pad, bottom + row + gap * 2 + 18, innerWidth, row,
                    unlockLabel(), () -> {
                        unlockAll = !unlockAll;
                        unlockToggle.setLabel(unlockLabel());
                    });
            addButton(unlockToggle);
            int half = (innerWidth - gap) / 2;
            addButton(new Button(x + pad, bottom, half, row,
                    GuiLang.raw("pvzce.dialog.cancel", "取消"), this::close));
            addButton(new Button(x + pad + half + gap, bottom, innerWidth - half - gap, row,
                    GuiLang.raw("pvzce.player.create_confirm", "创建"), this::createWorld));
        }

        private String unlockLabel() {
            return GuiLang.raw("pvzce.unlock_all", "全解锁") + "：" + (unlockAll ? "开" : "关");
        }

        private void createWorld() {
            String name = nameBox.value().trim();
            if (name.isEmpty()) {
                message = GuiLang.raw("pvzce.player.empty_name", "请输入玩家名字。");
                return;
            }
            if (!name.equals(WorldPaths.sanitize(name))) {
                message = GuiLang.raw("pvzce.player.invalid_name", "名字只能使用字母、数字、下划线或连字符。");
                return;
            }
            try {
                Files.createDirectories(WorldPaths.savesDir(client.gameDir()));
                // Atomic: an existing player must never receive a fresh starter profile.
                Files.createDirectory(WorldPaths.worldDir(client.gameDir(), name));
            } catch (FileAlreadyExistsException e) {
                message = GuiLang.raw("pvzce.player.duplicate_name", "这个玩家已存在，请换一个名字。");
                return;
            } catch (IOException e) {
                LOGGER.error("Failed to create player {}", name, e);
                message = GuiLang.raw("pvzce.player.create_error", "创建失败，请稍后重试。");
                return;
            }
            client.connection().send(new CreateWorldC2S(name, unlockAll));
            // Creation answers with the new profile, but the user has not switched yet.
            // Restore the menu player's snapshot after it, in the same ordered packet queue.
            client.connection().send(new RequestProfileC2S(client.currentWorld()));
            close();
            notice = "";
            refreshWorlds(name);
        }

        @Override
        public void render(PvzceClient client) {
            super.render(client);
            if (!visible) {
                return;
            }
            client.fonts().body().draw(GuiLang.raw("pvzce.player.name", "玩家名字"),
                    nameBox.x(), labelY, 0.85F, 0.94F, 0.94F, 0.82F, 1F);
            client.fonts().body().draw(GuiLang.raw("pvzce.player.name_hint", "使用字母、数字、下划线或连字符"),
                    nameBox.x(), hintY, 0.7F, 0.65F, 0.7F, 0.6F, 1F);
            float scale = Math.min(0.8F, nameBox.width()
                    / Math.max(1F, client.fonts().body().width(message, 1F)));
            client.fonts().body().draw(message, nameBox.x(), messageY, scale, 1F, 0.65F, 0.4F, 1F);
        }
    }
}
