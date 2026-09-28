package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.layout.GuiLayout;
import com.pvzce.common.PvzceLicense;

/**
 * "关于"页：把 GPL-3.0 要求的法律声明摆给玩家看。
 *
 * <p>这个页面的存在理由是合规，不是产品功能：GPL-3.0 §5(d) 要图形界面显示版权声明、无担保声明与
 * 许可取得方式，而它给的例子就是 about box。设置里那一行"关于"因此不是装饰。
 *
 * <p>文本来自 {@link PvzceLicense#ABOUT_TEXT}——同一个常量也是启动日志用的那一份，
 * 改文本只改那一个文件。
 */
public final class AboutScreen extends Screen {
    private int titleY;
    private float titleScale;

    public AboutScreen(PvzceClient client) {
        super(client);
    }

    @Override
    protected void init() {
        int guiH = client.guiHeight();
        int titleReserve = Math.max(40, Math.min(72, guiH / 5));
        int buttonHeight = GuiLayout.fitHeight(guiH, 56, 1, titleReserve, 8);
        titleScale = Math.min(2.2F, Math.max(1.1F, guiH / 130F));
        titleY = guiH - titleReserve + Math.round(client.fonts().button().lineHeight(titleScale) * 0.35F);
        int buttonWidth = Math.min(220, client.guiWidth() - 24);
        addWidget(new Button(centerX(buttonWidth), Math.max(8, guiH / 24), buttonWidth, buttonHeight,
                GuiLang.raw("pvzce.about.close", "返回"), this::requestClose));
    }

    @Override
    public boolean blurredBackdrop() {
        return true;
    }

    @Override
    public void render() {
        client.beginGuiView();
        // 不透明的深色底铺满整屏，理由和 AlmanacScreen 一样：这一页整页都是要读的正文。
        // 一开始用的是"模糊的标题背景 + 半透明压暗"，结果白字压在明亮的天空与草坪上几乎读不出来
        // （`.smoke/license/about-bad.png` 那张就是）。法律声明读不清等于没显示。
        client.drawSolid(0, 0, client.guiWidth(), client.guiHeight(), -2F, 0.09F, 0.05F, 0.03F, 1F);
        String title = GuiLang.raw("pvzce.about.title", "关于");
        client.fonts().button().draw(title,
                (client.guiWidth() - client.fonts().button().width(title, titleScale)) / 2F,
                titleY, titleScale, 1, 1, 1, 1);
        // 底部留出"返回"按钮的位置，声明文本排在它上面；drawWrappedText 会在放不下时截断并加省略号。
        float bottom = Math.max(8, client.guiHeight() / 24) + GuiLayout.fitHeight(
                client.guiHeight(), 56, 1, Math.max(40, Math.min(72, client.guiHeight() / 5)), 8) + 12;
        float maxWidth = Math.min(560, client.guiWidth() - 32);
        drawWrappedText(PvzceLicense.ABOUT_TEXT, (client.guiWidth() - maxWidth) / 2F,
                titleY - client.fonts().button().lineHeight(titleScale) * 1.6F,
                maxWidth, 1F, 0.94F, 0.94F, 0.90F, 1F, bottom);
        for (var widget : widgets) {
            widget.render(client);
        }
    }
}
