package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.account.GameAccount;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.layout.MenuPageCanvas;

/** Launcher session status; local player/save selection remains independent. */
public final class AccountScreen extends Screen {
    private MenuPageCanvas.Canvas canvas;
    private Button logout;

    public AccountScreen(PvzceClient client) { super(client); }

    @Override protected void init() {
        canvas = MenuPageCanvas.fit(client);
        logout = new Button(Math.round(canvas.x(180)), Math.round(canvas.y(500)),
                Math.round(canvas.scaled(210)), Math.round(canvas.scaled(50)), "退出账号 / 离线", client.account()::logout).style(Button.Style.SEED_CHOOSER);
        addWidget(logout);
        addWidget(new Button(Math.round(canvas.x(410)), Math.round(canvas.y(500)),
                Math.round(canvas.scaled(210)), Math.round(canvas.scaled(50)), "返回", this::requestClose).style(Button.Style.SEED_CHOOSER));
        tick();
    }

    @Override public void tick() {
        if (logout != null) logout.setActive(client.account().view().state() != GameAccount.State.OFFLINE);
    }

    @Override public void render() {
        client.beginGuiView();
        MenuPageCanvas.renderPageBackground(client, this);
        canvas.panel(client, 70, 105, 660, 320);
        client.fonts().button().drawCentered("启动器账号", canvas.x(400), canvas.y(58),
                canvas.scaled(2F), .25F, .13F, .05F, 1F);
        var account = client.account().view();
        text(account.message(), 175, 1.25F);
        if (account.state() == GameAccount.State.ONLINE) {
            text(account.displayName(), 235, 1.7F);
            text("有效期至 " + java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault()).format(account.expiresAt()), 285, 1.15F);
        } else {
            text("登录需要从启动器重新获取一次性凭证。", 250, 1.15F);
        }
        text("网站账号与本地玩家存档分别管理。", 345, 1.15F);
        text("离线状态也可继续游戏。", 380, 1.15F);
        for (var widget : widgets) widget.render(client);
    }

    private void text(String value, float top, float size) {
        float scale = Math.min(canvas.scaled(size), canvas.scaled(610) /
                Math.max(1F, client.fonts().body().width(value, 1F)));
        client.fonts().body().drawCentered(value, canvas.x(400), canvas.y(top), scale, .98F, .91F, .72F, 1F);
    }
}
