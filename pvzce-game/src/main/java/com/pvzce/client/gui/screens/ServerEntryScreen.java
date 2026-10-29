package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.config.SavedServers;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.*;
import com.pvzce.client.gui.layout.MenuPageCanvas;
import com.pvzce.common.network.ServerAddress;
import java.io.IOException;

/** The direct-connect and bookmark forms share address validation and layout. */
public final class ServerEntryScreen extends Screen {
    @FunctionalInterface public interface Submit { void accept(SavedServers.Entry entry) throws IOException; }
    private final String title;
    private final boolean direct;
    private final Submit submit;
    private String serverName;
    private String serverAddress;
    private String error = "";
    private EditBox name;
    private EditBox address;
    private MenuPageCanvas.Canvas canvas;

    public ServerEntryScreen(PvzceClient client, String title, boolean direct, SavedServers.Entry initial, Submit submit) {
        super(client); this.title = title; this.direct = direct; this.submit = submit;
        serverName = initial == null ? "服务器" : initial.name();
        serverAddress = initial == null ? client.roomAddress() : initial.address();
    }
    @Override protected void init() {
        if (name != null) serverName = name.value();
        if (address != null) serverAddress = address.value();
        clearWidgets(); canvas = MenuPageCanvas.fit(client);
        if (!direct) { name = box(220, 195, serverName, 64); addWidget(name); }
        address = box(220, direct ? 245 : 285, serverAddress, 256); addWidget(address);
        addWidget(canvas.widgetAt(client, 290, 440, 200, 42, direct ? "连接" : "保存", this::submit));
        addWidget(canvas.widgetAt(client, 510, 440, 200, 42, "取消", this::requestClose));
        focusManager().request(direct ? address : name);
    }
    private EditBox box(int x, int top, String value, int limit) {
        var box = new EditBox(Math.round(canvas.x(x)), Math.round(canvas.y(top + 40)),
                Math.round(canvas.scaled(360)), Math.round(canvas.scaled(40)), limit, this::submit).style(EditBox.Style.PARCHMENT);
        box.setValue(value); return box;
    }
    private void submit() {
        try {
            String normalized = ServerAddress.parse(address.value()).toString();
            submit.accept(new SavedServers.Entry(direct ? "直接连接" : name.value(), normalized));
        } catch (IllegalArgumentException | IOException e) { error = e.getMessage(); }
    }
    @Override public void render() {
        client.beginGuiView(); MenuPageCanvas.renderPageBackground(client, this);
        canvas.panel(client, 155, 135, 490, 355);
        client.fonts().button().drawCentered(title, canvas.x(400), canvas.y(78), canvas.scaled(1.8F), .25F, .13F, .05F, 1);
        if (!direct) text("服务器名称", 220, 180, 1.05F);
        text("服务器地址", 220, direct ? 230 : 270, 1.05F);
        text("IP / 主机名:端口，省略端口使用默认值", 220, direct ? 320 : 350, .85F);
        text("IPv6：[地址]:端口", 220, direct ? 350 : 378, .85F);
        text(error, 170, 412, .85F);
        for (var widget : widgets) widget.render(client);
    }
    private void text(String value, int x, int y, float size) {
        float scale = Math.min(canvas.scaled(size), canvas.scaled(460) / Math.max(1, client.fonts().body().width(value, 1)));
        client.fonts().body().draw(value, canvas.x(x), canvas.y(y), scale, .98F, .91F, .72F, 1);
    }
}
