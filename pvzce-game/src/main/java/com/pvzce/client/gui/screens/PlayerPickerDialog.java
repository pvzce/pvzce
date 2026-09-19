package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.components.AbstractSelectionList;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.Dialog;
import com.pvzce.client.gui.components.EditBox;
import com.pvzce.common.network.packet.CreateWorldC2S;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The "who is playing?" dialog: a name is a player, and a player is a world.
 *
 * <p>A world directory is one player's save ({@code saves/<name>/}), so the original's "who are
 * you?" screen and this project's world picker were always the same question asked in two
 * places. It is one dialog now, opened from the player name on the title screen.
 *
 * <p><strong>It only switches.</strong> Choosing a name changes who this session is playing as
 * and closes; it does not open the level list - that is what 开始游戏 on the menu is for, and a
 * click here that also navigated would make "change player" and "start playing" one gesture.
 * Creating a player does not switch to it either: making a save file and choosing to play as it
 * are two decisions.
 *
 * <p>A {@link Dialog} rather than a screen: this is a question about the menu, and the screen
 * layer already routes every mouse and key event to the topmost modal first, so "the menu behind
 * it cannot be clicked while this is up" is structural. Its frame is drawn thin
 * ({@link #FRAME_SCALE}) - the stone frame's native border is most of a dialog this size.
 */
public final class PlayerPickerDialog extends Dialog {
    /**
     * How much of the stone frame's native border to draw.
     *
     * <p>The frame is authored at ~209 texture pixels of border; at a 3x UI scale that is most of
     * a small dialog, and a list plus a name field plus four buttons do not fit in what is left.
     * Just over half keeps the carving recognisable and leaves the content its room.
     */
    private static final float FRAME_SCALE = 0.55F;

    private final PvzceClient client;
    private final Consumer<String> onSwitch;

    private final List<String> worlds = new ArrayList<>();
    private final AbstractSelectionList<String> list;
    private final EditBox nameBox;
    private final Button unlockToggle;
    /** Sandbox switch: a player created with it starts with every card unlocked. */
    private boolean unlockAll;

    private PlayerPickerDialog(PvzceClient client, int x, int y, int width, int height,
                               Consumer<String> onSwitch) {
        super(x, y, width, height, "谁要玩游戏？");
        this.client = client;
        this.onSwitch = onSwitch;
        titleScale(Math.min(1.15F, height / 260F));
        frameScale(FRAME_SCALE);
        // A plain title inside the frame: the hanging plate is ~38 units tall and would sit
        // behind the first row of a dialog this full.
        inlineTitle();
        closeOnEscape(true);

        int pad = Math.round(frameInset());
        int fieldWidth = width - pad * 2;
        // Compact rows and gaps on purpose: every unit saved here is a row of names the list can
        // show, and the list is the part of this dialog that grows with the player's data.
        int rowH = Math.max(18, Math.min(26, height / 11));
        int gap = 4;
        // Laid out bottom-up from the frame's inside, not from `y`: the border is part of this
        // widget, and content placed from its outside edge is content drawn on the frame.
        int bottom = y + Math.round(frameInset() * 1.15F) + 2;
        int actionY = bottom;
        int toggleY = actionY + rowH + gap;
        int boxY = toggleY + rowH + gap;
        int listBottom = boxY + rowH + gap;
        // Below the inline title, which is one line of text under the top border.
        int listTop = y + height - Math.round(frameInset() * 1.2F)
                - (int) client.font().lineHeight(titleScale()) - 6;
        int listHeight = Math.max(rowH * 2, listTop - listBottom);

        list = new AbstractSelectionList<>(x + pad, listBottom, fieldWidth, listHeight,
                Math.max(20, rowH),
                (screen, world, rowX, rowY) -> screen.font().draw(world, rowX, rowY + 4,
                        1F, 1F, 1F, 0.85F, 1F));
        addChild(list);

        nameBox = new EditBox(x + pad, boxY, fieldWidth, rowH, this::createWorld);
        addChild(nameBox);

        int half = (fieldWidth - gap) / 2;
        unlockToggle = new Button(x + pad, toggleY, half, rowH, unlockLabel(), this::toggleUnlock);
        addButton(unlockToggle);
        addButton(new Button(x + pad + half + gap, toggleY, fieldWidth - half - gap, rowH,
                GuiLang.raw("pvzce.player.create", "新玩家"), this::createWorld));
        addButton(new Button(x + pad, actionY, half, rowH,
                GuiLang.raw("pvzce.player.delete", "删除选中"), this::deleteWorld));
        addButton(new Button(x + pad + half + gap, actionY, fieldWidth - half - gap, rowH,
                GuiLang.raw("pvzce.player.use", "切换到这个玩家"), this::switchToSelected));

        refreshWorlds();
    }

    /** Centres the dialog over the menu, sized to the window it has to fit in. */
    public static PlayerPickerDialog create(PvzceClient client, Consumer<String> onSwitch) {
        int width = Math.min(360, client.guiWidth() - 20);
        int height = Math.min(250, client.guiHeight() - 20);
        return new PlayerPickerDialog(client, (client.guiWidth() - width) / 2,
                (client.guiHeight() - height) / 2, width, height, onSwitch);
    }

    /** Everyone with a save directory, in listing order, current player highlighted. */
    private void refreshWorlds() {
        worlds.clear();
        Path saves = client.gameDir().resolve("saves");
        if (Files.isDirectory(saves)) {
            try (Stream<Path> entries = Files.list(saves)) {
                entries.filter(Files::isDirectory)
                        .map(path -> path.getFileName().toString())
                        .sorted(Comparator.naturalOrder())
                        .forEach(worlds::add);
            } catch (IOException e) {
                System.err.println("Failed to list worlds: " + e.getMessage());
            }
        }
        list.setEntries(worlds);
        int current = worlds.indexOf(client.currentWorld());
        if (current >= 0) {
            list.select(current);
        }
    }

    private void toggleUnlock() {
        unlockAll = !unlockAll;
        unlockToggle.setLabel(unlockLabel());
    }

    private String unlockLabel() {
        return GuiLang.raw("pvzce.unlock_all", "全解锁") + "：" + (unlockAll ? "开" : "关");
    }

    /**
     * Makes a player out of the typed name, and highlights it.
     *
     * <p>Does not switch to it: creating a save and choosing to play as it are two decisions, and
     * the button that switches says so.
     */
    private void createWorld() {
        String raw = nameBox.value() == null ? "" : nameBox.value().trim();
        String name = raw.isBlank() ? "新世界" : raw.replaceAll("[^A-Za-z0-9_-]", "_");
        if (name.isBlank()) {
            return;
        }
        try {
            Files.createDirectories(client.gameDir().resolve("saves").resolve(name));
        } catch (IOException e) {
            System.err.println("Failed to create world " + name + ": " + e.getMessage());
            return;
        }
        // The directory is the client's (the list reads the disk), but the profile inside it is
        // the server's: it is the one that knows what "unlocked" means, and it writes the file
        // the level list is filtered by.
        client.connection().send(new CreateWorldC2S(name, unlockAll));
        nameBox.setValue("");
        refreshWorlds();
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
    }

    /**
     * Switches to the highlighted player and closes.
     *
     * <p>The one gesture that changes who is playing. A click on a name only highlights it, so a
     * row click cannot change the session on its way to somewhere else - which is also why this
     * dialog never opens the level list.
     */
    private void switchToSelected() {
        String world = list.selected();
        if (world == null || world.isBlank()) {
            return;
        }
        close();
        onSwitch.accept(world);
    }
}
