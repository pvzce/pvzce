package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.EditBox;
import com.pvzce.client.gui.layout.MenuPageCanvas;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.network.packet.RoomC2S;

/** Create/join and the room's preparation roster, using the existing menu canvas and seed chooser. */
public final class MultiplayerScreen extends Screen {
    private MenuPageCanvas.Canvas canvas;
    private EditBox nickname;
    private EditBox address;
    private String layoutKey = "";
    private Button join;
    private Button start;
    private Button prepare;

    public MultiplayerScreen(PvzceClient client) { super(client); }

    @Override protected void init() {
        canvas = MenuPageCanvas.fit(client);
        rebuild();
    }

    private void rebuild() {
        String savedAddress = address == null ? client.roomAddress() : address.value();
        String savedName = nickname == null ? "玩家" : nickname.value();
        clearWidgets();
        start = null;
        prepare = null;
        layoutKey = key();
        var room = client.multiplayer();
        if (room == null) {
            nickname = box(220, 166, 420, savedName, 24);
            address = box(220, 226, 420, savedAddress.isBlank() ? "127.0.0.1:" + PvzceConstants.MULTIPLAYER_PORT : savedAddress, 256);
            join = button(280, 286, 240, "加入 / 重新连接", () -> client.joinMultiplayer(address.value(), nickname.value()));
            button(150, 366, 240, "创建合作房间", () -> client.hostMultiplayer(PvzceConstants.MULTIPLAYER_PORT, false));
            button(410, 366, 240, "创建对战房间", () -> client.hostMultiplayer(PvzceConstants.MULTIPLAYER_PORT, true));
            button(300, 500, 200, "返回", client::showTitle);
        } else {
            nickname = null; address = null; join = null;
            if (room.host()) {
                button(90, 420, 190, room.versus() ? "选择对战地图" : "选择关卡",
                        () -> client.openScreen(new LevelSelectScreen(client)));
                start = button(305, 420, 190, room.versus() ? "开始对战" : "开始合作",
                        () -> client.connection().send(new RoomC2S("start", "", "")));
                button(520, 420, 190, "关闭房间", client::leaveMultiplayer);
            } else {
                prepare = button(180, 420, 210, room.levelId().isEmpty() ? "等待房主选关"
                        : room.ready() ? "已准备" : room.versus() ? "准备对战" : "选卡并准备", client::prepareMultiplayer);
                button(410, 420, 210, "离开房间", client::leaveMultiplayer);
            }
            if (room.state().equals("playing") && !client.level().levelId().isEmpty())
                button(300, 500, 200, "返回战场", client::popScreen);
        }
        updateActions();
    }

    private String key() {
        var room = client.multiplayer();
        return room == null ? "closed" : room.state() + room.host() + room.levelId() + room.versus() + room.teamId() + room.ready();
    }

    private Button button(int x, int y, int width, String title, Runnable action) {
        Button button = new Button(Math.round(canvas.x(x)), Math.round(canvas.y(y + 44)),
                Math.round(canvas.scaled(width)), Math.round(canvas.scaled(44)), title, action)
                .style(Button.Style.SEED_CHOOSER);
        addWidget(button); return button;
    }

    private EditBox box(int x, int y, int width, String value, int limit) {
        EditBox box = new EditBox(Math.round(canvas.x(x)), Math.round(canvas.y(y + 38)),
                Math.round(canvas.scaled(width)), Math.round(canvas.scaled(38)), limit, () -> { });
        box.setValue(value); addWidget(box); return box;
    }

    @Override public void tick() {
        if (canvas != null && !layoutKey.equals(key())) rebuild();
        if (join != null) join.setActive(!client.connectingMultiplayer());
        updateActions();
    }

    private void updateActions() {
        var room = client.multiplayer();
        if (start != null) start.setActive(room.canStart());
        if (prepare != null) prepare.setActive(!room.ready() && !room.levelId().isEmpty());
    }

    @Override public void render() {
        client.beginGuiView();
        MenuPageCanvas.renderPageBackground(client, this);
        canvas.panel(client, 60, 115, 680, 370);
        var room = client.multiplayer();
        String title = room == null ? "多人游戏" : room.versus() ? "真人对战房间" : "合作房间";
        client.fonts().button().drawCentered(title, canvas.x(400), canvas.y(70), canvas.scaled(2F), .25F, .13F, .05F, 1F);
        if (room == null) {
            text("昵称", 110, 184, 1.1F);
            text("服务器地址", 110, 244, 1.1F);
            text("地址格式：IP:端口 · 房主端口 " + PvzceConstants.MULTIPLAYER_PORT, 120, 340, 1F);
            text("合作共享阳光，各自选卡；对战为植物对僵尸。", 120, 452, 1F);
        } else {
            String name = room.levelId().isBlank() ? "等待房主选择关卡" : client.levelList().stream()
                    .filter(l -> l.id().equals(room.levelId())).map(l -> l.name()).findFirst().orElse(room.levelId());
            String mode = room.versus() ? "植物 vs 僵尸 · 真人 1v1" : "合作闯关 · 2～4 人";
            if (room.versus() && !room.teamId().isEmpty()) {
                String team = client.levelList().stream().filter(l -> l.id().equals(room.levelId()))
                        .flatMap(l -> l.teams().stream()).filter(t -> t.id().equals(room.teamId()))
                        .map(t -> t.name()).findFirst().orElse(room.teamId());
                mode += " · 你是" + team;
            }
            text(mode, 110, 150, 1.1F);
            text(name, 110, 188, 1.2F);
            int top = 234;
            for (String player : room.players()) { text(player, 125, top, 1.1F); top += 42; }
            text(room.versus() ? "植物收满目标阳光获胜；僵尸突破最左端获胜。" : "进入菜单时战场继续运行；重开和切关由房主操作。", 110, 390, .9F);
        }
        text(client.multiplayerMessage(), 80, 565, .9F);
        for (var widget : widgets) widget.render(client);
    }

    private void text(String value, int x, int y, float size) {
        float scale = Math.min(canvas.scaled(size), canvas.scaled(800 - x - 65) /
                Math.max(1F, client.fonts().body().width(value, 1F)));
        client.fonts().body().draw(value, canvas.x(x), canvas.y(y), scale, .98F, .91F, .72F, 1F);
    }

    @Override public void requestClose() {
        if (client.isMultiplayer() && !client.level().levelId().isEmpty()) client.popScreen();
        else { client.leaveMultiplayer(); client.showTitle(); }
    }
}
