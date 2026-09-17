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
     * Slider ranges per rule path.
     *
     * <p>{@link GameRuleType} clamps values but does not expose its bounds, and the editor must
     * not offer a range the server would silently clamp afterwards. These mirror the
     * registrations in {@code BuiltInRegistries.registerGameRules}.
     */
    private static final Map<String, float[]> RANGES = Map.ofEntries(
            Map.entry("pvzce:day_length", new float[]{0F, 12000F}),
            Map.entry("pvzce:night_length", new float[]{-1F, 12000F}),
            Map.entry("pvzce:sun_spawn_chance", new float[]{0F, 1F}),
            Map.entry("pvzce:sun_value", new float[]{1F, 500F}),
            Map.entry("pvzce:crater_recovery", new float[]{0F, 30000F}),
            Map.entry("pvzce:zombie_damage_multiplier", new float[]{0F, 5F}),
            Map.entry("pvzce:zombie_speed_multiplier", new float[]{0F, 5F}),
            Map.entry("pvzce:plant_damage_multiplier", new float[]{0F, 5F}),
            Map.entry("pvzce:seed_cooldown_multiplier", new float[]{0F, 5F}),
            Map.entry("pvzce:max_players_per_team", new float[]{1F, 64F}));

    /** Rules whose value is a whole number of ticks or players; the slider rounds. */
    private static final Set<String> INTEGER_RULES = Set.of(
            "pvzce:day_length", "pvzce:night_length", "pvzce:crater_recovery",
            "pvzce:sun_value", "pvzce:max_players_per_team");

    /** The order the page lists them in: pacing, economy, combat, then the rest. */
    private static final List<String> ORDER = List.of(
            "pvzce:day_length", "pvzce:night_length", "pvzce:sun_spawn_chance", "pvzce:sun_value",
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
                float[] range = RANGES.getOrDefault(id.toString(), new float[]{0F, 1F});
                float fallback = type.defaultValue() instanceof Number number ? number.floatValue() : range[0];
                page.field(FieldWidgets.number(path, label, range[0], range[1],
                        INTEGER_RULES.contains(id.toString()), Optional.of(fallback)));
            }
        }
        return page.build();
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
