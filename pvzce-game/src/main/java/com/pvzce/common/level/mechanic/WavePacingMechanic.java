package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.content.WavePacingData;
import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.common.PvzceIds;

import java.util.ArrayList;
import java.util.List;

/**
 * How this level's zombies arrive, and what a cleared lawn buys the player.
 *
 * <p>The mechanic behind {@link WavePacingData}; the data record explains what each mode means
 * and this class only answers the two questions the engine asks about it:
 *
 * <ul>
 *   <li>{@link #of} - what pacing does this level run with? Every level has an answer, because a
 *       level that declares nothing still gets the clear bonus (see {@link #DEFAULT_PACING}):
 *       "the player cleared the field and is waiting out a countdown written for someone slower"
 *       is not a mode a level opts into, it is a defect the engine should not have.</li>
 *   <li>{@link #validate} - are the wave numbers, pools and ranges this level wrote about this
 *       level? A per-wave table is keyed by wave numbers, and a level that inserted a wave has
 *       stale numbers that must be reported rather than silently ignored.</li>
 * </ul>
 *
 * <p>Registering this mechanic is also what puts a page in the level editor: {@link #editorFields}
 * is the whole UI, and a level with the mechanic gets a page for it automatically.
 */
public final class WavePacingMechanic implements LevelMechanic<WavePacingData> {
    /**
     * The pacing a level that declares no {@code pvzce:wave_pacing} block runs with.
     *
     * <p>Not "no pacing": the clear bonus and the kill gate's own tightening are what make the
     * shipped wave tables playable without rewriting all of them, and a level that wants the old
     * behaviour writes {@code "clear_reward_factor": 1} to say so. This is the same shape as the
     * implicit deck and the implicit mowers - an ordinary level should not have to declare the
     * ordinary rules.
     */
    public static final WavePacingData DEFAULT_PACING = WavePacingData.DEFAULT;

    @Override
    public MapCodec<WavePacingData> codec() {
        return WavePacingData.MAP_CODEC;
    }

    @Override
    public List<String> validate(LevelDef def, WavePacingData data) {
        List<String> errors = new ArrayList<>();
        int waveCount = def.waves().size();
        for (WavePacingData.WavePacing entry : data.waves()) {
            errors.addAll(entry.validate(waveCount));
            // A budget wave and a written composition are two answers to one question. Reported
            // rather than resolved, because which one the author meant is not knowable here: a
            // level that wants both writes the budget and lets `entries` be a fallback on
            // purpose, and that is a mistake worth reading out loud.
            if (entry.budget().isPresent() && entry.budget().orElse(0) > 0) {
                for (int number : entry.waves()) {
                    if (number < 1 || number > waveCount) {
                        continue;
                    }
                    WaveDef wave = def.waves().get(number - 1);
                    if (wave.totalZombies() > 0) {
                        errors.add("wave_pacing row for wave " + number
                                + " declares a budget, but that wave also writes "
                                + wave.totalZombies() + " zombies in entries; the budget wins,"
                                + " so the entries would never arrive");
                    }
                }
            }
        }
        if (!(data.clearRewardFactor() >= 1F) || data.clearRewardFactor() > 20F) {
            errors.add("wave_pacing clear_reward_factor must be inside [1, 20], was "
                    + data.clearRewardFactor());
        }
        if (data.clearRewardMinTicks() < 0) {
            errors.add("wave_pacing clear_reward_min_ticks cannot be negative, was "
                    + data.clearRewardMinTicks());
        }
        if (data.clearRewardGraceTicks() < 0) {
            errors.add("wave_pacing clear_reward_grace_ticks cannot be negative, was "
                    + data.clearRewardGraceTicks());
        }
        if (!(data.earlyWaveKillRatio() > 0F) || data.earlyWaveKillRatio() > 1F) {
            errors.add("wave_pacing early_wave_kill_ratio must be inside (0, 1], was "
                    + data.earlyWaveKillRatio());
        }
        if (!(data.earlyKillDelayFactor() > 0F) || data.earlyKillDelayFactor() > 1F) {
            errors.add("wave_pacing early_kill_delay_factor must be inside (0, 1], was "
                    + data.earlyKillDelayFactor());
        }
        return errors;
    }

    /**
     * The editable fields, which is the editor's whole page for this mechanic.
     *
     * <p>The per-wave table is one {@link FieldSpec.StringList} rather than a nested list: the
     * editor draws it as an editable line of wave numbers, and every other field of a row is a
     * level-wide default. Rows that need their own {@code max_alive} are the exception, not the
     * shape, and a level that needs one can still write it by hand.
     */
    @Override
    public List<FieldSpec> editorFields() {
        return List.of(
                FieldSpec.decimal("clear_reward_factor", "pvzce.mechanic.wave_pacing.field.clear_reward_factor",
                        1F, 20F),
                FieldSpec.integer("clear_reward_grace_ticks",
                        "pvzce.mechanic.wave_pacing.field.clear_reward_grace_ticks", 0, 3600),
                FieldSpec.decimal("early_wave_kill_ratio",
                        "pvzce.mechanic.wave_pacing.field.early_wave_kill_ratio", 0.05F, 1F),
                FieldSpec.decimal("early_kill_delay_factor",
                        "pvzce.mechanic.wave_pacing.field.early_kill_delay_factor", 0.25F, 1F),
                new FieldSpec.Choice("default_mode", "pvzce.mechanic.wave_pacing.field.default_mode",
                        List.of("fixed", "stockpile", "survival_ratio", "budget")),
                new FieldSpec.Choice("waves[0].mode", "pvzce.mechanic.wave_pacing.field.mode",
                        List.of("fixed", "stockpile", "survival_ratio", "budget")),
                new FieldSpec.IntList("waves[0].waves", "pvzce.mechanic.wave_pacing.field.waves"));
    }

    /**
     * The pacing of a level, whether or not it declared this mechanic.
     *
     * <p>Asked on the level rather than on a mechanic instance because the server needs the
     * answer for every level, including the ones whose file has no {@code mechanics} block - see
     * {@link #DEFAULT_PACING}.
     */
    public static WavePacingData of(LevelDef def) {
        return LevelMechanics.<WavePacingData>dataOf(def, PvzceIds.MECHANIC_WAVE_PACING,
                WavePacingData.class).orElse(DEFAULT_PACING);
    }

    /** True when the level wrote a block of its own rather than running on the defaults. */
    public static boolean declared(LevelDef def) {
        return LevelMechanics.has(def, PvzceIds.MECHANIC_WAVE_PACING);
    }
}
