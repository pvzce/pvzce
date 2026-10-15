package com.pvzce.client.gui.workshop;
import com.google.gson.JsonParser;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.animation.AnimationManager;
import com.pvzce.common.core.PvzceRegistries;
import com.pvzce.common.resource.PvzceResourceManager;
import com.pvzce.common.tag.TestContent;
import com.pvzce.common.workshop.*;
import com.pvzce.testutil.ClientHarness;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WorkshopAnimationBindingTest {
    @Test void aDraftMapsItsAttackToAnImportedClipWithoutRegisteringTheDraft()throws Exception{
        TestContent.loadBuiltInContentAndTags();
        try(var harness=ClientHarness.create("workshop-animation")){
            Workspace workspace=Workspace.create(harness.gameDir(),"Animation","anim");
            workspace.write("data/anim/plants/leaf.json",JsonParser.parseString("""
                {"animation":"anim:leaf","animation_clips":{"shoot":"attack"}}
                """));
            workspace.write("assets/anim/animations/leaf.json",JsonParser.parseString("""
                {"type":"flipbook","animations":{"attack":{"frames":["pvzce:textures/resource/sun"],"loop":false}}}
                """));
            workspace.write("data/anim/plants/static_leaf.json",JsonParser.parseString("{\"texture\":\"anim:textures/leaf\"}"));
            var catalog=WorkshopContent.catalog(workspace);
            assertEquals("anim:textures/leaf",com.pvzce.common.core.EntityArt.sprite(Identifier.parse("anim:static_leaf"),"plant",catalog).toString());
            try(var resources=new PvzceResourceManager(getClass().getClassLoader())){
                resources.workspaceOverlay(workspace.root());resources.init(harness.gameDir());
                var manager=new AnimationManager(harness.client(),harness.client().level(),resources);
                ClientEntity entity=new ClientEntity(-1,"plant","anim:leaf",0,0,100,1,"shoot",0,"");
                entity.previewCatalog(catalog);manager.play(entity,"shoot");
                assertNotNull(manager.playback(entity));assertEquals("attack",manager.playback(entity).activeName());
                assertNull(com.pvzce.common.core.BuiltInRegistries.PLANTS.get(Identifier.parse("anim:leaf")));
                assertNotNull(catalog.get(PvzceRegistries.PLANTS).get(Identifier.parse("anim:leaf")));
            }
        }
    }
}
