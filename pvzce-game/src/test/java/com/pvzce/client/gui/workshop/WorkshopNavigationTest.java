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
    private static void size(ClientHarness harness,int width,int height)throws Exception{
        harness.client().config().setGuiScale(2);
        var singleton=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        var window=(com.pvzce.client.PvzceWindow)((sun.misc.Unsafe)singleton.get(null)).allocateInstance(com.pvzce.client.PvzceWindow.class);
        for(String dimension:java.util.List.of("width","height")){
            var field=window.getClass().getDeclaredField(dimension);field.setAccessible(true);field.set(window,dimension.equals("width")?width:height);
        }
        var field=harness.client().getClass().getDeclaredField("window");field.setAccessible(true);field.set(harness.client(),window);
    }
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
            size(harness,1920,1080);
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
    @Test void creationDialogTakesKeyboardAndEscapeCancelsOnlyThePrompt()throws Exception{
        try(var harness=ClientHarness.create("workshop-keyboard")){
            size(harness,1920,1080);
            var screen=new WorkshopScreen(harness.client());harness.client().setScreenReplacing(screen);screen.initIfNeeded();
            click(screen,named(screen,WorkshopUi.text("create","创建工作区")));
            screen.charTyped('a');
            assertEquals("a",screen.focusManager().focused().value());
            screen.dispatchEscape();
            assertSame(screen,harness.client().currentScreen());assertTrue(screen.dialogs().isEmpty());assertFalse(screen.hasTextInputFocused());
            click(screen,named(screen,WorkshopUi.text("create","创建工作区")));
            screen.charTyped('a');screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);
            assertTrue(screen.hasTextInputFocused());
            screen.charTyped('b');screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);
            assertEquals("b",((WorkshopScreen)harness.client().currentScreen()).workspace().namespace());
        }
    }
    @Test void pickerKeepsItsSearchInputAndOnlyEnterSelectsASingleMatch()throws Exception{
        try(var harness=ClientHarness.create("workshop-picker")){
            size(harness,1920,1080);
            harness.client().setScreenReplacing(new WorkshopScreen(harness.client()));
            var entries=new java.util.ArrayList<String>(java.util.List.of("sun","sunflower"));
            for(int i=0;i<10;i++)entries.add("item_"+i);
            var chosen=new java.util.ArrayList<String>();
            var picker=new WorkshopListScreen(harness.client(),"选择内容",entries,chosen::add);
            harness.client().openScreen(picker);picker.initIfNeeded();
            assertFalse(named(picker,"上一页").isActive());assertTrue(named(picker,"下一页").isActive());
            picker.mouseScrolled(40,680,-1);
            assertTrue(named(picker,"上一页").isActive());assertFalse(named(picker,"下一页").isActive());
            var input=picker.focusManager().focused();
            input.setValue("missing");picker.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);
            assertSame(picker,harness.client().currentScreen());assertTrue(chosen.isEmpty());
            click(picker,named(picker,"清空"));assertSame(input,picker.focusManager().focused());
            picker.charTyped('s');picker.charTyped('u');picker.charTyped('n');
            assertSame(input,picker.focusManager().focused());assertEquals("sun",input.value());
            picker.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);assertTrue(chosen.isEmpty());
            input.setValue("sunflower");picker.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);
            assertEquals(java.util.List.of("sunflower"),chosen);
        }
    }
    @Test void browserSearchesDraftNamesAndEscapeSavesThroughTheRealClosePolicy()throws Exception{
        try(var harness=ClientHarness.create("workshop-search-save")){
            size(harness,1920,1080);
            var workspace=Workspace.create(harness.gameDir(),"花园","garden");
            String file="data/garden/plants/leaf.json";
            workspace.write(file,com.google.gson.JsonParser.parseString("{\"health\":300,\"custom\":{\"future\":true}}"));
            workspace.write("assets/garden/lang/zh_cn.json",com.google.gson.JsonParser.parseString("{\"plant.garden.leaf\":\"测试豌豆\"}"));
            var browser=new WorkshopScreen(harness.client(),workspace);harness.client().setScreenReplacing(browser);browser.initIfNeeded();
            var search=browser.widgets().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
            search.setValue("测试豌豆");click(browser,named(browser,"测试豌豆"));
            var editor=(WorkshopJsonScreen)harness.client().currentScreen();editor.initIfNeeded();
            var health=editor.widgets().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
            health.setValue("bad");editor.dispatchEscape();assertSame(editor,harness.client().currentScreen());
            health.setValue("450");editor.enter("custom");editor.dispatchEscape();
            assertSame(editor,harness.client().currentScreen());assertTrue(editor.atRoot());
            editor.dispatchEscape();assertSame(browser,harness.client().currentScreen());
            var saved=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(workspace.file(file))).getAsJsonObject();
            assertEquals(450,saved.get("health").getAsInt());assertTrue(saved.getAsJsonObject("custom").get("future").getAsBoolean());
            assertEquals("测试豌豆",search.value());
        }
    }
    @Test void returningFromAnAbilityRestoresItsListPageWithoutOverlappingTheFooter()throws Exception{
        try(var harness=ClientHarness.create("workshop-property-pages")){
            size(harness,854,480);assertEquals(427,harness.client().guiWidth());assertEquals(240,harness.client().guiHeight());
            var workspace=Workspace.create(harness.gameDir(),"花园","garden");
            var json=com.google.gson.JsonParser.parseString("{\"capabilities\":[{\"type\":\"pvzce:shooter\",\"interval\":90},{\"type\":\"pvzce:shooter\",\"interval\":120}]}");
            String file="data/garden/plants/leaf.json";workspace.write(file,json);
            var editor=new WorkshopJsonScreen(harness.client(),workspace,file);harness.client().setScreenReplacing(editor);editor.initIfNeeded();
            click(editor,named(editor,"能力"));click(editor,named(editor,"→"));
            var card=editor.widgets().stream().filter(w->w instanceof Button b&&b.label().contains("120")).findFirst().orElseThrow();
            editor.dispatchMouseClicked(card.x()+card.width()/2D,card.y()+card.height()/2D,0);
            assertEquals("1",editor.currentField());editor.dispatchEscape();assertTrue(editor.atRoot());
            assertTrue(editor.widgets().stream().anyMatch(w->w instanceof Button b&&b.label().contains("120")));
            assertFalse(named(editor,"→").isActive());assertTrue(named(editor,"←").isActive());
            for(var widget:editor.widgets()){
                assertTrue(widget.y()>=0&&widget.y()+widget.height()<=harness.client().guiHeight());
                if(widget.y()>58)assertTrue(widget.y()>=72,"content must clear the footer");
            }
        }
    }

}
