package com.pvzce.client.gui.mods;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.AbstractSelectionList;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.EditBox;
import com.pvzce.client.gui.layout.GuiLayout;
import com.pvzce.client.renderer.texture.Texture;
import com.pvzce.common.resource.PackResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;

/** Mod list screen: left search/list pane, right mod description pane. */
public final class ModsScreen extends Screen {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("PVZCE/Mods");
    private static final int LEFT_X = 24;

    private int leftWidth;
    private int rightX;
    private int rightWidth;
    private EditBox searchBox;
    private AbstractSelectionList<ModInfo> modList;
    private ModInfo selected;
    private int titleY;
    private float titleScale;
    private final java.util.Set<String> expandedMods = new java.util.HashSet<>();

    public ModsScreen(PvzceClient client) {
        super(client);
    }

    @Override
    protected void init() {
        ModMenu.initialize();
        int height = client.guiHeight();
        int guiW = client.guiWidth();
        leftWidth = Math.max(260, Math.min(520, (guiW - 64) * 55 / 100));
        rightX = LEFT_X + leftWidth + 20;
        rightWidth = Math.max(120, guiW - rightX - 16);
        if (rightWidth < 240 && guiW > 480) {
            leftWidth = guiW - 300;
            rightX = LEFT_X + leftWidth + 20;
            rightWidth = guiW - rightX - 16;
        }

        int titleReserve = Math.max(38, Math.min(58, height / 5));
        int searchHeight = GuiLayout.fitHeight(height, 42, 1, titleReserve, 0);
        int searchY = height - titleReserve - 6 - searchHeight;
        titleScale = Math.min(1.8F, Math.max(1.0F, height / 140F));
        titleY = (searchY + searchHeight) + Math.round(client.font().lineHeight(titleScale) * 0.35F);

        searchBox = new EditBox(LEFT_X + 8, searchY, Math.max(120, leftWidth - 130), searchHeight, 128, () -> {
        });
        addWidget(searchBox);
        addWidget(new Button(LEFT_X + leftWidth - 116, searchY, 108, searchHeight, "清除",
                () -> searchBox.setValue("")));

        int listY = 8;
        int listHeight = Math.max(40, searchY - listY - 8);
        modList = new AbstractSelectionList<>(LEFT_X, listY, leftWidth, listHeight, 46,
                (client, mod, x, y) -> {
                    int indent = mod.parentId() == null ? 0 : 16;
                    Texture icon = modIcon(mod);
                    if (icon != null) {
                        client.drawTexture(Identifier.of("mod_icon", mod.id()), x + indent, y + 8, 30, 30, 0, 1, 1, 1, 1);
                    } else {
                        client.warnMissingTexture(Identifier.of("mod_icon", mod.id()));
                        client.drawSolid(x + indent, y + 8, 30, 30, 0, 0.35F, 0.35F, 0.4F, 1F);
                        String initial = mod.name().isEmpty() ? "?" : mod.name().substring(0, 1).toUpperCase();
                        client.font().draw(initial, x + indent + 8, y + 10, 1F, 1F, 1F, 1F, 1F);
                    }
                    client.font().draw(mod.name(), x + indent + 36, y + 20, 1F, 1F, 1F, 1F, 1F);
                    String sub = mod.id() + " · v" + mod.version() + (mod.parentId() == null ? "" : " (子模组)");
                    client.font().draw(sub, x + indent + 38, y + 2, 0.65F, 0.7F, 0.75F, 0.7F, 1F);
                });
        addWidget(modList);
        refreshFilter();

        int smallW = Math.max(100, Math.min(140, rightWidth / 3 - 6));
        int doneHeight = GuiLayout.fitHeight(height, 56, 1, 0, 0);
        int actionY = 8 + doneHeight + 8;
        int actionHeight = GuiLayout.fitHeight(height, 45, 1, titleReserve, actionY);
        addWidget(new Button(rightX, 8, Math.max(120, smallW), doneHeight, "完成", this::requestClose));
        // Website/config/collapse buttons sit above Done with a clear gap.
        addWidget(new Button(rightX, actionY, smallW, actionHeight, "配置", this::openConfig));
        addWidget(new Button(rightX + smallW + 6, actionY, smallW, actionHeight, "主页", this::openHomepage));
        addWidget(new Button(rightX + (smallW + 6) * 2, actionY,
                Math.max(smallW, rightWidth - (smallW + 6) * 2), actionHeight, "折叠子模组", this::toggleChildren));
    }

    private void toggleChildren() {
        if (selected == null || selected.parentId() != null) {
            return;
        }
        if (!expandedMods.remove(selected.id())) {
            expandedMods.add(selected.id());
        }
        refreshFilter();
    }

    private Texture modIcon(ModInfo mod) {
        Identifier id = Identifier.of("mod_icon", mod.id());
        Texture texture = client.textures().get(id);
        if (texture != null) {
            return texture;
        }
        byte[] bytes = ModMenu.iconBytes(mod);
        if (bytes == null) {
            return null;
        }
        try {
            return client.textures().uploadAndCache(id, new PackResource(mod.id(), mod.iconPath(), bytes));
        } catch (Exception e) {
            LOGGER.debug("Could not load the icon of mod {}; its row stays blank", mod.id(), e);
            return null;
        }
    }

    private void refreshFilter() {
        String search = searchBox == null ? "" : searchBox.value();
        List<ModInfo> filtered = new ArrayList<>();
        for (ModInfo mod : ModMenu.mods()) {
            if (mod.parentId() != null) {
                continue;
            }
            boolean parentMatches = mod.matches(search);
            List<ModInfo> children = ModMenu.childrenOf(mod.id());
            if (parentMatches || search == null || search.isBlank() || children.stream().anyMatch(m -> m.matches(search))) {
                filtered.add(mod);
                if (expandedMods.contains(mod.id()) || (search != null && !search.isBlank())) {
                    for (ModInfo child : children) {
                        if (child.matches(search)) {
                            filtered.add(child);
                        }
                    }
                }
            }
        }
        modList.setEntries(filtered);
        if (selected == null || !filtered.contains(selected)) {
            selected = filtered.isEmpty() ? null : filtered.get(0);
        }
    }

    @Override
    public void tick() {
        if (modList != null && modList.selected() != null) {
            selected = modList.selected();
        }
        if (searchBox != null && searchBox.isFocused()) {
            refreshFilter();
        }
    }

    private void openConfig() {
        if (selected == null) {
            return;
        }
        ConfigScreenFactory<?> factory = ModMenu.getConfigFactory(selected.id());
        if (factory != null) {
            Screen config = factory.create(this);
            if (config != null) {
                client.openScreen(config);
            }
        }
    }

    private void openHomepage() {
        if (selected == null) {
            return;
        }
        String url = selected.contact().get("homepage");
        if (url == null) {
            url = selected.contact().get("sources");
        }
        if (url != null) {
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            } catch (Exception e) {
                client.level().addMessage("无法打开链接: " + url);
            }
        }
    }

    @Override
    public void render() {
        client.beginGuiView();
        renderBackground(0.09F, 0.1F, 0.13F);
        String title = "模组列表";
        client.font().draw(title, LEFT_X + 10, titleY, titleScale, 1, 1, 1, 1);
        client.font().draw("已加载 " + ModMenu.mods().size() + " 个模组", LEFT_X + 180, titleY,
                0.8F, 0.7F, 0.75F, 0.7F, 1F);

        for (var widget : widgets) {
            widget.render(client);
        }
        renderDetail();
    }

    private void renderDetail() {
        if (selected == null) {
            return;
        }
        int titleReserve = Math.max(38, Math.min(58, client.guiHeight() / 5));
        int y = client.guiHeight() - titleReserve - 8;
        client.font().draw(selected.name(), rightX, y, 1.6F, 1F, 1F, 1F, 1F);
        y -= 30;
        client.font().draw(selected.id() + " · v" + selected.version(), rightX, y, 0.9F, 0.8F, 0.85F, 0.8F, 1F);
        y -= 26;
        String authors = "作者: " + (selected.authors().isEmpty() ? "未知" : String.join(", ", selected.authors()));
        client.font().draw(authors, rightX, y, 0.8F, 0.85F, 0.85F, 0.85F, 1F);
        y -= 22;
        String license = "许可: " + (selected.license().isEmpty() ? "未知" : String.join(", ", selected.license()));
        client.font().draw(license, rightX, y, 0.8F, 0.85F, 0.85F, 0.85F, 1F);
        y -= 28;
        int badgeX = rightX;
        for (String badge : selected.badges()) {
            String label = badgeLabel(badge);
            float width = client.font().width(label, 0.7F) + 12;
            client.drawSolid(badgeX, y, width, 20, 0, 0.25F, 0.35F, 0.25F, 1F);
            client.font().draw(label, badgeX + 6, y + 3, 0.7F, 1F, 1F, 1F, 1F);
            badgeX += width + 6;
        }
        y -= 32;
        // The description is wrapped down to the bottom of the window; the old
        // fixed y=190 cut it off silently on tall windows and overflowed on short
        // ones.
        drawWrapped(selected.description(), rightX, y, rightWidth, 0.8F, 0.9F, 0.9F, 0.9F, 1F,
                Math.max(12F, y - (client.guiHeight() - titleReserve - 8) + y));
    }

    /**
     * Char-wraps text into the band between {@code y} and {@code minY}. Text that
     * does not fit is truncated with an ellipsis so the reader can tell it was cut
     * rather than wondering why the description stops mid-sentence.
     */
    private void drawWrapped(String text, float x, float y, float maxWidth, float scale,
                             float r, float g, float b, float a, float minY) {
        if (text == null || text.isEmpty()) {
            return;
        }
        float lineHeight = client.font().lineHeight(scale) + 2;
        StringBuilder line = new StringBuilder();
        float cursorY = y;
        boolean truncated = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            String candidate = line.toString() + ch;
            boolean wrap = ch == '\n' || client.font().width(candidate, scale) > maxWidth;
            if (wrap) {
                if (!line.isEmpty()) {
                    if (cursorY - lineHeight < minY) {
                        truncated = true;
                        break;
                    }
                    client.font().draw(line.toString(), x, cursorY, scale, r, g, b, a);
                    cursorY -= lineHeight;
                    line.setLength(0);
                }
                if (ch != '\n') {
                    line.append(ch);
                }
            } else {
                line.append(ch);
            }
        }
        if (!line.isEmpty() && !truncated) {
            if (cursorY - lineHeight < minY) {
                truncated = true;
            } else {
                client.font().draw(line.toString(), x, cursorY, scale, r, g, b, a);
            }
        }
        if (truncated && cursorY >= minY) {
            client.font().draw("…", x, cursorY, scale, r, g, b, a);
        }
    }

    private static String badgeLabel(String badge) {
        return switch (badge) {
            case "library" -> "库";
            case "client" -> "仅客户端";
            case "modpack" -> "整合包";
            default -> badge;
        };
    }
}
