package com.pvzce.testutil;

import com.google.gson.JsonElement;
import com.pvzce.api.content.EnvValue;
import com.pvzce.api.content.InitialEntityDef;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LevelDialogue;
import com.pvzce.api.content.LevelHint;
import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.content.LevelUnlock;
import com.pvzce.api.content.TeamDef;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;

import java.util.List;
import java.util.Optional;
import java.util.Map;

/**
 * A level definition built by changing one or two things about another one.
 *
 * <p>{@link LevelDef} is a record with twenty-five components, and a test that wants "this shipped
 * level, but with no waves" has to hand it all twenty-five: five test classes had written that
 * copy out, and they had already drifted - one of them remembered {@code playableTeams} and the
 * others silently dropped it, so "the same level except the waves" was not the same level in
 * every file. A copy that names only what it changes cannot drift that way, and a component added
 * to {@code LevelDef} later does not touch any of them.
 *
 * <p>Use this rather than {@code new LevelDef(...)} in tests: a construction list is a copy of the
 * record's shape, and there are already four convenience constructors in {@code LevelDef} for the
 * common short forms.
 */
public final class TestLevels {
    private TestLevels() {
    }

    /** The same level with these waves; {@code List.of()} is "no zombies arrive". */
    public static LevelDef withWaves(LevelDef def, List<WaveDef> waves) {
        return copy(def).waves(waves).build();
    }

    /** The same level with these rules, replacing the ones it had. */
    public static LevelDef withRules(LevelDef def, Map<Identifier, JsonElement> rules) {
        return copy(def).rules(rules).build();
    }

    /** The same level with this scene, which is how a test picks the cells it wants plants in. */
    public static LevelDef withScene(LevelDef def, Map<Identifier, List<String>> scene) {
        return copy(def).scene(scene).build();
    }

    /** A builder holding every component of {@code def}, ready to change one of them. */
    public static Builder copy(LevelDef def) {
        return new Builder(def);
    }

    /**
     * The level's own card list, minus the tools and resources a belt level keeps on the bar.
     *
     * <p>A level that deals its own cards must not pin any <em>plants</em>: the belt deals those,
     * and a pinned one is never granted. Tools and resources are the other way round - the belt
     * cannot deal them (see {@code BeltCardSource#rebuildBar}), so a belt level that pins the
     * shovel is stating the only way to fix a misplaced plant, and every shipped belt level does.
     * Four tests used to assert "a belt level lists nothing at all", which was true only while no
     * belt level had a shovel.
     */
    public static List<Identifier> plantSlots(LevelDef def) {
        List<Identifier> plants = new java.util.ArrayList<>();
        for (Identifier slot : def.slots()) {
            if (slot == null) {
                continue;
            }
            var resolved = com.pvzce.common.core.SlotResolver.resolve(slot);
            if (resolved.isEmpty()
                    || resolved.get().kind() == com.pvzce.common.core.Slot.Kind.PLANT) {
                plants.add(slot);
            }
        }
        return List.copyOf(plants);
    }

    /** Every component of {@link LevelDef}, each defaulting to what it already was. */
    public static final class Builder {
        private Identifier id;
        private String name;
        private String description;
        private int width;
        private int height;
        private Map<Identifier, List<String>> scene;
        private List<TeamDef> teams;
        private Identifier winTeam;
        private Map<Identifier, JsonElement> rules;
        private Map<Identifier, EnvValue> envVars;
        private List<WaveDef> waves;
        private float waveIntervalEndMultiplier;
        private List<Identifier> slots;
        private Map<Identifier, Boolean> unlockResources;
        private int initialSun;
        private LevelDef.LevelMusicDef music;
        private List<InitialEntityDef> initialEntities;
        private int maxSeedSlots;
        private LevelRewards rewards;
        private LevelUnlock unlock;
        private List<TypedMechanic> mechanics;
        private LevelDialogue dialogue;
        private List<LevelHint> hints;
        private List<Identifier> playableTeams;
        private Optional<Identifier> background;
        private List<String> hiddenSceneElements;
        private boolean disableShaders;
        private LevelDef.LevelBuffPlan buffPlan = LevelDef.LevelBuffPlan.NONE;
        private boolean seedScreen = true;

        private Builder(LevelDef def) {
            this.id = def.id();
            this.name = def.name();
            this.description = def.description();
            this.width = def.width();
            this.height = def.height();
            this.scene = def.scene();
            this.teams = def.teams();
            this.winTeam = def.winTeam();
            this.rules = def.rules();
            this.envVars = def.envVars();
            this.waves = def.waves();
            this.waveIntervalEndMultiplier = def.waveIntervalEndMultiplier();
            this.slots = def.slots();
            this.unlockResources = def.unlockResources();
            this.initialSun = def.initialSun();
            this.music = def.music();
            this.initialEntities = def.initialEntities();
            this.maxSeedSlots = def.maxSeedSlots();
            this.rewards = def.rewards();
            this.unlock = def.unlock();
            this.mechanics = def.mechanics();
            this.dialogue = def.dialogue();
            this.hints = def.hints();
            this.playableTeams = def.playableTeams();
            this.background = def.background();
            this.hiddenSceneElements = def.hiddenSceneElements();
            this.disableShaders = def.disableShaders();
            this.buffPlan = def.buffPlan();
            this.seedScreen = def.seedScreen();
        }

        public Builder id(Identifier value) {
            this.id = value;
            return this;
        }

        public Builder name(String value) {
            this.name = value;
            return this;
        }

        public Builder width(int value) {
            this.width = value;
            return this;
        }

        public Builder height(int value) {
            this.height = value;
            return this;
        }

        public Builder scene(Map<Identifier, List<String>> value) {
            this.scene = value;
            return this;
        }

        public Builder teams(List<TeamDef> value) {
            this.teams = value;
            return this;
        }

        public Builder winTeam(Identifier value) {
            this.winTeam = value;
            return this;
        }

        public Builder rules(Map<Identifier, JsonElement> value) {
            this.rules = value;
            return this;
        }

        public Builder envVars(Map<Identifier, EnvValue> value) {
            this.envVars = value;
            return this;
        }

        public Builder waves(List<WaveDef> value) {
            this.waves = value;
            return this;
        }

        /** The level's music timeline; the copy's own unless a test says otherwise. */
        public Builder music(LevelDef.LevelMusicDef value) {
            this.music = value;
            return this;
        }

        public Builder slots(List<Identifier> value) {
            this.slots = value;
            return this;
        }

        public Builder initialSun(int value) {
            this.initialSun = value;
            return this;
        }

        public Builder initialEntities(List<InitialEntityDef> value) {
            this.initialEntities = value;
            return this;
        }

        public Builder maxSeedSlots(int value) {
            this.maxSeedSlots = value;
            return this;
        }

        public Builder rewards(LevelRewards value) {
            this.rewards = value;
            return this;
        }

        public Builder unlock(LevelUnlock value) {
            this.unlock = value;
            return this;
        }

        public Builder mechanics(List<TypedMechanic> value) {
            this.mechanics = value;
            return this;
        }

        public Builder dialogue(LevelDialogue value) {
            this.dialogue = value;
            return this;
        }

        public Builder hints(List<LevelHint> value) {
            this.hints = value;
            return this;
        }

        public Builder playableTeams(List<Identifier> value) {
            this.playableTeams = value;
            return this;
        }

        public Builder background(Optional<Identifier> value) {
            this.background = value;
            return this;
        }

        public Builder hiddenSceneElements(List<String> value) {
            this.hiddenSceneElements = value;
            return this;
        }

        public Builder buffs(LevelDef.LevelBuffPlan value) {
            this.buffPlan = value;
            return this;
        }

        /** False for a level that starts without showing the card screen; see LevelDef. */
        public Builder seedScreen(boolean value) {
            this.seedScreen = value;
            return this;
        }

        public LevelDef build() {
            return new LevelDef(id, name, description, width, height, scene, teams, winTeam, rules,
                    envVars, waves, waveIntervalEndMultiplier, slots, unlockResources, initialSun,
                    music, initialEntities, maxSeedSlots, rewards, unlock, mechanics, dialogue,
                    hints, playableTeams, background, hiddenSceneElements, disableShaders, buffPlan,
                    seedScreen);
        }
    }
}
