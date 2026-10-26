package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceWindow;
import com.pvzce.client.animation.AnimationManager;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.almanac.AlmanacEntries;
import com.pvzce.client.renderer.RenderSystem;
import com.pvzce.client.renderer.font.Fonts;
import com.pvzce.client.renderer.texture.TextureManager;
import com.pvzce.common.tag.TestContent;
import com.pvzce.testutil.ClientHarness;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

class AlmanacScreenTest {
    @Test
    void cardDataAndKeyboardNavigationFollowTheCatalogue() throws Exception {
        try (var resources = TestContent.loadBuiltInContentAndTags();
             var harness = ClientHarness.create("almanac")) {
            GuiLang.reload(resources, "zh_cn");
            var screen = new AlmanacScreen(harness.client());
            screen.show(AlmanacEntries.Page.PLANTS);
            screen.initIfNeeded();
            Identifier pea = Identifier.withDefaultNamespace("pea_shooter");
            assertEquals(pea, screen.currentEntry());
            // Slot JSON omits these; the resolved plant cost must still be displayed.
            assertEquals(100, invoke(screen, "plantCost", pea));
            assertEquals(com.pvzce.common.core.BuiltInRegistries.PLANTS.get(pea).cost().cooldownTicks(),
                    invoke(screen, "plantCooldown", pea));
            assertEquals(List.of("血量: 300"), stats(screen, AlmanacEntries.Page.PLANTS, pea));
            assertTrue(stats(screen, AlmanacEntries.Page.ZOMBIES,
                    Identifier.withDefaultNamespace("basic_zombie")).contains("速度: 0.23 格/秒"));
            screen.keyPressed(GLFW_KEY_DOWN);
            assertEquals(AlmanacEntries.plants().at(8), screen.currentEntry());
            screen.keyPressed(GLFW_KEY_PAGE_DOWN);
            assertEquals(AlmanacEntries.plants().at(Math.min(56, screen.entryCount() - 1)), screen.currentEntry());
            screen.keyPressed(GLFW_KEY_END);
            assertEquals(AlmanacEntries.plants().at(-1), screen.currentEntry());
            screen.keyPressed(GLFW_KEY_RIGHT);
            assertEquals(pea, screen.currentEntry());
            screen.selectEntry(-1);
            assertEquals(AlmanacEntries.plants().at(-1), screen.currentEntry());
            screen.keyPressed(GLFW_KEY_BACKSPACE);
            assertEquals(-1, screen.pageIndex());
            assertNull(screen.currentEntry());
        }
    }

    /** Real drawing and clicks: a card cannot silently disappear or extend behind the detail. */
    @Test
    void everySheetFitsAndEveryVisibleCardCanBeClickedAtDifferentWindowSizes() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("pvzce.testGraphics"));
        assertTrue(glfwInit());
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long nativeWindow = glfwCreateWindow(1920, 1080, "Almanac layout", 0, 0);
        assertNotEquals(0, nativeWindow);
        glfwMakeContextCurrent(nativeWindow);
        org.lwjgl.opengl.GL.createCapabilities();
        RenderSystem.init();
        try (var resources = TestContent.loadBuiltInContentAndTags();
             var harness = ClientHarness.create("almanac-drawing")) {
            var client = harness.client();
            var singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            var window = (PvzceWindow) ((sun.misc.Unsafe) singleton.get(null)).allocateInstance(PvzceWindow.class);
            set(client, "window", window);
            set(client, "resources", resources);
            set(client, "textures", new TextureManager(resources));
            set(client, "fonts", new Fonts(resources, client::guiScale));
            set(client, "animations", new AnimationManager(client, client.level(), resources));
            GuiLang.reload(resources, "zh_cn");
            client.setProfile(0, List.of(), true);
            try {
                for (int[] size : new int[][]{{1920, 1080}, {1280, 720}, {800, 600}, {640, 960}}) {
                    set(window, "width", size[0]);
                    set(window, "height", size[1]);
                    client.config().setGuiScale(2);
                    var screen = new AlmanacScreen(client);
                    screen.initIfNeeded();
                    for (float[] target : new float[][]{{200, 300, 0}, {590, 300, 1}, {400, 503, 2}}) {
                        screen.showIndex();
                        screen.render();
                        clickNative(screen, target[0], target[1]);
                        assertEquals((int) target[2], screen.pageIndex(), "index opens the requested catalogue");
                        screen.render();
                        clickNative(screen, 110, 581);
                        assertEquals(-1, screen.pageIndex(), "the footer returns to the index");
                    }
                    for (var catalogue : AlmanacEntries.all()) {
                        screen.show(catalogue.page());
                        int capacity = catalogue.page() == AlmanacEntries.Page.ZOMBIES ? 25 : 48;
                        if (catalogue.size() > capacity) {
                            screen.render();
                            clickNative(screen, 360, 581);
                            assertEquals(catalogue.at(capacity), screen.currentEntry());
                            screen.render();
                            clickNative(screen, 222, 581);
                            assertEquals(catalogue.at(0), screen.currentEntry());
                        }
                        for (int first = 0; first < catalogue.size(); first += capacity) {
                            screen.selectEntry(first);
                            screen.render();
                            assertEquals(org.lwjgl.opengl.GL11.GL_NO_ERROR, org.lwjgl.opengl.GL11.glGetError());
                            int visible = Math.min(capacity, catalogue.size() - first);
                            List<?> hits = (List<?>) field(screen, "hits");
                            float scale = (float) field(screen, "scale");
                            float ox = (float) field(screen, "originX"), oy = (float) field(screen, "originY");
                            for (int local = 0; local < visible; local++) {
                                Object hit = hits.get(local);
                                float x = (float) field(hit, "x"), y = (float) field(hit, "y");
                                float w = (float) field(hit, "w"), h = (float) field(hit, "h");
                                assertTrue(x >= 0 && x + w < 458 && y >= 80 && y + h < 568,
                                        catalogue.page() + " / " + (first + local) + " crosses a frame");
                                screen.dispatchMouseClicked(ox + (x + w / 2F) * scale,
                                        oy + (600 - y - h / 2F) * scale, 0);
                                assertEquals(catalogue.at(first + local), screen.currentEntry());
                            }
                        }
                    }
                    screen.show(AlmanacEntries.Page.ZOMBIES);
                    screen.render();
                    Identifier entry = screen.currentEntry();
                    float scale = (float) field(screen, "scale");
                    float ox = (float) field(screen, "originX"), oy = (float) field(screen, "originY");
                    screen.onMouseScrolled(ox + 600 * scale, oy + 180 * scale, -1);
                    assertEquals(entry, screen.currentEntry(), "scrolling prose must not change the entry");
                    assertTrue((float) field(screen, "textScroll") > 0);
                    screen.show(AlmanacEntries.Page.PLANTS);
                    screen.render();
                    capture(size);
                    client.setProfile(0, List.of(), false);
                    screen.render();
                    assertEquals(org.lwjgl.opengl.GL11.GL_NO_ERROR, org.lwjgl.opengl.GL11.glGetError());
                    client.setProfile(0, List.of(), true);
                    screen.onRemoved();
                }
            } finally {
                client.fonts().close();
                client.textures().close();
            }
        } finally {
            com.pvzce.client.renderer.vertex.Tesselator.INSTANCE.close();
            RenderSystem.shader().close();
            glfwDestroyWindow(nativeWindow);
            glfwTerminate();
        }
    }

    private static void clickNative(AlmanacScreen screen, float x, float y) throws Exception {
        float scale = (float) field(screen, "scale");
        screen.dispatchMouseClicked((float) field(screen, "originX") + x * scale,
                (float) field(screen, "originY") + (600 - y) * scale, 0);
    }

    private static void capture(int[] size) throws Exception {
        String output = System.getProperty("pvzce.almanacUiCapture");
        if (output != null && size[0] == 1920) {
            var path = java.nio.file.Path.of(output);
            java.nio.file.Files.createDirectories(path.getParent());
            assertTrue(com.pvzce.client.FramebufferCapture.writePng(path.toString(), size[0], size[1]));
        }
    }

    private static Object invoke(AlmanacScreen screen, String name, Identifier id) throws Exception {
        var method = AlmanacScreen.class.getDeclaredMethod(name, Identifier.class);
        method.setAccessible(true);
        return method.invoke(screen, id);
    }

    private static List<?> stats(AlmanacScreen screen, AlmanacEntries.Page page, Identifier id) throws Exception {
        var method = AlmanacScreen.class.getDeclaredMethod("statLines", AlmanacEntries.Page.class, Identifier.class);
        method.setAccessible(true);
        return (List<?>) method.invoke(screen, page, id);
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
}
