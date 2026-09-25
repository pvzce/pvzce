package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;
import com.pvzce.server.level.LevelServer;

/**
 * The words a mutation shows the player.
 *
 * <p>Two jobs that look alike and are not: {@link #name} is the panel's label for a mutation and
 * is drawn from the id alone, while {@link #banner} is the one line the centre of the screen
 * shouts when one appears, and it may carry the number that was rolled or the subject that was
 * picked ("僵尸危机：铁桶僵尸").
 *
 * <p>Written in the player's language rather than in keys, because the mutation catalogue is
 * code and its names would otherwise need a language file that a third-party mutation could not
 * add to. The panel's fallback - a mutation with no name of its own - is its id's last segment,
 * which is at least stable and greppable.
 */
final class MutationText {
    private MutationText() {
    }

    /** The label a mutation is listed under. */
    static String name(Mutation mutation, Mutation.Roll roll) {
        String base = baseName(mutation.id());
        if (roll != null && roll.subject().isPresent()) {
            return base + "：" + subjectName(roll.subject().get());
        }
        return base;
    }

    /** The line the banner shows when the mutation arrives. */
    static String banner(Mutation mutation, Mutation.Roll roll, LevelServer level) {
        String name = name(mutation, roll);
        if (roll == null || mutation.weight() <= 0) {
            return name;
        }
        // The multiplier is shown whenever it is not exactly 1: "阳光速率 ×1.4" is the difference
        // between a player who understands why their sunflowers are slow and one who thinks the
        // game broke.
        if (Math.abs(roll.multiplier() - 1F) < 0.001F) {
            return name;
        }
        return name + " ×" + String.format(java.util.Locale.ROOT, "%.2f", roll.multiplier());
    }

    /** The Chinese label for a mutation id, by its path. */
    private static String baseName(Identifier id) {
        if (id == null) {
            return "?";
        }
        return switch (id.path()) {
            case "slot_replace" -> "卡槽替换";
            case "conveyor" -> "传送带";
            case "sun_rate" -> "阳光速率";
            case "plant_attack_rate" -> "植物攻击速率";
            case "zombie_speed" -> "僵尸移动速率";
            case "zombie_spawn_rate" -> "僵尸出怪速率";
            case "plant_sun_cost" -> "植物阳光消耗";
            case "nightfall" -> "天黑";
            case "bowling_nut" -> "坚果保龄球";
            case "whack_a_zombie" -> "打地鼠";
            case "grave_growth" -> "墓碑生长";
            case "zombie_crisis" -> "僵尸危机";
            case "buff_shift" -> "增益变动";
            case "apocalypse" -> "世界末日";
            case "zombie_blast" -> "僵尸爆炸";
            case "plant_blast" -> "植物爆炸";
            case "mendel" -> "孟德尔乱入";
            case "kelp_spread" -> "水草蔓延";
            default -> id.path();
        };
    }

    /**
     * The Chinese label for a zombie, plant or buff id a mutation names in its banner.
     *
     * <p>Buffs are here for the same reason zombies are: a mutation that says what it changed has
     * to be able to say <em>which</em> buff, and the server has no language file to read. The
     * client's own name for a buff is {@code level_buff.<ns>.<path>} (see {@code GuiLang}), and
     * the two lists have to agree - the same debt the panel's own id-to-name table carries.
     */
    static String subjectName(Identifier id) {
        if (id == null) {
            return "?";
        }
        return switch (id.path()) {
            case "basic_zombie" -> "普通僵尸";
            case "conehead_zombie" -> "路障僵尸";
            case "buckethead_zombie" -> "铁桶僵尸";
            case "flag_zombie" -> "旗帜僵尸";
            case "ducky_tube_zombie" -> "救生圈僵尸";
            case "ducky_tube_conehead_zombie" -> "救生圈路障僵尸";
            case "ducky_tube_buckethead_zombie" -> "救生圈铁桶僵尸";
            case "newspaper_zombie" -> "读报僵尸";
            case "pole_vaulter_zombie" -> "撑杆僵尸";
            case "football_zombie" -> "橄榄球僵尸";
            case "dancing_zombie" -> "舞王僵尸";
            case "backup_dancer" -> "伴舞僵尸";
            case "gargantuar" -> "巨人僵尸";
            case "imp" -> "小鬼僵尸";
            case "miner_zombie" -> "矿工僵尸";
            case "balloon_zombie" -> "气球僵尸";
            case "snorkel_zombie" -> "潜水僵尸";
            case "dolphin_rider_zombie" -> "海豚骑士僵尸";
            case "door_zombie" -> "铁门僵尸";
            case "zombie_boss" -> "僵尸博士";
            case "pea" -> "豌豆";
            case "snow_pea" -> "寒冰豌豆";
            // Buffs, for the mutation that shifts them.
            case "auto_collect" -> "自动拾取";
            case "mushroom_range" -> "远距蘑菇";
            default -> id.path();
        };
    }
}
