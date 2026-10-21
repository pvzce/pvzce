package com.pvzce.client;

import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.ScreenStack;
import com.pvzce.client.gui.screens.InGameScreen;
import com.pvzce.testutil.ClientHarness;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LightingPreviewTest {
    private static void field(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test void previewIsSettingsOnlyAndF1IsGameOnlyWithoutNavigationOrPackets() throws Exception {
        try (var harness = ClientHarness.create("lighting-preview")) {
            var client = harness.client();
            var singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            var window = (PvzceWindow)((sun.misc.Unsafe)singleton.get(null)).allocateInstance(PvzceWindow.class);
            field(window, "width", 1280);
            field(window, "height", 720);
            field(client, "window", window);
            client.config().setGuiScale(2);
            assertFalse(client.previewAvailable());
            client.level().init("pvzce:test", 9, 5, List.of(), List.of(), List.of(), 0,
                    List.of(), List.of(), "pvzce:plant_team", "植物方", List.of());
            var stackField = PvzceClient.class.getDeclaredField("screens");
            stackField.setAccessible(true);
            var stack = (ScreenStack)stackField.get(client);
            var game = new InGameScreen(client);
            stack.push(game);
            assertFalse(client.previewAvailable(), "The game must not display the settings preview button");
            assertEquals(com.pvzce.client.input.KeyBindings.Action.HIDE_UI,
                    client.keyBindings().actionFor(org.lwjgl.glfw.GLFW.GLFW_KEY_F1));
            var action = PvzceClient.class.getDeclaredMethod("handleBoundAction", com.pvzce.client.input.KeyBindings.Action.class);
            action.setAccessible(true);
            assertEquals(true, action.invoke(client, com.pvzce.client.input.KeyBindings.Action.HIDE_UI));
            assertTrue(client.gameUiHidden());
            assertTrue(client.sceneOnly());
            client.deliverGuiClick(100, 100, 0);
            assertTrue(harness.sentPackets().isEmpty());
            assertEquals(true, action.invoke(client, com.pvzce.client.input.KeyBindings.Action.HIDE_UI));
            assertFalse(client.gameUiHidden());
            int[] clicks = {0};
            var settings = new Screen(client) {
                @Override public void render() {}
                @Override protected void onMouseClicked(double x, double y, int button) { clicks[0]++; }
            };
            stack.push(settings);
            assertFalse(client.previewAvailable(), "Settings hub must not display the lighting preview button");
            assertFalse(client.toggleGameUi(), "F1 is scoped to the game screen");
            var lighting = new com.pvzce.client.gui.screens.LightingSettingsScreen(client);
            stack.push(lighting);
            assertTrue(client.previewAvailable());
            client.deliverGuiClick(client.guiWidth()-30, 18, 0);
            assertTrue(client.lightingPreview());
            client.deliverGuiClick(100, 100, 0);
            client.deliverGuiClick(100, 100, 1);
            assertEquals(0, clicks[0], "Hidden UI must not receive input");
            assertSame(lighting, client.currentScreen());
            client.deliverGuiClick(client.guiWidth()-30, 18, 0);
            assertFalse(client.lightingPreview());
            assertSame(lighting, client.currentScreen());
            lighting.requestClose();
            assertSame(settings, client.currentScreen());
            client.deliverGuiClick(100, 100, 0);
            assertEquals(1, clicks[0]);
            assertTrue(harness.sentPackets().isEmpty(), "Preview must not change simulation or pause state");
            client.level().reset();
            assertFalse(client.previewAvailable());
            assertFalse(client.lightingPreview());
        }
    }
}
