package com.pvzce.client.gui.workshop;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
/** Session setup/teardown has its own cancellable screen. */
public final class WorkshopWaitingScreen extends Screen {
    public WorkshopWaitingScreen(PvzceClient client) { super(client); }
    protected void init() {
        addWidget(new Button(centerX(180), 20, 180, 30, WorkshopUi.text("cancel_test", "返回编辑器"), client::endWorkshopTest));
    }
    public void render() {
        renderBackground(0.08F, 0.12F, 0.10F);
        client.fonts().body().draw(WorkshopUi.text("loading", "正在加载临时沙盒…"), 20, client.guiHeight()/2F, 1F, 1,1,1,1);
        for (var widget : widgets) widget.render(client);
    }
    public void requestClose() { client.endWorkshopTest(); }
}
