package com.pvzce.common.workshop;
import com.google.gson.*;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.*;
import com.pvzce.common.resource.*;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceTest {
    @TempDir Path directory;
    @BeforeAll static void load()throws Exception{TestContent.loadBuiltInContentAndTags();}
    @Test void draftDoesNotLoadAndBuildFailurePreservesPreviousOutput()throws Exception{
        Workspace workspace=Workspace.create(directory,"草稿","draft");
        assertThrows(IllegalArgumentException.class,()->workspace.file("data/draft/plants/../../../workspace.json"));
        String path="data/draft/plants/flower.json";
        workspace.writeBytes(PvzcePack.ICON_FILE,new byte[]{1,2,3});
        workspace.write(path,JsonParser.parseString("{\"health\":345}"));
        try(var resources=new PvzceResourceManager(getClass().getClassLoader())){
            resources.init(directory);
            assertEquals(1,resources.scanAvailable(directory).size());
            assertTrue(resources.getData(Identifier.parse("draft:plants/flower.json")).isEmpty());
            WorkshopContent.validate(workspace);
            assertNull(BuiltInRegistries.PLANTS.get(Identifier.parse("draft:flower")));
            Path output=workspace.build(directory,WorkshopContent::validate);
            assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(output.resolve(PvzcePack.ICON_FILE)));
            String previous=Files.readString(output.resolve(path));
            resources.reload();assertEquals(2,resources.scanAvailable(directory).size());
            PackSelection selection=PackSelection.load(directory);selection.setEnabled(workspace.packName(),false);assertTrue(selection.save());
            workspace.write(path,JsonParser.parseString("{\"health\":456}"));
            Files.delete(workspace.file(PvzcePack.ICON_FILE));
            workspace.build(directory,WorkshopContent::validate);
            assertFalse(Files.exists(output.resolve(PvzcePack.ICON_FILE)));
            assertFalse(PackSelection.load(directory).isEnabled(workspace.packName()));
            String latest=Files.readString(output.resolve(path));assertNotEquals(previous,latest);
            workspace.write(path,JsonParser.parseString("{\"capabilities\":[{\"type\":\"draft:missing\"}]}"));
            assertThrows(IllegalArgumentException.class,()->workspace.build(directory,WorkshopContent::validate));
            assertEquals(latest,Files.readString(output.resolve(path)));
        }
    }
    @Test void tagValidationUsesPrivateRegistriesAndRejectsCycles()throws Exception{
        Workspace workspace=Workspace.create(directory,"标签","tagdraft");
        workspace.write("data/tagdraft/tags/plant/a.json",JsonParser.parseString("{\"values\":[\"#tagdraft:b\"]}"));
        workspace.write("data/tagdraft/tags/plant/b.json",JsonParser.parseString("{\"values\":[\"#tagdraft:a\"]}"));
        assertThrows(IllegalArgumentException.class,()->WorkshopContent.validate(workspace));
        assertTrue(BuiltInRegistries.PLANTS.containsKey(Identifier.parse("pvzce:pea_shooter")));
    }
}
