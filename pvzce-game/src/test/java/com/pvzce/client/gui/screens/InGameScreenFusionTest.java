package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.PvzceWindow;
import com.pvzce.client.mechanic.ClientMechanics;
import com.pvzce.client.mechanic.FusionLayout;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.level.FusionState;
import com.pvzce.common.network.packet.FusionActionC2S;
import com.pvzce.common.network.packet.LevelPayload;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.network.packet.SlotInfo;
import com.pvzce.common.network.packet.UseToolC2S;
import com.pvzce.common.tag.TestContent;
import com.pvzce.testutil.ClientHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InGameScreenFusionTest {
    @BeforeAll static void load() throws Exception { TestContent.loadBuiltInContentAndTags(); }

    private static void field(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true); field.set(target, value);
    }

    private static InGameScreen screen(ClientHarness harness) throws Exception {
        // Only framebuffer dimensions are needed by screen picking. No native window or GL
        // context is created; native input and rendering are checked by the single smoke run.
        Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        PvzceWindow window = (PvzceWindow) ((sun.misc.Unsafe) singleton.get(null)).allocateInstance(PvzceWindow.class);
        field(window, "width", 1920); field(window, "height", 1080);
        PvzceClient client = harness.client(); field(client, "window", window);
        client.level().init("pvzce:yard/minigame/fusion_1", 9, 5,
                List.of(new SlotInfo(0, "pvzce:shovel", "tool", 0, 0, true)), List.of(), List.of(), 0,
                List.of(), List.of(), PvzceIds.PLANT_TEAM.toString(), "植物方",
                List.of(new LevelPayload.MechanicPayload(PvzceIds.MECHANIC_FUSION, "{\"tutorial\":true}")));
        ClientMechanics.applySync(client.level(), MechanicSyncS2C.of(PvzceIds.MECHANIC_FUSION, FusionState.CODEC,
                new FusionState(List.of(), List.of(), List.of(), 0, 25, "", true)));
        InGameScreen screen = new InGameScreen(client); client.setScreenReplacing(screen); return screen;
    }

    @Test void clickingTheShovelUsesTheNormalToolPacketOnceAndReturnsItToTheBar() throws Exception {
        try (ClientHarness harness = ClientHarness.create("pvzce-fusion-click")) {
            InGameScreen screen = screen(harness);
            PvzceClient client = harness.client();
            var l = FusionLayout.of(client.guiWidth(), client.guiHeight()); var shovel = l.shovel();
            screen.dispatchMouseClicked(l.left() + (shovel.x() + shovel.width() / 2) * l.scale(),
                    (shovel.y() + shovel.height() / 2) * l.scale(), 0);
            assertEquals(0, screen.selectedCardIndex());
            double x = client.camera().screenX(2.5F) / client.guiScale();
            double y = client.camera().cellScreenY(2, 0) / client.guiScale();
            screen.dispatchMouseClicked(x, y, 0);
            assertEquals(-1, screen.selectedCardIndex());
            screen.dispatchMouseClicked(x, y, 0);
            assertEquals(List.of(new UseToolC2S(0, 2, 0)), harness.sentPackets().stream()
                    .filter(p -> p instanceof UseToolC2S).toList());
            screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_S);
            assertEquals(0, screen.selectedCardIndex());
            screen.dispatchMouseClicked(x, y, 1);
            assertEquals(-1, screen.selectedCardIndex());
        }
    }

    @Test void clickingAnAbilityOnTheLawnOnlyRequestsThatDrop() throws Exception {
        try (ClientHarness harness = ClientHarness.create("pvzce-fusion-drop")) {
            InGameScreen screen = screen(harness); PvzceClient client = harness.client();
            FusionState state = new FusionState(List.of(), List.of(),
                    List.of(new FusionState.Drop(7, "pvzce:shooter", 2.5F, 0.5F)), 1, 25, "", true);
            ClientMechanics.applySync(client.level(), MechanicSyncS2C.of(PvzceIds.MECHANIC_FUSION, FusionState.CODEC, state));
            screen.dispatchMouseClicked(client.camera().screenX(2.5F) / client.guiScale(),
                    client.camera().screenY(0.95F) / client.guiScale(), 0);
            assertEquals(List.of(new FusionActionC2S("collect", "", 7)), harness.sentPackets().stream()
                    .filter(p -> p instanceof FusionActionC2S).toList());
        }
    }
}
