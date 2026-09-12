package com.pvzce.common.core;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.nbt.NbtIo;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreSmokeTest {
    @Test
    void identifierParsesAndValidates() {
        Identifier id = Identifier.parse("pvzce:pea_shooter");
        assertEquals("pvzce", id.namespace());
        assertEquals("pea_shooter", id.path());
        assertEquals(Identifier.parse("pea_shooter"), Identifier.withDefaultNamespace("pea_shooter"));
        assertEquals("pvzce/textures/foo.png", Identifier.of("pvzce", "textures/foo.png").toPath());
    }

    @Test
    void staticRegistryRoundTrips() {
        BuiltInRegistries.bootstrap();
        PlantDef pea = BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace("pea_shooter"));
        assertNotNull(pea);
        assertEquals(90, pea.capability(com.pvzce.common.capability.plant.ShooterCapability.class)
                .orElseThrow().intervalTicks());
        assertEquals(Identifier.withDefaultNamespace("pea_shooter"), BuiltInRegistries.PLANTS.getKey(pea));
        assertEquals(pea, BuiltInRegistries.PLANTS.getById(BuiltInRegistries.PLANTS.getId(pea)));
    }

    @Test
    void nbtRoundTrips() throws Exception {
        CompoundTag root = new CompoundTag();
        root.putString("LevelId", "pvzce:demo_level");
        root.putInt("Tick", 123);
        ListTag list = new ListTag();
        CompoundTag plant = new CompoundTag();
        plant.putString("id", "pvzce:pea_shooter");
        plant.putInt("x", 3);
        list.add(plant);
        root.put("plants", list);

        Path file = Files.createTempFile("pvzce-nbt", ".dat");
        NbtIo.writeCompressed(root, file);
        CompoundTag read = NbtIo.readCompressed(file);

        assertEquals("pvzce:demo_level", read.getString("LevelId"));
        assertEquals(123, read.getInt("Tick"));
        assertEquals("pvzce:pea_shooter", read.getList("plants").getCompound(0).getString("id"));
    }

    @Test
    void builtinDataPackLoadsDemoLevel() throws Exception {
        // Content and convention tags together: the placement rules read tags,
        // so a data-only load would leave every cell unplantable.
        TestContent.loadBuiltInContentAndTags();
        LevelDef demo = BuiltInRegistries.LEVELS.get(Identifier.withDefaultNamespace("yard/adventure/demo_level"));
        assertNotNull(demo);
        assertEquals(9, demo.width());
        assertEquals(5, demo.height());
    }
}
