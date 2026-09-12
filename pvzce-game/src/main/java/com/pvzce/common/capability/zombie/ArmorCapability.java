package com.pvzce.common.capability.zombie;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.ArmorDef;
import com.pvzce.api.content.ProjectileDef;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.IntTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.common.PvzceParticles;

import java.util.ArrayList;
import java.util.List;

/**
 * Layered armor with per-piece durability.
 *
 * <p>Front armor intercepts ground shots and top armor intercepts lobbed shots;
 * losing it can hand the zombie a faster post-armor speed (newspaper zombie).
 * The armor list and the post-armor speed now live in this capability rather
 * than being fields every zombie carries.
 */
public final class ArmorCapability implements ZombieCapability {
    private final List<ArmorDef> armor;
    private final float postArmorSpeed;

    private final List<Piece> pieces = new ArrayList<>();

    public ArmorCapability(List<ArmorDef> armor, float postArmorSpeed) {
        this.armor = List.copyOf(armor);
        this.postArmorSpeed = postArmorSpeed;
        for (ArmorDef def : this.armor) {
            pieces.add(new Piece(def, def.durability()));
        }
    }

    public static final MapCodec<ArmorCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            ArmorDef.CODEC.listOf().optionalFieldOf("pieces", List.of()).forGetter(ArmorCapability::armor),
            Codec.FLOAT.optionalFieldOf("post_armor_speed", -1F).forGetter(ArmorCapability::postArmorSpeed)
    ).apply(i, ArmorCapability::new));

    public List<ArmorDef> armor() {
        return armor;
    }

    public float postArmorSpeed() {
        return postArmorSpeed;
    }

    /** Total remaining armor HP (used by tests and the HUD). */
    public int totalHealth() {
        int total = 0;
        for (Piece piece : pieces) {
            total += Math.max(0, piece.hp);
        }
        return total;
    }

    public boolean hasArmor() {
        return pieces.stream().anyMatch(piece -> piece.hp > 0);
    }

    @Override
    public ZombieCapability instantiate() {
        return new ArmorCapability(armor, postArmorSpeed);
    }

    @Override
    public float speedMultiplier(ZombieEntity zombie) {
        return postArmorSpeed > 0F && !hasArmor() && !armor.isEmpty()
                ? postArmorSpeed / Math.max(0.0001F, zombie.def().moveSpeed())
                : 1F;
    }

    @Override
    public boolean onProjectileHit(ZombieEntity zombie, ProjectileDef projectile, int damage, LevelAccess level) {
        String wanted = "air".equals(projectile.layer()) ? ArmorDef.TOP : ArmorDef.FRONT;
        Piece piece = pieces.stream()
                .filter(p -> p.hp > 0 && wanted.equals(p.def.position()))
                .findFirst()
                .orElse(null);
        if (piece == null) {
            return false;
        }
        piece.hp -= damage;
        zombie.setAnimation(EntityAnimations.HIT);
        boolean broke = false;
        if (piece.hp <= 0) {
            piece.hp = 0;
            broke = true;
            zombie.setAnimation(EntityAnimations.ANGRY);
        }
        Identifier armorSound = projectile.sounds().impact()
                .orElse(zombie.def().sounds().armorHit().orElse(PvzceSounds.ZOMBIE_SHIELD_HIT));
        level.emitEffect(PvzceParticles.ZOMBIE_HELMET.toString(), zombie.cellX(), zombie.cellY(), armorSound);
        if (broke && zombie.def().sounds().special().isPresent()) {
            level.emitEffect("", zombie.cellX(), zombie.cellY(), zombie.def().sounds().special().get());
        }
        return true;
    }

    @Override
    public void save(CompoundTag tag) {
        // Keep slots aligned with the definition so a broken piece restores as a
        // zero-HP entry and can never come back.
        ListTag list = new ListTag();
        for (int i = 0; i < armor.size(); i++) {
            int hp = i < pieces.size() ? Math.max(0, pieces.get(i).hp) : 0;
            list.add(new IntTag(hp));
        }
        tag.put("pieces", list);
    }

    @Override
    public void load(CompoundTag tag) {
        ListTag list = tag.getList("pieces");
        for (int i = 0; i < pieces.size() && i < list.size(); i++) {
            if (list.get(i) instanceof IntTag hp) {
                pieces.get(i).hp = Math.max(0, hp.value());
            }
        }
    }

    private static final class Piece {
        private final ArmorDef def;
        private int hp;

        private Piece(ArmorDef def, int hp) {
            this.def = def;
            this.hp = hp;
        }
    }
}
