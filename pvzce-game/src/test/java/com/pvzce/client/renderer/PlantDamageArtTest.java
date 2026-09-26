package com.pvzce.client.renderer;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.EntityLayers;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.animation.AnimationManager;
import com.pvzce.client.animation.BoneArt;
import com.pvzce.client.animation.BonePose;
import com.pvzce.client.animation.ControllerFile;
import com.pvzce.client.animation.ControllerModel;
import com.pvzce.client.animation.ControllerPlayback;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.resource.PvzceResourceManager;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A chewed nut is drawn chewed.
 *
 * <p>The reported "坚果墙被啃了一部分后，外观没有变化": the cracked drawings were in the rip the whole
 * time, referenced by no track, and the conversion exported only the intact one. The two things that
 * have to hold are pinned here - the model carries the extra drawings, and the one the client picks
 * follows the health the server streams.
 */
class PlantDamageArtTest {
    private static PvzceResourceManager resources;
    private static AnimationManager manager;

    @BeforeAll
    static void setUp() throws Exception {
        resources = TestContent.loadBuiltInContentAndTags();
        manager = new AnimationManager(null, new com.pvzce.client.ClientLevel(), resources);
    }

    @AfterAll
    static void tearDown() throws java.io.IOException {
        resources.close();
    }

    /**
     * The wall-nut's three drawings, at the thirds the original wears them at.
     *
     * <p>Also the negative case: a plant with no cracked art at all gets no override, which is
     * what keeps the renderer from building a bone set for all forty-one of them.
     */
    @Test
    void aWallNutWearsItsCracksAtTheOriginalsThirds() {
        ControllerModel model = model("wall_nut");
        assertTrue(model.bones().containsKey("cracked_1"), "the first crack must be in the model");
        assertTrue(model.bones().containsKey("cracked_2"), "and the second");
        Map<String, BonePose> poses = poses("wall_nut");

        assertEquals(Set.of(), cracksAt("wall_nut", 4000, model, poses),
                "a full-health nut is drawn whole");
        assertEquals(Set.of(), cracksAt("wall_nut", 3000, model, poses),
                "and stays whole above two thirds");
        assertEquals(Set.of("cracked_1"), cracksAt("wall_nut", 2000, model, poses),
                "a nut at half health draws the first crack instead of the whole body");
        assertEquals(Set.of("cracked_2"), cracksAt("wall_nut", 500, model, poses),
                "and the second one below a third");
        assertFalse(artAt("wall_nut", 4000).visibleBones(model, poses).contains("cracked_1"),
                "the intact drawing is not drawn with a crack");

        assertNull(artAt("pea_shooter", 100), "a plant with no cracked art has no override");
    }

    /** Which cracked drawings the plant is drawn with, out of the whole visible set. */
    private static Set<String> cracksAt(String plantId, int health, ControllerModel model,
                                        Map<String, BonePose> poses) {
        return artAt(plantId, health).visibleBones(model, poses).stream()
                .filter(bone -> bone.startsWith(PlantDamageArt.STATE_PREFIX))
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * The tall-nut is taller than the wall-nut, which is the other half of the report.
     *
     * <p>"高坚果体积不对": the two were fitted to the same 0.76-cell box, so the tall-nut was drawn
     * exactly as tall as the wall-nut it is supposed to tower over. The original's own sprites are
     * 146px against 100px.
     */
    @Test
    void theTallNutIsTallerThanTheWallNut() {
        float wall = model("wall_nut").sizeY();
        float tall = model("tall_nut").sizeY();
        assertTrue(tall > wall * 1.35F,
                "the tall-nut is " + tall + " cells against the wall-nut's " + wall
                        + "; the original draws them at 146px and 100px");
    }

    /** The override a plant at {@code health} gets, or {@code null} when it has none. */
    private static BoneArt artAt(String plantId, int health) {
        PlantDef def = BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace(plantId));
        assertNotNull(def, plantId + " has to be registered");
        ClientEntity entity = new ClientEntity(1, "plant", "pvzce:" + plantId, 2.5F, 1.5F,
                health, EntityLayers.PLANT, EntityAnimations.IDLE, 0F, "");
        return PlantDamageArt.forEntity(entity, model(plantId));
    }

    private static Map<String, BonePose> poses(String plantId) {
        ClientEntity entity = new ClientEntity(1, "plant", "pvzce:" + plantId, 2.5F, 1.5F, 4000,
                EntityLayers.PLANT, EntityAnimations.IDLE, 0F, "");
        manager.play(entity, EntityAnimations.IDLE);
        var playback = manager.playback(entity);
        assertTrue(playback instanceof ControllerPlayback,
                plantId + " must resolve to a controller playback");
        return ((ControllerPlayback) playback).currentPose(manager.now());
    }

    private static ControllerModel model(String plantId) {
        Identifier fileId = com.pvzce.common.core.EntityArt.animationFile(
                Identifier.withDefaultNamespace(plantId));
        assertNotNull(fileId, plantId + " must declare an animation file");
        var file = manager.file(fileId);
        assertTrue(file.isPresent(), plantId + " must load its animation file " + fileId);
        assertTrue(file.get() instanceof ControllerFile,
                plantId + " must be a controller model, not a flipbook");
        return ((ControllerFile) file.get()).model();
    }
}
