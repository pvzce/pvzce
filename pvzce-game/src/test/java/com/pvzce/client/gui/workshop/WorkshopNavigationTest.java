package com.pvzce.client.gui.workshop;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.*;
import com.pvzce.common.workshop.Workspace;
import com.pvzce.testutil.ClientHarness;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WorkshopNavigationTest {
    private static void click(Screen screen,Button button){screen.dispatchMouseClicked(button.x()+button.width()/2D,button.y()+button.height()/2D,0);}
    private static Button named(Screen screen,String fallback){return screen.widgets().stream().filter(w->w instanceof Button b&&b.label().equals(fallback)).map(w->(Button)w).findFirst().orElseThrow();}
    private static void submit(Screen screen,String value){Dialog dialog=screen.dialogs().get(0);dialog.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow().setValue(value);Button button=dialog.children().stream().filter(Button.class::isInstance).map(Button.class::cast).findFirst().orElseThrow();click(screen,button);}
    @Test void texturePreviewsKeepTheTextureDirectory(){
        assertEquals("garden:textures/leaf",WorkshopPreviewScreen.assetId("assets/garden/textures/leaf.png").toString());
        assertEquals("garden:grow",WorkshopPreviewScreen.assetId("assets/garden/sounds/grow.ogg").toString());
    }
    @Test void previewUsesABottomOriginViewportInsideSmallAndLargeWindows(){
        for(int[] size:java.util.List.of(new int[]{427,240},new int[]{960,540},new int[]{640,360})){
            int[] area=WorkshopPreviewScreen.previewArea(size[0],size[1]);
            assertTrue(area[0]>=0&&area[1]>=0);
            assertTrue(area[0]+area[2]<=size[0]);assertTrue(area[1]+area[3]<=size[1]);
            assertEquals(area[2],area[3]);
        }
    }
    @Test void creationUsesTheActualWidgetAndDialogDispatchAndLeavesPacksUnpublished()throws Exception{
        try(var harness=ClientHarness.create("workshop-navigation")){
            var client=harness.client();
            var singleton=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");singleton.setAccessible(true);
            var window=(com.pvzce.client.PvzceWindow)((sun.misc.Unsafe)singleton.get(null)).allocateInstance(com.pvzce.client.PvzceWindow.class);
            for(String dimension:java.util.List.of("width","height")){
                var field=window.getClass().getDeclaredField(dimension);field.setAccessible(true);field.set(window,dimension.equals("width")?1920:1080);
            }
            var windowField=client.getClass().getDeclaredField("window");windowField.setAccessible(true);windowField.set(client,window);
            WorkshopScreen screen=new WorkshopScreen(client);client.setScreenReplacing(screen);screen.initIfNeeded();
            click(screen,named(screen,WorkshopUi.text("create","创建工作区")));
            submit(screen,"测试工作区");submit(screen,"garden");
            assertInstanceOf(WorkshopScreen.class,client.currentScreen());
            assertEquals("garden",((WorkshopScreen)client.currentScreen()).workspace().namespace());
            assertEquals(1,Workspace.list(harness.gameDir()).size());
            assertFalse(java.nio.file.Files.exists(harness.gameDir().resolve("datapacks/workshop-garden")));
            client.popScreen();assertSame(screen,client.currentScreen());
        }
    }
}
