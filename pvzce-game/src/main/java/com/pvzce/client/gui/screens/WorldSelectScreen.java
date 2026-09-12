package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.AbstractSelectionList;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.components.EditBox;
import com.pvzce.common.network.packet.CreateWorldC2S;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** World list: create / delete / enter worlds under ~/.pvzce/saves/. */
public final class WorldSelectScreen extends Screen {
    private final List<String> worlds = new ArrayList<>();
    private AbstractSelectionList<String> list;
    private EditBox nameBox;
    /** Sandbox switch: a world created with it starts with every card unlocked. */
    private boolean unlockAll;

    public WorldSelectScreen(PvzceClient client) {
        super(client);
    }

    @Override
    protected void init() {
        client.music().ensureMenu("pvzce:music/choose_your_seeds");
        refreshWorlds();

        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        int titleReserve = Math.max(44, Math.min(96, guiH / 4));
        int topY = guiH - titleReserve;
        int available = Math.max(96, topY - 40);
        int backWidth = Math.min(150, guiW - 16);
        int backHeight = Math.min(56, Math.max(30, available / 5));
        int actionHeight = Math.min(62, Math.max(32, available / 4));
        int nameHeight = Math.min(54, Math.max(28, available / 5));
        int actionGap = Math.max(8, Math.min(14, (available - backHeight - actionHeight - nameHeight) / 3));
        int actionY = 4 + backHeight + actionGap;
        int nameY = actionY + actionHeight + actionGap;
        int listY = nameY + nameHeight + actionGap;
        int listHeight = Math.max(24, topY - listY - 6);
        int listWidth = Math.min(620, guiW - 16);

        list = new AbstractSelectionList<>(centerX(listWidth), listY, listWidth, listHeight, 42,
                (client, world, x, y) -> client.font().draw(world, x, y + 8, 1F, 1F, 1F, 1F, 1F));
        list.setEntries(worlds);
        addWidget(list);

        int nameWidth = Math.min(340, guiW - 16);
        nameBox = new EditBox(centerX(nameWidth), nameY, nameWidth, nameHeight, this::createWorld);
        addWidget(nameBox);

        int smallWidth = Math.min(170, (guiW - 16 - actionGap * 2) / 3);
        int total = smallWidth * 3 + actionGap * 2;
        int startX = centerX(total);
        addWidget(new Button(startX, actionY, smallWidth, actionHeight, "创建世界", this::createWorld));
        addWidget(new Button(startX + smallWidth + actionGap, actionY, smallWidth, actionHeight, "删除选中", this::deleteWorld));
        addWidget(new Button(startX + (smallWidth + actionGap) * 2, actionY, smallWidth, actionHeight, "进入", this::enterWorld));

        // The sandbox switch sits between the name box and the list, on the same
        // row as the 世界名称 label so it cannot be mistaken for a world action.
        int toggleHeight = Math.max(24, Math.min(34, nameHeight - 6));
        int toggleWidth = Math.min(240, guiW - 16);
        addWidget(toggleButton(centerX(nameWidth) + nameWidth + 8 > guiW - 8
                ? centerX(toggleWidth) : centerX(nameWidth) + nameWidth + 8,
                nameY + (nameHeight - toggleHeight) / 2, toggleWidth, toggleHeight));

        addWidget(new Button(centerX(backWidth), 4, backWidth, backHeight, "返回", client::showTitle));
    }

    /**
     * The sandbox toggle.
     *
     * <p>A plain {@link Button} whose label carries the state instead of a new
     * checkbox widget: labels are what the smoke-test click helper can find, and a
     * checkbox would have to reimplement the same "click to switch" behaviour.
     */
    private Button toggleButton(int x, int y, int width, int height) {
        // The button cannot reference itself inside its own constructor, so the
        // label is updated through a one-slot holder.
        Button[] holder = new Button[1];
        Button toggle = new Button(x, y, width, height, unlockLabel(), () -> {
            unlockAll = !unlockAll;
            holder[0].setLabel(unlockLabel());
        });
        holder[0] = toggle;
        return toggle;
    }

    private String unlockLabel() {
        return GuiLang.raw("pvzce.unlock_all", "全解锁") + "：" + (unlockAll ? "开" : "关");
    }

    private void refreshWorlds() {
        worlds.clear();
        Path saves = client.gameDir().resolve("saves");
        if (!Files.isDirectory(saves)) {
            return;
        }
        try (Stream<Path> entries = Files.list(saves)) {
            entries.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted(Comparator.naturalOrder())
                    .forEach(worlds::add);
        } catch (IOException e) {
            System.err.println("Failed to list worlds: " + e.getMessage());
        }
    }

    private void createWorld() {
        String raw = nameBox.value().trim();
        String name = raw.isBlank() ? "新世界" : raw.replaceAll("[^A-Za-z0-9_-]", "_");
        if (name.isBlank()) {
            return;
        }
        Path dir = client.gameDir().resolve("saves").resolve(name);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            System.err.println("Failed to create world " + name + ": " + e.getMessage());
            return;
        }
        // The directory is the client's (the list reads the disk), but the profile
        // inside it is the server's: it is the one that knows what "unlocked" means,
        // and it writes the file the level list will be filtered by.
        client.connection().send(new CreateWorldC2S(name, unlockAll));
        nameBox.setValue("");
        refreshWorlds();
        list.setEntries(worlds);
        list.select(Math.max(0, worlds.indexOf(name)));
    }

    private void deleteWorld() {
        String selected = list.selected();
        if (selected == null) {
            return;
        }
        Path dir = client.gameDir().resolve("saves").resolve(selected);
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            System.err.println("Failed to delete world " + selected + ": " + e.getMessage());
            return;
        }
        refreshWorlds();
        list.setEntries(worlds);
    }

    private void enterWorld() {
        String selected = list.selected();
        if (selected == null) {
            return;
        }
        client.setCurrentWorld(selected);
        client.openScreen(new LevelSelectScreen(client));
    }

    @Override
    public void render() {
        client.beginGuiView();
        renderBackground(0.12F, 0.22F, 0.11F);
        String title = "选择世界";
        float scale = Math.min(2.2F, client.guiHeight() / 100F);
        client.font().draw(title, (client.guiWidth() - client.font().width(title, scale)) / 2F,
                client.guiHeight() - client.font().lineHeight(scale) - 8, scale, 1F, 1F, 1F, 1F);
        client.font().draw("世界名称", centerX(Math.min(340, client.guiWidth() - 16)),
                nameBox.y() + nameBox.height() + 2, 0.8F, 0.85F, 0.85F, 0.85F, 1F);
        for (var widget : widgets) {
            widget.render(client);
        }
    }
}
