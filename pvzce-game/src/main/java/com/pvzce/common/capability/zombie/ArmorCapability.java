package com.pvzce.common.capability.zombie;

import com.pvzce.common.level.WorldPosition;
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
    /**
     * How high above the zombie's cell a broken piece starts its fall, in cells.
     *
     * <p>The zombie model is 0.95 cells tall, so this is roughly shoulder height - where a
     * cone or a bucket actually sits.
     */
    private static final float DROP_HEIGHT = 0.55F;

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

    /** Applies one entity's durability factor, preserving each piece's remaining fraction. */
    public void setDurabilityMultiplier(double multiplier) {
        for (Piece piece : pieces) {
            int maximum = (int) Math.min(Integer.MAX_VALUE,
                    Math.max(0L, Math.round(piece.def.durability() * multiplier)));
            if (maximum == piece.maximum) continue;
            piece.hp = piece.maximum <= 0 ? 0
                    : (int) Math.min(maximum, Math.round(piece.hp * (double) maximum / piece.maximum));
            piece.maximum = maximum;
        }
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
        return onProjectileHit(zombie, projectile, damage, level, true);
    }

    @Override
    public boolean onProjectileHit(ZombieEntity zombie, ProjectileDef projectile, int damage,
                                   LevelAccess level, boolean emitImpact) {
        boolean lobbed = "air".equals(projectile.layer());
        // A spray goes through what is held in front and is still stopped by what is worn on the
        // head: the fume-shroom's gas passes a screen door (that is the whole reason it is the
        // answer to a Screen Door Zombie) but a cone, a bucket or a football helmet still takes
        // the hit. Read from the shot's own damage type, so a pack can ship a spray of its own.
        boolean piercesFront = projectile.damageType()
                .map(ZombieEntity::damageType)
                .map(com.pvzce.api.content.DamageTypeDef::ignoresFrontArmor)
                .orElse(false);
        String wanted = lobbed || piercesFront ? ArmorDef.TOP : ArmorDef.FRONT;
        // The armour's own sound first, not the shot's: a pea hitting a bucket is a metal
        // clank, and it was playing the same *splat* as a pea hitting a body because the
        // projectile's generic impact sound was preferred. A piece of content that declares
        // no `armor_hit` still falls back to it, and then to the shield hit.
        Identifier armorSound = zombie.def().sounds().armorHit()
                .orElse(projectile.sounds().impact().orElse(PvzceSounds.ZOMBIE_SHIELD_HIT));
        String splash = projectile.impactParticle().map(Identifier::toString).orElse("");
        if (absorb(zombie, wanted, damage, level, armorSound, splash, emitImpact)) {
            return true;
        }
        return !lobbed && absorb(zombie, ArmorDef.TOP, damage, level, armorSound, splash, emitImpact);
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
        return onImpact(zombie, damage, level, null);
    }

    @Override
    public boolean onImpact(ZombieEntity zombie, int damage, LevelAccess level,
                            com.pvzce.api.content.DamageTypeDef type) {
        Identifier sound = zombie.def().sounds().armorHit().orElse(PvzceSounds.ZOMBIE_SHIELD_HIT);
        boolean piercesFront = type != null && type.ignoresFrontArmor();
        return (!piercesFront && absorb(zombie, ArmorDef.FRONT, damage, level, sound, ""))
                || absorb(zombie, ArmorDef.TOP, damage, level, sound, "");
    }

    /**
     * Takes one whole piece off, the way the magnet-shroom does.
     *
     * <p>Deliberately not "set the health to zero and let the impact path notice": losing a piece
     * has consequences beyond its health - the equipment stops being drawn, a piece that promises
     * a speed change triggers the rage clip - and every one of them already lives in
     * {@link #absorb}. So this damages the piece by exactly its remaining health, which is the
     * same event as a hit that happened to finish it.
     *
     * @return true when something came off
     */
    @Override public Identifier magneticItem(ZombieEntity zombie) {
        return pieces.stream().filter(p -> p.hp > 0 && p.def.magnetic())
                .map(p -> p.def.id()).findFirst().orElse(null);
    }

    @Override public boolean removeMagneticItem(ZombieEntity zombie, LevelAccess level) {
        Piece piece = pieces.stream().filter(p -> p.hp > 0 && p.def.magnetic()).findFirst().orElse(null);
        if (piece == null) return false;
        // Equipment taken by a magnet must not also fall as damage debris.
        piece.hp = 0;

        return true;
    }

    public boolean strip(ZombieEntity zombie, LevelAccess level) {
        Piece piece = pieces.stream().filter(p -> p.hp > 0).findFirst().orElse(null);
        if (piece == null) {
            return false;
        }
        Identifier sound = zombie.def().sounds().armorHit().orElse(PvzceSounds.ZOMBIE_SHIELD_HIT);
        return absorb(zombie, piece.def.position(), piece.hp, level, sound, "");
    }

    /**
     * Resolves a hit against the first intact piece in {@code wanted}, or on the body.
     *
     * @param splash the shot's own impact effect, or empty for a hit that is not a shot
     */
    private boolean absorb(ZombieEntity zombie, String wanted, int damage, LevelAccess level,
                           Identifier sound, String splash) {
        return absorb(zombie, wanted, damage, level, sound, splash, true);
    }

    private boolean absorb(ZombieEntity zombie, String wanted, int damage, LevelAccess level,
                           Identifier sound, String splash, boolean emitImpact) {
        Piece piece = pieces.stream()
                .filter(p -> p.hp > 0 && wanted.equals(p.def.position()))
                .findFirst()
                .orElse(null);
        if (piece == null) {
            return false;
        }
        piece.hp -= damage;
        boolean broke = false;
        if (piece.hp <= 0) {
            piece.hp = 0;
            broke = true;
            // Only a piece whose *definition* promises a speed change is a rage trigger: the
            // newspaper is the one that tears, and `angry` is its clip. A cone or a bucket
            // breaking changes how the zombie looks, not how it behaves, and asking for a
            // clip the model does not have made the client fall back to `idle` - the same
            // standing-pose stutter the hit state used to cause.
            if (postArmorSpeed > 0F) {
                zombie.setAnimation(EntityAnimations.ANGRY);
            }
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
        } else if (!splash.isEmpty()) {
            // The shot came apart on the armour, which is what the player watched happen.
            particle = splash;
        } else {
            particle = PvzceParticles.HIT_SPARK.toString();
        }
        // From the head, where the piece was: a cone that pops out of the zombie's boots
        // reads as a particle that happened to fire, not as a hat coming off.
        if (emitImpact || broke) {
            level.emitEffect(particle, new WorldPosition(zombie.cellX(), zombie.cellY(), zombie.height() + DROP_HEIGHT), zombie.surfaceId(), sound);
        }
        if (broke && zombie.def().sounds().special().isPresent()) {
            level.emitEffect("", zombie.position(), zombie.surfaceId(), zombie.def().sounds().special().get());
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
                pieces.get(i).hp = Math.max(0, Math.min(pieces.get(i).maximum, hp.value()));
            }
        }
    }

    private static final class Piece {
        private final ArmorDef def;
        private int hp;
        private int maximum;

        private Piece(ArmorDef def, int hp) {
            this.def = def;
            this.hp = hp;
            this.maximum = hp;
        }
    }
}
