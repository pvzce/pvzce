package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceWindow;
import com.pvzce.client.config.PvzceClientConfig;
import com.pvzce.client.config.PvzceClientConfig.ShaderEffect;
import com.pvzce.client.gui.components.Button;
import com.pvzce.testutil.ClientHarness;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LightingSettingsScreenTest {
    private static Button button(LightingSettingsScreen screen, String label) {
        return screen.widgets().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b -> b.label().startsWith(label)).findFirst().orElseThrow();
    }

    private static void click(LightingSettingsScreen screen, String label) {
        var target = button(screen, label);
        screen.dispatchMouseClicked(target.x() + target.width() / 2D, target.y() + target.height() / 2D, 0);
    }

    @Test
    void controlsFitAndChangesSaveWithoutLosingTheParentScreen() throws Exception {
        try (var harness = ClientHarness.create("lighting-settings")) {
            var client = harness.client();
            client.config().setGuiScale(2);
            var singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            var window = (PvzceWindow) ((sun.misc.Unsafe) singleton.get(null)).allocateInstance(PvzceWindow.class);
            var field = client.getClass().getDeclaredField("window");
            field.setAccessible(true);
            field.set(client, window);
            var resources = new com.pvzce.common.resource.PvzceResourceManager(Thread.currentThread().getContextClassLoader());
            resources.init(client.gameDir());
            var resourcesField = client.getClass().getDeclaredField("resources");
            resourcesField.setAccessible(true);
            resourcesField.set(client, resources);
            com.pvzce.client.gui.GuiLang.reload(resources, "zh_cn");
            var fontsField = client.getClass().getDeclaredField("fonts");
            fontsField.setAccessible(true);
            fontsField.set(client, new com.pvzce.client.renderer.font.Fonts(client.resources(), client::guiScale));
            for (int[] size : new int[][]{{1920, 1080}, {854, 480}, {640, 960}}) {
                for (int dimension = 0; dimension < 2; dimension++) {
                    var value = PvzceWindow.class.getDeclaredField(dimension == 0 ? "width" : "height");
                    value.setAccessible(true);
                    value.setInt(window, size[dimension]);
                }
                var parent = new SettingsScreen(client);
                client.setScreenReplacing(parent);
                var screen = new LightingSettingsScreen(client);
                // Exercise stack navigation without requesting a GL backdrop capture.
                var screens = client.getClass().getDeclaredField("screens");
                screens.setAccessible(true);
                ((com.pvzce.client.gui.ScreenStack) screens.get(client)).push(screen);
                screen.initIfNeeded();
                List<Button> buttons = screen.widgets().stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
                for (var b : buttons) {
                    assertTrue(b.x() >= 0 && b.y() >= 0 && b.x() + b.width() <= client.guiWidth()
                            && b.y() + b.height() <= client.guiHeight(), b.label());
                    for (var other : buttons) {
                        if (other == b) continue;
                        assertTrue(b.x() + b.width() <= other.x() || other.x() + other.width() <= b.x()
                                || b.y() + b.height() <= other.y() || other.y() + other.height() <= b.y(),
                                b.label() + " overlaps " + other.label());
                    }
                }
                click(screen, "局部光源：");
                assertFalse(client.config().effectEnabled(ShaderEffect.LOCAL_LIGHTS));
                assertFalse(button(screen, "局部光源阴影").isActive());
                click(screen, "恢复光影默认值");
                assertTrue(button(screen, "局部光源阴影").isActive());
                click(screen, "水下焦散");
                assertFalse(PvzceClientConfig.load(client.gameDir()).effectEnabled(ShaderEffect.WATER_CAUSTICS));
                click(screen, "恢复光影默认值");
                click(screen, "完成");
                assertSame(parent, client.currentScreen());
            }
        }
    }
}
