package com.pvzce.client.sound;
import com.pvzce.common.resource.PvzceResourceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class WorkshopSoundEventTest {
    @TempDir Path directory;
    @Test void namespacesSurviveManifestAndFileResolution()throws Exception{
        Path assets=directory.resolve("datapacks/custom/assets/garden");Files.createDirectories(assets);
        Files.writeString(assets.resolve("sounds.json"),"""
            {"bloom":{"sounds":["garden:grow",{"name":"other:bell","weight":2}]}}
            """);
        try(var resources=new PvzceResourceManager(getClass().getClassLoader())){
            resources.init(directory);
            var event=SoundEngine.parseDefinition(resources,"garden:bloom");
            assertEquals("garden:grow",event.variants().get(0).file());
            assertEquals("other:bell",event.variants().get(1).file());
            assertEquals("missing:bloom",SoundEngine.parseDefinition(resources,"missing:bloom").variants().get(0).file());
        }
    }
}
