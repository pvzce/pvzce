package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.config.SavedServers;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.*;
import com.pvzce.client.gui.layout.MenuPageCanvas;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.network.LanDiscovery;
import com.pvzce.common.network.packet.RoomC2S;
import java.io.IOException;
import java.util.List;

/** Server bookmarks and LAN discovery before joining; authoritative room roster after joining. */
public final class MultiplayerScreen extends Screen {
    private MenuPageCanvas.Canvas canvas;
    private EditBox nickname;
    private String nicknameValue = "玩家";
    private SavedServers saved;
    private String storageError = "";
    private String notice = "";
    private String layoutKey = "";
    private LanDiscovery.Browser discovery;
    private AbstractSelectionList<SavedServers.Entry> servers;
    private AbstractSelectionList<LanDiscovery.Room> lanRooms;
    private List<SavedServers.Entry> shownServers = List.of();
    private List<LanDiscovery.Room> shownLan = List.of();
    private String selectedAddress = "";
    private boolean lanSelected;
    private long lastClickNanos;
    private String lastClickAddress = "";
    private Button join, edit, delete, direct, add, createCoop, createVersus;
    private Button start, prepare;

    public MultiplayerScreen(PvzceClient client) { super(client); }

    @Override protected void init() {
        if (nickname != null) nicknameValue = nickname.value();
        if (saved == null && storageError.isEmpty()) {
            try { saved = new SavedServers(client.gameDir()); nicknameValue = saved.nickname(); }
            catch (IOException e) { storageError = e.getMessage(); }
        }
        canvas = MenuPageCanvas.fit(client);
        rebuild();
    }

    private void rebuild() {
        if (nickname != null) nicknameValue = nickname.value();
        clearWidgets();
        start = null; prepare = null; join = null; edit = null; delete = null; direct = null; add = null;
        createCoop = null; createVersus = null; servers = null; lanRooms = null;
        layoutKey = key();
        var room = client.multiplayer();
        if (room == null) {
            nickname = new EditBox(Math.round(canvas.x(550)), Math.round(canvas.y(99)),
                    Math.round(canvas.scaled(200)), Math.round(canvas.scaled(32)), 24, this::joinSelected).style(EditBox.Style.PARCHMENT);
            nickname.setValue(nicknameValue); addWidget(nickname);
            servers = list(50, 143, 700, 156, 52, this::renderServer);
            servers.setOnRowClick(entry -> select(entry.address(), false)); addWidget(servers);
            lanRooms = list(50, 350, 700, 104, 52, this::renderLan);
            lanRooms.setOnRowClick(entry -> { select(entry.address(), true); if (entry.joinable()) joinSelected(); }); addWidget(lanRooms);
            join = button(50, 478, 166, 38, "加入服务器", this::joinSelected);
            direct = button(226, 478, 166, 38, "直接连接", () -> client.openScreen(new ServerEntryScreen(client, "直接连接", true, null, entry -> {
                client.popScreen(); connect(entry.address());
            })));
            add = button(402, 478, 166, 38, "添加服务器", () -> editServer(-1));
            button(578, 478, 172, 38, "刷新列表", this::refresh);
            edit = button(50, 530, 105, 34, "编辑", () -> editServer(savedIndex()));
            delete = button(165, 530, 105, 34, "删除", this::deleteServer);
            createCoop = button(280, 530, 160, 34, "创建合作房间", () -> host(false));
            createVersus = button(450, 530, 160, 34, "创建对战房间", () -> host(true));
            button(620, 530, 130, 34, "返回", client::showTitle);
            button(55, 75, 365, 32, (client.roomRequiresAllPacks() ? "[✓] " : "[ ] ") + "必须接受所有数据包", () -> {
                client.roomRequiresAllPacks(!client.roomRequiresAllPacks()); rebuild();
            });
            if (discovery == null) discovery = new LanDiscovery.Browser();
            shownServers = List.of(); shownLan = List.of();
            updateLists();
        } else {
            nickname = null;
            stopDiscovery();
            if (room.host()) {
                button(90, 420, 190, 44, room.versus() ? "选择对战地图" : "选择关卡",
                        () -> client.openScreen(new LevelSelectScreen(client)));
                start = button(305, 420, 190, 44, room.versus() ? "开始对战" : "开始合作",
                        () -> client.connection().send(new RoomC2S("start", "", "")));
                button(520, 420, 190, 44, "关闭房间", client::leaveMultiplayer);
            } else {
                prepare = button(180, 420, 210, 44, room.levelId().isEmpty() ? "等待房主选关"
                        : room.ready() ? "已准备" : room.versus() ? "准备对战" : "选卡并准备", client::prepareMultiplayer);
                button(410, 420, 210, 44, "离开房间", client::leaveMultiplayer);
            }
            if (room.state().equals("playing") && !client.level().levelId().isEmpty())
                button(300, 500, 200, 44, "返回战场", client::popScreen);
        }
        updateActions();
    }

    private <E> AbstractSelectionList<E> list(int x, int top, int width, int height, int rowHeight,
                                             AbstractSelectionList.EntryRenderer<E> renderer) {
        int row = Math.round(canvas.scaled(rowHeight));
        int pixels = height / rowHeight * row;
        var list = new AbstractSelectionList<E>(Math.round(canvas.x(x)), Math.round(canvas.y(top)) - pixels,
                Math.round(canvas.scaled(width)), pixels, row, renderer);
        list.setStyle(AbstractSelectionList.Style.WOOD); return list;
    }
    private void updateLists() {
        List<SavedServers.Entry> next = saved == null ? List.of() : saved.entries();
        if (!shownServers.equals(next) || servers.entries().isEmpty() && !next.isEmpty()) {
            shownServers = next; servers.setEntries(next); preserveSelection();
        }
        var local = discovery == null ? List.<LanDiscovery.Room>of() : discovery.rooms();
        if (!shownLan.equals(local)) { shownLan = local; lanRooms.setEntries(local); preserveSelection(); }
    }
    private void preserveSelection() {
        int selected = -1;
        for (int i = 0; i < shownServers.size(); i++) if (!lanSelected && shownServers.get(i).address().equals(selectedAddress)) selected = i;
        servers.select(selected);
        selected = -1;
        for (int i = 0; i < shownLan.size(); i++) if (lanSelected && shownLan.get(i).address().equals(selectedAddress)) selected = i;
        lanRooms.select(selected);
    }
    private void select(String address, boolean lan) {
        long now = System.nanoTime();
        boolean doubleClick = !lan && address.equals(lastClickAddress) && now - lastClickNanos < 350_000_000L;
        lastClickAddress = address; lastClickNanos = now;
        selectedAddress = address; lanSelected = lan; notice = ""; preserveSelection(); updateActions();
        if (doubleClick) joinSelected();
    }
    private int savedIndex() { return lanSelected || servers == null ? -1 : servers.selectedIndex(); }
    private void joinSelected() {
        if (client.connectingMultiplayer()) return;
        if (lanSelected) {
            var room = shownLan.stream().filter(r -> r.address().equals(selectedAddress)).findFirst().orElse(null);
            if (room == null || !room.joinable()) { notice = "该局域网房间当前无法加入"; return; }
        } else if (savedIndex() < 0) return;
        connect(selectedAddress);
    }
    private void connect(String address) {
        if (client.connectingMultiplayer()) return;
        try { rememberNickname(); client.joinMultiplayer(address, nicknameValue); }
        catch (IOException | IllegalArgumentException e) { notice = e.getMessage(); }
    }
    private void rememberNickname() throws IOException {
        if (nickname != null) nicknameValue = nickname.value().strip();
        if (nicknameValue.isEmpty()) nicknameValue = "玩家";
        if (saved != null) saved.nickname(nicknameValue);
    }
    private void host(boolean versus) {
        try { rememberNickname(); client.hostMultiplayer(PvzceConstants.MULTIPLAYER_PORT, versus); }
        catch (IOException e) { notice = "保存昵称失败：" + e.getMessage(); }
    }
    private void editServer(int index) {
        if (saved == null) return;
        var initial = index < 0 ? null : saved.entries().get(index);
        client.openScreen(new ServerEntryScreen(client, index < 0 ? "添加服务器" : "编辑服务器", false, initial, entry -> {
            rememberNickname(); saved.put(index, entry); selectedAddress = entry.address(); lanSelected = false;
            client.popScreen(); updateLists(); updateActions();
        }));
    }
    private void deleteServer() {
        int index = savedIndex();
        if (index < 0) return;
        var target = saved.entries().get(index);
        showDialog(ConfirmDialog.create(client, "删除服务器", "从列表移除“" + target.name() + "”？", "删除", () -> {
            try { saved.remove(index); selectedAddress = ""; updateLists(); updateActions(); }
            catch (IOException e) { notice = "保存列表失败：" + e.getMessage(); }
        }, null));
    }
    private void refresh() {
        stopDiscovery(); discovery = new LanDiscovery.Browser();
        shownLan = List.of(); lanRooms.setEntries(shownLan);
        if (saved == null) {
            try { saved = new SavedServers(client.gameDir()); storageError = ""; }
            catch (IOException e) { storageError = e.getMessage(); }
        }
        notice = ""; updateLists(); updateActions();
    }
    private void stopDiscovery() { if (discovery != null) { discovery.close(); discovery = null; } }
    @Override protected void onRemoved() { stopDiscovery(); }

    private String key() {
        var room = client.multiplayer();
        return room == null ? "closed" : room.state() + room.host() + room.levelId() + room.versus() + room.teamId() + room.ready();
    }
    private Button button(int x, int top, int width, int height, String title, Runnable action) {
        var button = new Button(Math.round(canvas.x(x)), Math.round(canvas.y(top + height)),
                Math.round(canvas.scaled(width)), Math.round(canvas.scaled(height)), title, action).style(Button.Style.SEED_CHOOSER);
        addWidget(button); return button;
    }
    @Override public void tick() {
        if (!layoutKey.equals(key())) rebuild();
        if (servers != null) updateLists();
        updateActions();
    }
    private void updateActions() {
        var room = client.multiplayer();
        if (start != null) start.setActive(room.canStart());
        if (prepare != null) prepare.setActive(!room.ready() && !room.levelId().isEmpty());
        if (join != null) {
            boolean busy = client.connectingMultiplayer();
            boolean selected = lanSelected ? shownLan.stream().anyMatch(r -> r.address().equals(selectedAddress) && r.joinable()) : savedIndex() >= 0;
            join.setActive(!busy && selected); direct.setActive(!busy); add.setActive(!busy && saved != null);
            edit.setActive(!busy && savedIndex() >= 0); delete.setActive(!busy && savedIndex() >= 0);
            createCoop.setActive(!busy); createVersus.setActive(!busy);
        }
    }
    @Override public void render() {
        client.beginGuiView(); MenuPageCanvas.renderPageBackground(client, this);
        var room = client.multiplayer();
        String title = room == null ? "多人游戏" : room.versus() ? "真人对战房间" : "合作房间";
        client.fonts().button().drawCentered(title, canvas.x(400), canvas.y(room == null ? 48 : 70), canvas.scaled(1.8F), .25F, .13F, .05F, 1);
        if (room == null) {
            text("昵称", 490, 91, 1, true);
            text("已保存的服务器", 55, 125, 1.15F, true);
            text("局域网游戏 · 点击房间加入", 55, 331, 1.15F, true);
            text(!storageError.isEmpty() ? storageError : !notice.isEmpty() ? notice : client.connectingMultiplayer() ? "正在连接…" : client.multiplayerMessage(), 55, 589, .85F, true);
        } else {
            canvas.panel(client, 60, 115, 680, 370);
            String name = room.levelId().isBlank() ? "等待房主选择关卡" : client.levelList().stream()
                    .filter(l -> l.id().equals(room.levelId())).map(l -> l.name()).findFirst().orElse(room.levelId());
            String mode = room.versus() ? "植物 vs 僵尸 · 真人 1v1" : "合作闯关 · 2～4 人";
            if (room.versus() && !room.teamId().isEmpty()) {
                String team = client.levelList().stream().filter(l -> l.id().equals(room.levelId()))
                        .flatMap(l -> l.teams().stream()).filter(t -> t.id().equals(room.teamId()))
                        .map(t -> t.name()).findFirst().orElse(room.teamId());
                mode += " · 你是" + team;
            }
            text(mode, 110, 150, 1.1F); text(name, 110, 188, 1.2F);
            int top = 234;
            for (String player : room.players()) { text(player, 125, top, 1.1F); top += 42; }
            text(room.versus() ? "植物收满目标阳光获胜；僵尸突破最左端获胜。" : "进入菜单时战场继续运行；重开和切关由房主操作。", 110, 390, .9F);
            text(client.multiplayerMessage(), 80, 565, .9F);
        }
        for (var widget : widgets) widget.render(client);
        if (room == null) {
            if (shownServers.isEmpty()) text("还没有服务器，点击“添加服务器”保存常用地址", 75, 210, .95F);
            if (shownLan.isEmpty()) text(discovery == null ? "正在扫描局域网…" : discovery.status(), 75, 403, .95F);
        }
    }
    private void renderServer(PvzceClient ignored, SavedServers.Entry server, int x, int y) {
        rowText(server.name(), server.address(), "已保存", x, y);
    }
    private void renderLan(PvzceClient ignored, LanDiscovery.Room room, int x, int y) {
        String state = !room.protocol().equals(com.pvzce.common.network.PacketRegistry.fingerprint()) ? "网络包不符"
                : room.players() >= room.capacity() ? "房间已满" : room.versus() && room.playing() ? "对战已开始" : room.playing() ? "游戏中" : "等待加入";
        rowText(room.name(), room.address() + " · " + (room.versus() ? "真人对战" : "合作") + " · " + room.players() + "/" + room.capacity(), state, x, y);
    }
    private void rowText(String name, String address, String status, int x, int y) {
        float scale = canvas.scaled(1.05F), small = canvas.scaled(.85F);
        float maxWidth = canvas.scaled(505);
        scale = Math.min(scale, maxWidth / Math.max(1, client.fonts().body().width(name, 1)));
        client.fonts().body().draw(name, x + canvas.scaled(10), y + canvas.scaled(32), scale, 1, .94F, .77F, 1);
        small = Math.min(small, maxWidth / Math.max(1, client.fonts().body().width(address, 1)));
        client.fonts().body().draw(address, x + canvas.scaled(10), y + canvas.scaled(13), small, .80F, .77F, .65F, 1);
        float statusScale = canvas.scaled(.85F);
        client.fonts().body().draw(status, canvas.x(730) - client.fonts().body().width(status, statusScale),
                y + canvas.scaled(29), statusScale, .8F, .88F, .64F, 1);
    }
    private void text(String value, int x, int y, float size) {
        text(value, x, y, size, false);
    }
    private void text(String value, int x, int y, float size, boolean paper) {
        float scale = Math.min(canvas.scaled(size), canvas.scaled(800 - x - 40) / Math.max(1, client.fonts().body().width(value, 1)));
        client.fonts().body().draw(value, canvas.x(x), canvas.y(y), scale, paper ? .25F : .98F, paper ? .13F : .91F, paper ? .05F : .72F, 1);
    }
    @Override public void requestClose() {
        if (client.isMultiplayer() && !client.level().levelId().isEmpty()) client.popScreen();
        else { client.leaveMultiplayer(); client.showTitle(); }
    }
}
