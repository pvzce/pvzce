package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.config.PvzceClientConfig;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.layout.GuiLayout;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * The first-run page: what language, and how big the interface should be.
 *
 * <p><b>Two questions and no more.</b> Both are about the player's machine rather than the game
 * (which language they read, how big their screen is), both have to be answered before anything
 * else can be read or clicked, and neither is discoverable afterwards - a player who cannot read
 * the interface will not find the settings page that changes it. Everything else the game can ask
 * later, from the title screen's player picker to the seed chooser.
 *
 * <p><b>It answers by changing the page itself.</b> Picking a language redraws every word here in
 * it, and picking a size redraws the whole interface at it, because both settings are applied the
 * moment they are chosen ({@code PvzceClient.setLanguage} / {@code setGuiScaleSetting}). A preview
 * panel would be a second copy of the interface shown at a scale the rest of the screen is not at -
 * and the honest preview of "can I read this" is the thing being read.
 *
 * <p><b>It opens instead of the title screen</b>, and never again once answered: see
 * {@code PvzceClientConfig.onboarded()} for why a config file that predates this page counts as
 * answered. The page itself is drawn over the title art, because the first thing a new player
 * should see is the game rather than a form.
 */
public final class OnboardingScreen extends Screen {
    /** The title screen's own art, so the two screens are one continuous first impression. */
    private static final Identifier TITLE_LOGO =
            Identifier.withDefaultNamespace("textures/gui/screen/title/title_logo");
    private static final Identifier TITLE_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/screen/title/title_background");

    /** How much of the dialogue frame's native border the panel draws. */
    private static final float FRAME_SCALE = 0.42F;

    /** The two steps, in order. */
    private static final int STEP_LANGUAGE = 0;
    private static final int STEP_UI_SIZE = 1;
    private static final int STEP_COUNT = 2;

    /** How many sizes the size step offers: auto plus one per scale the window can take. */
    private static final int MAX_ROWS = 5;

    /** How wide the panel may get: wider than this and the rows read as a list, not a page. */
    private static final float PANEL_MAX_WIDTH = 700F;
    /** The step dots' size, reserved inside the panel's layout. */
    private static final float DOTS = 8F;

    private int step = STEP_LANGUAGE;
    private int titleY;
    private float titleScale;
    private float panelX;
    private float panelY;
    private float panelW;
    private float panelH;
    private int rowH;
    private int buttonH;
    private float dotsY;
    /** The option rows' rectangles, top to bottom; also their hit boxes. */
    private float[][] rows = new float[0][];

    public OnboardingScreen(PvzceClient client) {
        super(client);
    }

    /** The languages on offer: the built-in one first, then whatever the pack stack ships. */
    private List<String> locales() {
        return GuiLang.availableLocales(client.resources());
    }

    /**
     * The size options, as GUI scales.
     *
     * <p>{@code 0} is auto - the scale this resolution wants - and comes first because it is the
     * one the page recommends; the explicit scales follow so a player who disagrees has somewhere
     * to go. Only scales this window can actually take are listed: offering 4x to a 640x480 window
     * would be offering a scale the client would then refuse.
     */
    private int[] sizeOptions() {
        int max = Math.max(1, client.maxAvailableGuiScale());
        int[] options = new int[Math.min(MAX_ROWS, max + 1)];
        options[0] = PvzceClientConfig.AUTO_GUI_SCALE;
        for (int i = 1; i < options.length; i++) {
            options[i] = i;
        }
        return options;
    }

    @Override
    protected void init() {
        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        int rowCount = step == STEP_LANGUAGE ? locales().size() : sizeOptions().length;

        // One centred panel rather than rows pinned to the top and a button pinned to the bottom:
        // the page has to read as a page at any GUI size, and the first thing a new player sees
        // should not be a form with a hole in the middle of it. Every measurement below is derived
        // from the panel, so the rows, the dots, the title and the buttons cannot drift apart.
        panelW = Math.round(Math.min(guiW * 0.80F, PANEL_MAX_WIDTH));
        int pad = Math.max(10, Math.round(panelW / 32F));
        rowH = Math.max(24, Math.min(40, Math.round(panelW / 13F)));
        int gap = GuiLayout.gapFor(rowH);
        buttonH = Math.max(26, Math.min(44, Math.round(panelW / 12F)));
        titleScale = Math.min(1.9F, Math.max(1.05F, panelW / 430F));
        float titleLine = client.fonts().button().lineHeight(titleScale);
        float hintLine = client.fonts().body().lineHeight(0.9F);

        int rowsBlock = rowCount * rowH + Math.max(0, rowCount - 1) * gap;
        int contentH = pad + Math.round(titleLine) + 6 + Math.round(hintLine) + gap * 2
                + rowsBlock + gap * 2 + Math.round(DOTS) + buttonH + pad;
        panelX = (guiW - panelW) / 2F;
        panelY = Math.max(8F, (guiH - contentH) / 2F);
        panelH = contentH;

        float cursor = panelY + panelH - pad;
        titleY = Math.round(cursor - titleLine);
        cursor = titleY - 6 - hintLine;
        cursor -= gap * 2F;
        rows = new float[rowCount][];
        int rowX = Math.round(panelX + pad + 6);
        int rowW = Math.round(panelW) - (pad + 6) * 2;
        for (int i = 0; i < rowCount; i++) {
            cursor -= rowH;
            rows[i] = new float[] {rowX, cursor, rowW, rowH};
            cursor -= gap;
        }
        cursor -= gap;
        dotsY = cursor - DOTS;
        int buttonY = Math.round(panelY + pad);

        int buttonWidth = Math.min(200, Math.round(panelW - pad * 3) / 2);
        if (step > 0) {
            addWidget(new Button(Math.round(panelX + pad), buttonY, buttonWidth, buttonH,
                    GuiLang.raw("pvzce.onboarding.back", "上一步"), () -> {
                step--;
                rebuild();
            }));
            addWidget(new Button(Math.round(panelX + panelW - pad - buttonWidth), buttonY,
                    buttonWidth, buttonH,
                    GuiLang.raw("pvzce.onboarding.finish", "开始游戏"), client::completeOnboarding));
        } else {
            addWidget(new Button(Math.round(panelX + (panelW - buttonWidth) / 2F), buttonY,
                    buttonWidth, buttonH,
                    GuiLang.raw("pvzce.onboarding.next", "下一步"), () -> {
                step++;
                rebuild();
            }));
        }
    }

    /** Rebuilds the page for the new step (or the new language) without leaving it. */
    private void rebuild() {
        clearWidgets();
        init();
    }

    /** One row's label: the option, plus what it currently means. */
    private String rowLabel(int index) {
        if (step == STEP_LANGUAGE) {
            String locale = locales().get(index);
            String name = GuiLang.localeName(locale);
            boolean current = locale.equals(GuiLang.locale());
            return (current ? "● " : "　") + name;
        }
        int option = sizeOptions()[index];
        int effective = option == PvzceClientConfig.AUTO_GUI_SCALE
                ? client.recommendedGuiScale() : option;
        String name = option == PvzceClientConfig.AUTO_GUI_SCALE
                ? GuiLang.raw("pvzce.onboarding.auto", "自动（推荐）")
                : option + "x";
        String size = client.window().width() + "×" + client.window().height()
                + " → " + com.pvzce.common.util.MathUtil.ceilDiv(client.window().width(), effective)
                + "×" + com.pvzce.common.util.MathUtil.ceilDiv(client.window().height(), effective);
        boolean current = client.config().guiScale() == option
                || (option == PvzceClientConfig.AUTO_GUI_SCALE && effective == client.guiScale());
        return (current ? "● " : "　") + name + "　" + size;
    }

    @Override
    protected void onMouseClicked(double guiX, double guiY, int button) {
        // The screen's own hook: `mouseClicked` is final and dispatches the modal dialogs and the
        // widgets first, which is the order a row must not jump ahead of. A row that is hit is
        // simply the last link in that chain.
        if (button == 0) {
            chooseRowAt(guiX, guiY);
        }
    }

    /** Applies the row under the pointer; answers whether one was hit. */
    private boolean chooseRowAt(double guiX, double guiY) {
        for (int i = 0; i < rows.length; i++) {
            float[] r = rows[i];
            if (guiX >= r[0] && guiX < r[0] + r[2] && guiY >= r[1] && guiY < r[1] + r[3]) {
                if (step == STEP_LANGUAGE) {
                    client.setLanguage(locales().get(i));
                } else {
                    client.setGuiScaleSetting(sizeOptions()[i]);
                }
                // Both settings redraw the interface at once, so the page has to be rebuilt for
                // the new strings/scale. `refreshGui()` already did that for a scale change; a
                // language change needs it too, and `setLanguage` asks for the same thing.
                rebuild();
                return true;
            }
        }
        return false;
    }

    /**
     * The page is fully keyboard-driven as well as clickable.
     *
     * <p>Arrow keys walk the current step's options and apply each one as it is reached, which is
     * the same live preview a click gives; Enter is "下一步" and then "开始游戏"; Escape goes back a
     * step and never leaves the page (there is nothing behind it to leave *to* - a fresh install
     * has no title screen yet, and a first-run page a key can skip is a page that skipped itself).
     */
    @Override
    public void keyPressed(int key) {
        if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_RIGHT) {
            move(1);
            return;
        }
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_LEFT) {
            move(-1);
            return;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            advance();
            return;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && step > 0) {
            step--;
            rebuild();
            return;
        }
        super.keyPressed(key);
    }

    /** Steps one option forward or back within the current step, wrapping around. */
    private void move(int delta) {
        if (step == STEP_LANGUAGE) {
            List<String> locales = locales();
            int index = locales.indexOf(GuiLang.locale());
            client.setLanguage(locales.get(Math.floorMod(index + delta, locales.size())));
        } else {
            int[] options = sizeOptions();
            int index = currentSizeIndex(options);
            client.setGuiScaleSetting(options[Math.floorMod(index + delta, options.length)]);
        }
        rebuild();
    }

    /** Which size option is in force: the setting itself, or the one auto resolves to. */
    private int currentSizeIndex(int[] options) {
        int setting = client.config().guiScale();
        for (int i = 0; i < options.length; i++) {
            if (options[i] == setting) {
                return i;
            }
        }
        return 0;
    }

    /** Enter: on to the next step, or out of the page. */
    private void advance() {
        if (step < STEP_COUNT - 1) {
            step++;
            rebuild();
        } else {
            client.completeOnboarding();
        }
    }

    @Override
    public boolean blurredBackdrop() {
        return false;
    }

    @Override
    public void render() {
        client.beginGuiView();
        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        renderBackground(0.16F, 0.3F, 0.13F);
        if (client.hasTexture(TITLE_BACKGROUND)) {
            // The title screen's own art, so the two screens are one continuous first impression.
            client.drawTexture(TITLE_BACKGROUND, 0, 0, guiW, guiH, -1F, 1F, 1F, 1F, 1F);
        }
        // A dark wash over the art: the page's text is the point, and the lawn behind it is busy.
        client.drawSolid(0, 0, guiW, guiH, -0.5F, 0.04F, 0.06F, 0.09F, 0.55F);

        renderLogo(guiW);
        // The game's own dialogue frame: this page is a panel over the art, and it is the same
        // stone frame the pause menu and the confirmations are drawn with.
        com.pvzce.client.gui.components.NinePatch.drawDialogFrame(client, panelX, panelY, panelW,
                panelH, -0.3F, FRAME_SCALE);

        String title = GuiLang.raw("pvzce.onboarding.title", "欢迎来到植物大战僵尸社区版");
        client.fonts().button().draw(title,
                panelX + (panelW - client.fonts().button().width(title, titleScale)) / 2F, titleY,
                titleScale, 1F, 1F, 1F, 1F);
        String hint = step == STEP_LANGUAGE
                ? GuiLang.raw("pvzce.onboarding.language.hint", "选择界面语言")
                : GuiLang.raw("pvzce.onboarding.size.hint", "选择界面大小：本机分辨率已为你选好一个");
        client.fonts().body().draw(hint,
                panelX + (panelW - client.fonts().body().width(hint, 0.9F)) / 2F,
                titleY - 6 - client.fonts().body().lineHeight(0.9F), 0.9F, 0.88F, 0.93F, 0.88F, 1F);

        for (int i = 0; i < rows.length; i++) {
            renderRow(i);
        }
        renderStepDots();
        for (var widget : widgets) {
            widget.render(client);
        }
    }

    /**
     * The game's logo above the panel, if the window has room for it.
     *
     * <p>Above is {@code +Y}: the GUI projection's origin is the bottom-left, so a panel's top edge
     * is {@code panelY + panelH} and the space the logo may use is what is left over the panel.
     */
    private void renderLogo(int guiW) {
        float above = client.guiHeight() - (panelY + panelH);
        if (!client.hasTexture(TITLE_LOGO) || above < 40F) {
            return;
        }
        float logoHeight = Math.min(above - 16F, panelW / 3.4F);
        float logoWidth = logoHeight * 3F;
        client.drawTexture(TITLE_LOGO, (guiW - logoWidth) / 2F, panelY + panelH + 8F,
                logoWidth, logoHeight, -0.4F, 1F, 1F, 1F, 0.95F);
    }

    /** One option: a plate, the label, and a highlight when it is the one in force. */
    private void renderRow(int index) {
        float[] r = rows[index];
        boolean selected = rowLabel(index).startsWith("●");
        com.pvzce.client.renderer.SpriteRenderer.solid(r[0], r[1], r[2], r[3], -0.2F,
                0.05F, 0.06F, 0.07F, selected ? 0.92F : 0.72F);
        if (selected) {
            com.pvzce.client.renderer.SpriteRenderer.solid(r[0], r[1], 3, r[3], -0.1F,
                    0.95F, 0.85F, 0.3F, 1F);
        }
        String label = rowLabel(index);
        float scale = Math.max(0.9F, Math.min(1.25F, r[3] / 30F));
        client.fonts().body().draw(label, r[0] + 10F,
                r[1] + (r[3] - client.fonts().body().lineHeight(scale)) / 2F, scale,
                selected ? 1F : 0.86F, selected ? 1F : 0.9F, selected ? 1F : 0.86F, 1F);
    }

    /** The step indicator: two dots, the current one filled. */
    private void renderStepDots() {
        float gap = 10F;
        float total = STEP_COUNT * DOTS + (STEP_COUNT - 1) * gap;
        float x = panelX + (panelW - total) / 2F;
        for (int i = 0; i < STEP_COUNT; i++) {
            boolean here = i == step;
            com.pvzce.client.renderer.SpriteRenderer.solid(x + i * (DOTS + gap), dotsY, DOTS, DOTS,
                    -0.1F, here ? 0.95F : 0.62F, here ? 0.85F : 0.64F, here ? 0.3F : 0.62F, 1F);
        }
    }
}
