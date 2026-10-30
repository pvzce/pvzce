package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.AbstractSelectionList;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.layout.MenuPageCanvas;
import com.pvzce.common.network.packet.PackManifestS2C;
import java.util.LinkedHashSet;
import java.util.Set;

/** Pack acceptance before any host content or resources are installed. */
public final class ServerPacksScreen extends Screen {
    private final PackManifestS2C manifest;
    private final Set<String> selected = new LinkedHashSet<>();
    private MenuPageCanvas.Canvas canvas;

    public ServerPacksScreen(PvzceClient client, PackManifestS2C manifest) {
        super(client); this.manifest = manifest;
        manifest.packs().forEach(p -> selected.add(p.hash()));
    }
    @Override protected void init() {
        canvas = MenuPageCanvas.fit(client);
        int row = Math.round(canvas.scaled(50));
        var list = new AbstractSelectionList<PackManifestS2C.Pack>(Math.round(canvas.x(70)),
                Math.round(canvas.y(450)), Math.round(canvas.scaled(660)), row * 6, row,
                (ignored, pack, x, y) -> {
                    boolean required = manifest.requireAll() || pack.required();
                    String label = (selected.contains(pack.hash()) ? "[✓] " : "[ ] ") + pack.name();
                    float scale = Math.min(canvas.scaled(1), canvas.scaled(440) / Math.max(1, client.fonts().body().width(label, 1)));
                    client.fonts().body().draw(label, x + canvas.scaled(10), y + canvas.scaled(28), scale, 1, .94F, .77F, 1);
                    client.fonts().body().draw(required ? "必须" : "可选 · 点击切换", x + canvas.scaled(470), y + canvas.scaled(28), canvas.scaled(.9F), .8F, .88F, .64F, 1);
                });
        list.setStyle(AbstractSelectionList.Style.WOOD); list.setEntries(manifest.packs());
        list.setOnRowClick(pack -> {
            if (!manifest.requireAll() && !pack.required()) {
                if (!selected.remove(pack.hash())) selected.add(pack.hash());
            }
        });
        addWidget(list);
        addWidget(new Button(Math.round(canvas.x(100)), Math.round(canvas.y(530)), Math.round(canvas.scaled(260)),
                Math.round(canvas.scaled(42)), "拒绝并返回", client::leaveMultiplayer).style(Button.Style.SEED_CHOOSER));
        addWidget(new Button(Math.round(canvas.x(440)), Math.round(canvas.y(530)), Math.round(canvas.scaled(260)),
                Math.round(canvas.scaled(42)), "接受并加入", () -> {
                    client.popScreen(); client.selectMultiplayerPacks(manifest, selected);
                }).style(Button.Style.SEED_CHOOSER));
    }
    @Override public void render() {
        client.beginGuiView(); MenuPageCanvas.renderPageBackground(client, this);
        client.fonts().button().drawCentered("房主的数据包", canvas.x(400), canvas.y(60), canvas.scaled(1.6F), .25F, .13F, .05F, 1);
        client.fonts().body().draw(manifest.requireAll() ? "房主要求接受所有数据包；拒绝将返回多人游戏。" : "玩法包必须接受；材质、语言等可选包可点击取消。",
                canvas.x(70), canvas.y(110), canvas.scaled(.95F), .25F, .13F, .05F, 1);
        client.fonts().body().draw("缺失或内容不同的包会先下载；离开房间后恢复本地资源。",
                canvas.x(70), canvas.y(475), canvas.scaled(.9F), .25F, .13F, .05F, 1);
        for (var widget : widgets) widget.render(client);
    }
    @Override public void requestClose() { client.leaveMultiplayer(); }
}
