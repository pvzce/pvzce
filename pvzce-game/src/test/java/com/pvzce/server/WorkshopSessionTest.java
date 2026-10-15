package com.pvzce.server;
import com.google.gson.*;
import com.pvzce.common.workshop.*;
import com.pvzce.common.network.packet.*;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.api.util.Identifier;
import com.pvzce.testutil.ServerHarness;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkshopSessionTest {
    @TempDir Path directory;
    @Test void temporarySessionRestoresContentAndNeverChangesTheRealProfile()throws Exception{
        try(ServerHarness harness=ServerHarness.createWithWorld(directory,"real",false)){
            Path profile=directory.resolve("saves/real/profile.dat");byte[] before=Files.readAllBytes(profile);
            Workspace source=Workspace.create(directory,"临时","testing");
            source.write("data/testing/plants/producer.json",JsonParser.parseString("{\"health\":800,\"capabilities\":[{\"type\":\"pvzce:producer\",\"resource\":\"pvzce:sun\",\"every\":10}]}"));
            harness.clear();harness.send(new WorkshopSessionC2S("testing"));
            assertTrue(harness.awaitPacket(WorkshopSessionS2C.class,5000).active());
            assertNotNull(BuiltInRegistries.PLANTS.get(Identifier.parse("testing:producer")));
            assertTrue(harness.server().worlds().profileFor("real").unlocksEverything());
            harness.requestLevel("pvzce:yard/adventure/1_1","real",true);
            harness.awaitPacket(LevelInitS2C.class,5000);
            harness.clear();harness.send(new WorkshopSessionC2S(""));
            assertFalse(harness.awaitPacket(WorkshopSessionS2C.class,5000).active());
            assertNull(BuiltInRegistries.PLANTS.get(Identifier.parse("testing:producer")));
            assertArrayEquals(before,Files.readAllBytes(profile));
            try(var worlds=Files.list(directory.resolve("saves"))){assertEquals(java.util.List.of("real"),worlds.map(p->p.getFileName().toString()).sorted().toList());}
        }
    }
}
