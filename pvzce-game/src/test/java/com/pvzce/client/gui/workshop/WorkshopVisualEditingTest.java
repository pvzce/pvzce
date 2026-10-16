package com.pvzce.client.gui.workshop;

import com.google.gson.*;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.*;
import com.pvzce.common.core.*;
import com.pvzce.common.tag.TestContent;
import com.pvzce.common.workshop.Workspace;
import com.pvzce.testutil.ClientHarness;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class WorkshopVisualEditingTest {
    private static void size(ClientHarness harness)throws Exception{
        var singleton=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        var window=(com.pvzce.client.PvzceWindow)((sun.misc.Unsafe)singleton.get(null)).allocateInstance(com.pvzce.client.PvzceWindow.class);
        for(String dimension:java.util.List.of("width","height")){
            var field=window.getClass().getDeclaredField(dimension);field.setAccessible(true);field.set(window,dimension.equals("width")?1920:1080);
        }
        var field=harness.client().getClass().getDeclaredField("window");field.setAccessible(true);field.set(harness.client(),window);
    }
    private static void click(Screen screen,String label){
        var button=screen.widgets().stream().filter(w->w instanceof Button b&&b.label().equals(label)).map(Button.class::cast).findFirst().orElseThrow();
        screen.dispatchMouseClicked(button.x()+button.width()/2D,button.y()+button.height()/2D,0);
    }
    @Test void draftCardsUseTranslatedNamesAndDraftArtWithoutPublishing()throws Exception{
        TestContent.loadBuiltInContentAndTags();
        try(var harness=ClientHarness.create("workshop-visual-names")){
            var workspace=Workspace.create(harness.gameDir(),"花园","visual");
            workspace.write("data/visual/plants/leaf.json",JsonParser.parseString("{\"health\":321,\"animation\":\"pvzce:plant/attacker/pea_shooter\"}"));
            workspace.write("assets/visual/lang/zh_cn.json",JsonParser.parseString("{\"plant.visual.leaf\":\"测试豌豆\"}"));
            try(var art=new WorkshopPresentation(harness.client(),workspace)){
                assertEquals("测试豌豆",art.item("data/visual/plants/leaf.json","").name());
                assertEquals("测试豌豆",art.item("visual:leaf","plants").name());
                assertEquals(321,art.catalog().get(PvzceRegistries.PLANTS).get(Identifier.parse("visual:leaf")).health());
                art.draft("data/visual/plants/leaf.json",JsonParser.parseString("{\"health\":450,\"animation\":\"preview:changed\"}").getAsJsonObject());
                assertEquals(450,art.catalog().get(PvzceRegistries.PLANTS).get(Identifier.parse("visual:leaf")).health());
                assertEquals("preview:changed",EntityArt.bindings(Identifier.parse("visual:leaf"),"plant",art.catalog()).animation().orElseThrow().toString());
                assertNull(BuiltInRegistries.PLANTS.get(Identifier.parse("visual:leaf")));
            }
        }
    }
    @Test void inlineAnimationFieldsNavigateToTheClipAndFrameDurationsFollowReordering()throws Exception{
        try(var harness=ClientHarness.create("workshop-visual-frames")){
            size(harness);var workspace=Workspace.create(harness.gameDir(),"花园","visual");
            String path="assets/visual/animations/leaf.json";
            workspace.write(path,JsonParser.parseString("""
                {"type":"flipbook","custom":true,"animations":{"idle":{"frames":["pvzce:textures/resource/sun","pvzce:textures/resource/coin_gold"],"delays":[0.1,0.7],"loop":true}}}
                """));
            var screen=new WorkshopJsonScreen(harness.client(),workspace,path);harness.client().setScreenReplacing(screen);screen.initIfNeeded();
            click(screen,"编辑（2 项）");
            assertEquals("frames",screen.currentField());
            var frames=screen.draft().getAsJsonObject("animations").getAsJsonObject("idle").getAsJsonArray("frames");
            screen.moveEntry(frames,1,-1);
            assertEquals("pvzce:textures/resource/coin_gold",frames.get(0).getAsString());
            assertEquals("0.7 秒",screen.frameDelay(frames,0));
            screen.removeEntry(frames,0);
            click(screen,WorkshopUi.text("save","保存草稿"));
            var saved=JsonParser.parseString(Files.readString(workspace.file(path))).getAsJsonObject();
            assertTrue(saved.get("custom").getAsBoolean());
            assertEquals(0.1,saved.getAsJsonObject("animations").getAsJsonObject("idle").getAsJsonArray("delays").get(0).getAsDouble(),.00001);
        }
    }
    @Test void anInvalidNumberCannotSilentlySaveAnOlderValueAndUnknownFieldsSurvive()throws Exception{
        try(var harness=ClientHarness.create("workshop-visual-number")){
            size(harness);var workspace=Workspace.create(harness.gameDir(),"花园","visual");
            String path="data/visual/plants/leaf.json";
            workspace.write(path,JsonParser.parseString("{\"health\":300,\"custom\":{\"future\":true}}"));
            var screen=new WorkshopJsonScreen(harness.client(),workspace,path);harness.client().setScreenReplacing(screen);screen.initIfNeeded();
            var input=screen.widgets().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
            input.setValue("oops");click(screen,WorkshopUi.text("save_back","保存并返回"));
            assertSame(screen,harness.client().currentScreen());
            input.setValue("450");click(screen,WorkshopUi.text("save","保存草稿"));
            var saved=JsonParser.parseString(Files.readString(workspace.file(path))).getAsJsonObject();
            assertEquals(450,saved.get("health").getAsInt());assertTrue(saved.getAsJsonObject("custom").get("future").getAsBoolean());
        }
    }
    @Test void deletingAnInvalidAbilityClearsItsErrorAndSmallFrameListsStayOnScreen()throws Exception{
        try(var harness=ClientHarness.create("workshop-visual-removal")){
            size(harness);var workspace=Workspace.create(harness.gameDir(),"花园","visual");
            String path="data/visual/plants/leaf.json";
            workspace.write(path,JsonParser.parseString("{\"health\":300,\"capabilities\":[{\"type\":\"pvzce:shooter\",\"interval\":90,\"shots\":[]}]}"));
            var screen=new WorkshopJsonScreen(harness.client(),workspace,path);harness.client().setScreenReplacing(screen);screen.initIfNeeded();
            var abilities=screen.draft().getAsJsonArray("capabilities");var ability=abilities.get(0).getAsJsonObject();
            screen.setText(ability,"interval",ability.get("interval"),"invalid");
            screen.removeEntry(abilities,0);click(screen,WorkshopUi.text("save","保存草稿"));
            assertTrue(JsonParser.parseString(Files.readString(workspace.file(path))).getAsJsonObject().getAsJsonArray("capabilities").isEmpty());
            String animation="assets/visual/animations/leaf.json";
            workspace.write(animation,JsonParser.parseString("{\"type\":\"flipbook\",\"animations\":{\"idle\":{\"frames\":[\"pvzce:textures/resource/sun\"],\"delay\":0.1}}}"));
            var frames=new WorkshopJsonScreen(harness.client(),workspace,animation);harness.client().setScreenReplacing(frames);frames.initIfNeeded();
            // The default 427x240 GUI has less vertical space than a full frame card.
            var field=harness.client().getClass().getDeclaredField("window");field.setAccessible(true);var window=field.get(harness.client());
            for(String dimension:java.util.List.of("width","height")){var d=window.getClass().getDeclaredField(dimension);d.setAccessible(true);d.set(window,dimension.equals("width")?854:480);}
            frames.onResize();frames.enterClip("idle");
            for(var widget:frames.widgets()){
                assertTrue(widget.x()>=0&&widget.y()>=0);
                assertTrue(widget.x()+widget.width()<=harness.client().guiWidth());
                assertTrue(widget.y()+widget.height()<=harness.client().guiHeight());
            }
        }
    }

}
