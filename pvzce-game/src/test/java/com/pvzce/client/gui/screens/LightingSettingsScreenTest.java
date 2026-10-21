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
        boolean graphics = Boolean.getBoolean("pvzce.testGraphics");
        long nativeWindow = 0;
        if (graphics) {
            assertTrue(org.lwjgl.glfw.GLFW.glfwInit());
            org.lwjgl.glfw.GLFW.glfwDefaultWindowHints();
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_VISIBLE, org.lwjgl.glfw.GLFW.GLFW_FALSE);
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE, org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE);
            nativeWindow = org.lwjgl.glfw.GLFW.glfwCreateWindow(1920, 1080, "Lighting controls", 0, 0);
            assertNotEquals(0, nativeWindow);
            org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(nativeWindow);
            org.lwjgl.opengl.GL.createCapabilities();
            com.pvzce.client.renderer.RenderSystem.init();
        }
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
            if (graphics) {
                var textures = client.getClass().getDeclaredField("textures");
                textures.setAccessible(true);
                textures.set(client, new com.pvzce.client.renderer.texture.TextureManager(resources));
            }
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
                if (graphics) renderAndCapture(screen, size, "base");
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
                for (String tab : List.of("光效细节", "明暗曝光", "色彩 HSL")) {
                    click(screen, tab);
                    screen.initIfNeeded();
                    if (graphics) renderAndCapture(screen, size, tab);
                    for (var widget : screen.widgets()) {
                        assertTrue(widget.y() >= 36 && widget.x() >= 0
                                && widget.x() + widget.width() <= client.guiWidth()
                                && widget.y() + widget.height() <= client.guiHeight(), tab);
                    }
                    var slider = screen.widgets().stream()
                            .filter(com.pvzce.client.gui.components.Slider.class::isInstance)
                            .map(com.pvzce.client.gui.components.Slider.class::cast).findFirst().orElseThrow();
                    slider.mouseClicked(slider.x() + slider.width() / 2D, slider.y() + 1, 0);
                    slider.mouseDragged(slider.x() + slider.width() + 10, slider.y() + 1, 0);
                    slider.mouseReleased(slider.x() + slider.width(), slider.y() + 1, 0);
                    var parameter = switch (tab) {
                        case "光效细节" -> PvzceClientConfig.LightingParameter.SHADOW_SOFTNESS;
                        case "明暗曝光" -> PvzceClientConfig.LightingParameter.EXPOSURE;
                        default -> PvzceClientConfig.LightingParameter.VIBRANCE;
                    };
                    assertEquals(parameter.max(), PvzceClientConfig.load(client.gameDir()).lighting(parameter));
                    click(screen, "恢复光影默认值");
                }
                click(screen, "完成");
                assertSame(parent, client.currentScreen());
            }
            client.fonts().close();
            if (graphics) client.textures().close();
        } finally {
            if (graphics) {
                com.pvzce.client.renderer.vertex.Tesselator.INSTANCE.close();
                com.pvzce.client.renderer.RenderSystem.shader().close();
                org.lwjgl.glfw.GLFW.glfwDestroyWindow(nativeWindow);
                org.lwjgl.glfw.GLFW.glfwTerminate();
            }
        }
    }

    private static void renderAndCapture(LightingSettingsScreen screen, int[] size, String page) throws Exception {
        com.pvzce.client.renderer.RenderSystem.viewport(0, 0, size[0], size[1]);
        screen.render();
        assertEquals(org.lwjgl.opengl.GL11.GL_NO_ERROR, org.lwjgl.opengl.GL11.glGetError());
        String output = System.getProperty("pvzce.lightingUiCapture");
        if (output != null && size[0] == 1920) {
            var folder = java.nio.file.Path.of(output);
            java.nio.file.Files.createDirectories(folder);
            assertTrue(com.pvzce.client.FramebufferCapture.writePng(
                    folder.resolve("ui-" + page + ".png").toString(), size[0], size[1]));
        }
    }
}
