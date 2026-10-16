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
            source.write("data/testing/plants/aliased.json",JsonParser.parseString("{\"health\":300}"));
            source.write("data/testing/slots/custom_card.json",JsonParser.parseString("{\"content\":\"testing:aliased\",\"kind\":\"plant\",\"cost\":{\"resources\":{\"pvzce:sun\":123},\"cooldown\":77}}"));
            harness.clear();harness.send(new WorkshopSessionC2S("testing"));
            assertTrue(harness.awaitPacket(WorkshopSessionS2C.class,5000).active());
            assertNotNull(BuiltInRegistries.PLANTS.get(Identifier.parse("testing:producer")));
            assertTrue(harness.server().worlds().profileFor("real").unlocksEverything());
            harness.clear();
            var info=harness.levelList("real").levels().stream().filter(level->level.id().equals("pvzce:yard/adventure/2_1")).findFirst().orElseThrow();
            assertTrue(info.seedPool().stream().anyMatch(card->card.slotId().equals("testing:producer")&&card.content().equals("testing:producer")));
            var alias=info.seedPool().stream().filter(card->card.content().equals("testing:aliased")).toList();
            assertEquals(1,alias.size());assertEquals("testing:custom_card",alias.get(0).slotId());assertEquals(123,alias.get(0).costSun());
            var def=BuiltInRegistries.LEVELS.get(Identifier.parse(info.id()));
            assertFalse(com.pvzce.common.core.SeedOptions.cardPool(def,PlayerProfile.starter()::ownsCard).contains(Identifier.parse("testing:producer")));
            harness.clear();harness.send(new PlayLevelC2S(info.id(),"real",true,java.util.List.of("testing:producer","testing:custom_card")));
            var init=harness.awaitPacket(LevelInitS2C.class,5000);
            assertTrue(init.slots().stream().anyMatch(card->card.defId().equals("testing:producer")));
            assertTrue(init.slots().stream().anyMatch(card->card.defId().equals("testing:aliased")&&card.costSun()==123));
            harness.clear();harness.send(new WorkshopSessionC2S(""));
            assertFalse(harness.awaitPacket(WorkshopSessionS2C.class,5000).active());
            assertNull(BuiltInRegistries.PLANTS.get(Identifier.parse("testing:producer")));
            assertArrayEquals(before,Files.readAllBytes(profile));
            try(var worlds=Files.list(directory.resolve("saves"))){assertEquals(java.util.List.of("real"),worlds.map(p->p.getFileName().toString()).sorted().toList());}
        }
    }
}
