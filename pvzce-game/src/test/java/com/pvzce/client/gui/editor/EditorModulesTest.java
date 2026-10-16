package com.pvzce.client.gui.editor;

import com.google.gson.*;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.gui.components.*;
import com.pvzce.client.gui.screens.EditorScreen;
import com.pvzce.common.tag.TestContent;
import com.pvzce.common.workshop.Workspace;
import com.pvzce.testutil.ClientHarness;
import org.junit.jupiter.api.*;
import java.nio.file.Files;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EditorModulesTest {
    @BeforeAll static void content() throws Exception {TestContent.loadBuiltInContentAndTags();}
    private static void window(com.pvzce.client.PvzceClient client) throws Exception {
        var singleton=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        var window=(com.pvzce.client.PvzceWindow)((sun.misc.Unsafe)singleton.get(null)).allocateInstance(com.pvzce.client.PvzceWindow.class);
        for(String dimension:List.of("width","height")){
            var field=window.getClass().getDeclaredField(dimension);field.setAccessible(true);field.set(window,dimension.equals("width")?1920:1080);
        }
        var field=client.getClass().getDeclaredField("window");field.setAccessible(true);field.set(client,window);
    }
    private static void click(EditorScreen screen,Button button){screen.dispatchMouseClicked(button.x()+button.width()/2D,button.y()+button.height()/2D,0);}
    private static Button named(EditorScreen screen,String label){return screen.widgets().stream().filter(w->w instanceof Button b&&b.label().equals(label)).map(Button.class::cast).findFirst().orElseThrow();}
    @Test void unusedPagesDoNotInjectDefaultsAndHiddenDataSurvivesSaving() throws Exception {
        try(var harness=ClientHarness.create("editor-modules")){
            window(harness.client());
            Workspace workspace=Workspace.create(harness.gameDir(),"关卡","test");
            String path="data/test/levels/blank.json";workspace.write(path,new JsonObject());
            var screen=new EditorScreen(harness.client(),workspace,Identifier.parse("test:blank"),workspace.file(path),new JsonObject());
            harness.client().setScreenReplacing(screen);screen.initIfNeeded();
            assertEquals(List.of("overview","terrain","plant","zombie","info"),screen.editorVisiblePages());
            // The actual manager dialog, rather than a direct show-page hook, enables dialogue.
            click(screen,named(screen,"添加机制 / 编辑功能…"));
            var dialog=screen.dialogs().getFirst();
            var list=(AbstractSelectionList<?>)dialog.children().stream().filter(AbstractSelectionList.class::isInstance).findFirst().orElseThrow();
            int index=screen.editorModules().additions(new EditorContext(screen),screen.editorAllPages()).stream().map(EditorModules.Addition::key).toList().indexOf("dialogue");
            list.select(index);
            var add=dialog.children().stream().filter(w->w instanceof Button b&&b.label().equals("添加")).map(Button.class::cast).findFirst().orElseThrow();
            click(screen,add);assertTrue(screen.editorVisiblePages().contains("dialogue"));
            screen.setEditorModuleShown("dialogue",false);
            click(screen,named(screen,"保存"));
            var saved=JsonParser.parseString(Files.readString(workspace.file(path))).getAsJsonObject();
            for(String unused:List.of("music","waves","slots","max_seed_slots","rules"))assertFalse(saved.has(unused),unused);
            JsonObject existing=JsonParser.parseString("{\"music\":{\"prepare\":\"test:quiet\"},\"dialogue\":{\"custom\":42}}").getAsJsonObject();
            screen=new EditorScreen(harness.client(),workspace,Identifier.parse("test:blank"),workspace.file(path),existing);
            harness.client().setScreenReplacing(screen);screen.initIfNeeded();
            assertTrue(screen.editorVisiblePages().containsAll(List.of("music","dialogue")));
            screen.setEditorModuleShown("dialogue",false);click(screen,named(screen,"保存"));
            saved=JsonParser.parseString(Files.readString(workspace.file(path))).getAsJsonObject();
            assertEquals(42,saved.getAsJsonObject("dialogue").get("custom").getAsInt());
        }
    }
    @Test void addingConveyorBuildsItsPageAndHonoursItsCardSource() throws Exception {
        try(var harness=ClientHarness.create("editor-conveyor")){
            window(harness.client());Workspace workspace=Workspace.create(harness.gameDir(),"关卡","test");
            var screen=new EditorScreen(harness.client(),workspace,Identifier.parse("test:blank"),workspace.file("data/test/levels/blank.json"),new JsonObject());
            harness.client().setScreenReplacing(screen);screen.initIfNeeded();
            var context=new EditorContext(screen);
            screen.addEditorModule(screen.editorModules().additions(context,screen.editorAllPages()).stream().filter(a->a.key().equals("cards")).findFirst().orElseThrow());
            assertTrue(screen.editorVisiblePages().contains("cards"));
            var conveyor=screen.editorModules().additions(context,screen.editorAllPages()).stream().filter(a->a.key().equals("add_mechanic:pvzce:conveyor")).findFirst().orElseThrow();
            conveyor.mechanic().addProperty("all_plants",true);
            conveyor.mechanic().addProperty("custom",42);
            screen.addEditorModule(conveyor);
            assertTrue(screen.editorVisiblePages().contains("mechanic.conveyor"));
            assertFalse(screen.editorVisiblePages().contains("cards"));
            var block=screen.draft().json().getAsJsonArray("mechanics").deepCopy();
            screen.openEditorModule("mechanic.conveyor");
            screen.setEditorModuleShown("mechanic.conveyor",false);
            assertEquals(block,screen.draft().json().getAsJsonArray("mechanics"));
            // A codec's omitted defaults must not become the numeric widget's minimum.
            var typed=com.pvzce.common.level.mechanic.LevelMechanics.codec().parse(com.mojang.serialization.JsonOps.INSTANCE,block.get(0)).getOrThrow();
            assertEquals(300,((com.pvzce.api.content.LevelBelt)typed.value()).intervalTicks());
            screen.addEditorModule(new EditorModules.Addition("add_mechanic:pvzce:fog","迷雾",JsonParser.parseString("{\"type\":\"pvzce:fog\",\"max_alpha\":0.65}").getAsJsonObject()));
            screen.openEditorModule("mechanic.fog");
            screen.setEditorModuleShown("mechanic.fog",false);
            assertEquals(.65F,screen.draft().json().getAsJsonArray("mechanics").get(1).getAsJsonObject().get("max_alpha").getAsFloat());
        }
    }
}
