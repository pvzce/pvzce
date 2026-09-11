package com.pvzce.server;

import com.google.gson.JsonPrimitive;
import com.pvzce.api.content.EnvValue;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.resource.PvzceDataLoader;
import com.pvzce.common.resource.PvzceResourceManager;
import com.pvzce.server.env.LevelEnvVars;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M3: environment-variable channel and the env-gated plant AI. */
class EnvironmentAndAiTest {
    private static LevelDef demo;

    @BeforeAll
    static void load() throws Exception {
        BuiltInRegistries.bootstrap();
        PvzceResourceManager resources = new PvzceResourceManager(Thread.currentThread().getContextClassLoader());
        resources.init(Path.of(System.getProperty("java.io.tmpdir"), "pvzce-env-ai-test"));
        PvzceDataLoader.LoadResult result = new PvzceDataLoader().load(resources, BuiltInRegistries.ACCESS);
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        demo = BuiltInRegistries.LEVELS.get(Identifier.withDefaultNamespace("demo_level"));
    }

    @Test
    void envVarsReadWriteAndListen() {
        LevelEnvVars vars = new LevelEnvVars(Map.of(
                Identifier.withDefaultNamespace("demo_flag"),
                new EnvValue(Identifier.withDefaultNamespace("boolean"), new JsonPrimitive(true))));
        assertTrue(vars.getBoolean(Identifier.withDefaultNamespace("demo_flag"), false));
        assertFalse(vars.has(Identifier.withDefaultNamespace("missing")));

        final int[] changed = {0};
        vars.listen(Identifier.withDefaultNamespace("counter"), (name, oldValue, newValue) -> changed[0]++);
        vars.set(Identifier.withDefaultNamespace("int"), Identifier.withDefaultNamespace("counter"), 42);
        assertEquals(42, vars.getInt(Identifier.withDefaultNamespace("counter"), 0));
        assertEquals(1, changed[0]);
    }

    @Test
    void plantAiPlantsWhenEnvVarEnabled() {
        LevelDef def = new LevelDef(
                Identifier.withDefaultNamespace("ai_test"), "", "", 9, 5,
                demo.scene(), demo.teams(), demo.winTeam(), demo.rules(),
                Map.of(Identifier.withDefaultNamespace("plant_ai"),
                        new EnvValue(Identifier.withDefaultNamespace("boolean"), new JsonPrimitive(true))),
                demo.waves(), demo.waveIntervalEndMultiplier(), demo.slots(), demo.unlockResources(), 150, LevelDef.LevelMusicDef.DEFAULT, List.of());
        LevelServer level = new LevelServer(def);
        CapturingBridge bridge = new CapturingBridge();
        for (int i = 0; i < 600 && level.gameState().equals("running"); i++) {
            level.tick(bridge);
        }
        assertTrue(level.plantCount() > 0, "plant AI should have planted at least one plant");
    }

    private static final class CapturingBridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
        }
    }
}
