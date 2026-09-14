package com.pvzce.common.capability.zombie;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.ArmorDef;
import com.pvzce.api.content.EquipmentDef;
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

    /** Remaining HP of one piece by its definition id, or 0 when it is gone. */
    public int healthOf(Identifier pieceId) {
        for (Piece piece : pieces) {
            if (piece.def.id().equals(pieceId)) {
                return Math.max(0, piece.hp);
            }
        }
        return 0;
    }

    /** True while the named piece is still on the zombie. */
    public boolean wearing(Identifier pieceId) {
        return healthOf(pieceId) > 0;
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

    /**
     * A shot hits the piece that is in its way.
     *
     * <p>A lobbed shot comes down on the head, so it meets whatever is worn up there
     * ({@code top}) and arcs over a shield held in front - which is the whole reason the
     * two positions exist, and why a buttered cabbage bypasses a screen door.
     *
     * <p>A flat shot meets the shield first, and <b>if there is no shield, the hat</b>: a
     * cone or a bucket sits on the head and stops a pea exactly as it stops a lob. Only
     * routing flat shots to {@code front} meant a Conehead's armour was never touched by
     * the peas that killed it - the cone stayed pristine until the zombie died and then
     * vanished with it, instead of wearing through its two damaged drawings first.
     */
    @Override
    public boolean onProjectileHit(ZombieEntity zombie, ProjectileDef projectile, int damage, LevelAccess level) {
        boolean lobbed = "air".equals(projectile.layer());
        String wanted = lobbed ? ArmorDef.TOP : ArmorDef.FRONT;
        Identifier armorSound = projectile.sounds().impact()
                .orElse(zombie.def().sounds().armorHit().orElse(PvzceSounds.ZOMBIE_SHIELD_HIT));
        if (absorb(zombie, wanted, damage, level, armorSound)) {
            return true;
        }
        return !lobbed && absorb(zombie, ArmorDef.TOP, damage, level, armorSound);
    }

    /**
     * Armor takes bowling-nut hits like it takes shots.
     *
     * <p>An impact has no projectile layer to route it, so it hits whatever the zombie is
     * wearing: the front piece first (a shield is in the way), then the top one (the cone
     * and the bucket, which this project models as head armor and which only lobbed shots
     * reach otherwise). Without the second lookup a bowling nut would find no front piece on
     * a Conehead, fall through to the body, and kill a buckethead in one hit - the armor
     * ladder the mini-game is built on would never happen.
     */
    @Override
    public boolean onImpact(ZombieEntity zombie, int damage, LevelAccess level) {
        Identifier sound = zombie.def().sounds().armorHit().orElse(PvzceSounds.ZOMBIE_SHIELD_HIT);
        return absorb(zombie, ArmorDef.FRONT, damage, level, sound)
                || absorb(zombie, ArmorDef.TOP, damage, level, sound);
    }

    /** Resolves a hit against the first intact piece in {@code wanted}, or on the body. */
    private boolean absorb(ZombieEntity zombie, String wanted, int damage, LevelAccess level, Identifier sound) {
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
        // What the hit throws off depends on whether it took the armour with it: a piece
        // that is still on the zombie gives a spark, the piece that just shattered is
        // *itself* the debris. This used to be one particle for every armour hit - the
        // football helmet - so a Conehead showered helmets with each pea and its own cone
        // never came off. The equipment entry names the sprite, because the piece id
        // ("cone") and the art ("traffic_cone") are not the same word.
        String particle = "";
        if (broke) {
            particle = zombie.def().equipmentForPiece(piece.def.id())
                    .flatMap(EquipmentDef::dropParticle)
                    .map(Identifier::toString)
                    .orElse("");
        } else {
            particle = PvzceParticles.HIT_SPARK.toString();
        }
        level.emitEffect(particle, zombie.cellX(), zombie.cellY(), sound);
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
