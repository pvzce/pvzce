package com.pvzce.client.gui.editor.pages;

import com.pvzce.api.content.GameRuleType;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.editor.EditorPage;
import com.pvzce.client.gui.editor.form.FieldWidgets;
import com.pvzce.client.gui.editor.form.FormPage;
import com.pvzce.common.core.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The rules page: one field per registered game rule.
 *
 * <p>It is the sample for what a declarative page buys. The page it replaces was a list of
 * rule names, a value box, a slider and a toggle whose visibility was switched by hand, plus
 * a reset button and two parsers - roughly 150 lines to edit eleven numbers, all of it
 * re-derived from the rule registry on every build. Here the registry is walked once and each
 * rule becomes a field with the same write rule as every other field in the editor: a value
 * equal to the rule's default is removed from the file rather than frozen into it, which is
 * what "已改" used to mean.
 */
public final class RulePage {
    /**
     * The three rules whose slider is deliberately narrower than the rule's own bounds.
     *
     * <p>Everything else takes its range from {@link GameRuleType#bounds()}, so the page offers
     * exactly what the server accepts. These are registered as "0 (or -1) up to
     * {@code Integer.MAX_VALUE}" because those really are their bounds - a tick count has no
     * meaningfully small ceiling - and a slider spanning two billion ticks is not a control
     * anybody can use. 12000 ticks is 200 seconds, longer than any shipped level's day.
     *
     * <p>Written down as a short exception rather than a table: the table this replaces also
     * listed the rules whose range already matched the registration, and it had drifted from it
     * ({@code sun_value} stopped at 500 while the server took 10000, so the editor could not
     * express a value the game allows).
     */
    private static final Map<String, float[]> NARROWER_SLIDER = Map.of(
            "pvzce:day_length", new float[]{0F, 12000F},
            "pvzce:night_length", new float[]{-1F, 12000F},
            "pvzce:crater_recovery", new float[]{0F, 30000F},
            // The sky's own clock: a gap is seconds, not minutes, and the registered bound of
            // 36000 would make the slider useless for every value a level actually writes.
            "pvzce:sun_spawn_interval_min", new float[]{0F, 3600F},
            "pvzce:sun_spawn_interval_max", new float[]{0F, 3600F},
            "pvzce:sun_spawn_initial_ticks", new float[]{0F, 3600F});

    /** The order the page lists them in: pacing, economy, combat, then the rest. */
    private static final List<String> ORDER = List.of(
            "pvzce:day_length", "pvzce:night_length",
            "pvzce:sun_spawn_interval_min", "pvzce:sun_spawn_interval_max",
            "pvzce:sun_spawn_initial_ticks", "pvzce:sun_value",
            "pvzce:zombie_sun_drop_chance", "pvzce:zombie_sun_drop_count",
            "pvzce:crater_recovery", "pvzce:graves_spawn_night", "pvzce:zombie_damage_multiplier",
            "pvzce:zombie_speed_multiplier", "pvzce:plant_damage_multiplier",
            "pvzce:seed_cooldown_multiplier",
            "pvzce:max_players_per_team", "pvzce:level_pause_on_single_player");

    public static EditorPage create() {
        FormPage.Builder page = FormPage.builder("rule")
                .label(GuiLang.raw("pvzce.editor.page.rule", "rule"))
                .order(30)
                .heading(GuiLang.raw("pvzce.editor.tip.rule", "改动立即生效，保存后写入关卡"));
        for (Identifier id : orderedRules()) {
            GameRuleType<?> type = BuiltInRegistries.GAME_RULES.get(id);
            if (type == null) {
                continue;
            }
            String path = "rules." + id;
            String label = GuiLang.name(id);
            if (type instanceof GameRuleType.BooleanRule booleanRule) {
                page.field(FieldWidgets.bool(path, label, "开", "关",
                        Optional.of(Boolean.TRUE.equals(booleanRule.defaultValue()))));
            } else {
                float[] range = sliderRange(id, type);
                float fallback = type.defaultValue() instanceof Number number ? number.floatValue() : range[0];
                page.field(FieldWidgets.number(path, label, range[0], range[1],
                        type.integral(), Optional.of(fallback)));
            }
        }
        return page.build();
    }

    /**
     * The range the page offers for one rule.
     *
     * <p>Package-private so a test can walk every registered rule and check that the page never
     * offers less than the server accepts - which is exactly how {@code sun_value} came to be
     * editable only up to 500.
     */
    static float[] sliderRange(Identifier id, GameRuleType<?> type) {
        float[] bounds = type.bounds();
        if (bounds == null) {
            // A numeric rule type with no bounds would silently get a 0..1 slider and clamp away
            // everything the author typed; failing here says which registration is incomplete.
            throw new IllegalStateException("Rule " + id + " is numeric but declares no bounds");
        }
        return NARROWER_SLIDER.getOrDefault(id.toString(), bounds);
    }

    /** Every registered rule: the page's own order first, then anything a mod added. */
    private static List<Identifier> orderedRules() {
        List<Identifier> rules = new ArrayList<>();
        for (String path : ORDER) {
            Identifier id = Identifier.tryParse(path);
            if (id != null && BuiltInRegistries.GAME_RULES.containsKey(id)) {
                rules.add(id);
            }
        }
        for (Identifier id : BuiltInRegistries.GAME_RULES.keySet()) {
            if (!rules.contains(id)) {
                rules.add(id);
            }
        }
        return rules;
    }

    private RulePage() {
    }
}
