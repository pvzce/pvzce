package com.pvzce.server.entity;

import com.pvzce.api.entity.EntityKind;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.Team;
import com.pvzce.server.level.LevelServer;

/**
 * A seed packet on the lawn: the plant card a broken container held.
 *
 * <p>What a scary pot or a smashed vase hands over now, in place of the card that used to appear
 * in the bar by itself. The packet lies where its container stood, is picked up with a click (see
 * {@code LevelServer.pickUpCardDrop}), and is then in the player's hand until it is planted -
 * free of sun and of cooldown, because breaking the container is what paid for it.
 *
 * <p><b>Its clock is its health.</b> That is not a trick for want of a field: the packet's whole
 * state is "how much longer it is there", the health is the one number every entity already
 * carries, streams to the client and survives a save, and the client's flash is drawn from it. A
 * second counter beside it would be the same number twice, and the two could disagree.
 */
public final class CardDropEntity extends PvzceEntity {
    /** Which card this packet is. A card id, not a plant id: see {@code LevelServer.spawnCardDrop}. */
    private Identifier card;
    /**
     * True while the player is carrying this packet.
     *
     * <p>A held packet is still an entity - it is where the card lives, and the level's own save
     * writes it - but it is off the clock and off the lawn: the client draws it in the player's
     * hand instead (see {@code HeldCardS2C}). Nothing may pick it up twice, and it may not expire
     * from under the hand that is holding it.
     */
    private boolean held;
    private float landingY;
    private boolean falling;

    public void fallFromSky(int rows) {
        landingY = cellY();
        setCellY(rows + 0.5F);
        falling = true;
    }

    public int landingRow() {
        return falling ? (int) Math.floor(landingY) : gridY();
    }

    public CardDropEntity(Identifier card, Team team, int gridX, int gridY) {
        super(card, team, gridX + 0.5F, gridY + 0.5F, PvzceConstants.CARD_DROP_LIFETIME_TICKS);
        this.card = card;
    }

    @Override
    public String entityKind() {
        return EntityKind.CARD_DROP;
    }

    /**
     * The air layer, like every other drop.
     *
     * <p>The layer is what says "this is not planted in the lawn": a packet lies on the grass and
     * is drawn above everything standing in its row, which is what makes it clickable when a
     * plant of the player's is right beside it.
     */
    @Override
    public int layer() {
        return com.pvzce.api.entity.EntityLayers.AIR;
    }

    /** The card this packet is: a plant the player can plant, or one that goes to the bar. */
    public Identifier card() {
        return card;
    }

    public boolean held() {
        return held;
    }

    public void setHeld(boolean held) {
        this.held = held;
    }

    /** How many ticks this packet has left; its health, which the client reads as its clock. */
    public int ticksLeft() {
        return Math.max(0, health());
    }

    @Override
    public void tick(LevelServer level) {
        if (held) {
            // A packet in the player's hand is not on the lawn's clock: the twenty seconds are
            // the time the player has to notice it where it fell, not a deadline on using it.
            return;
        }
        if (falling) {
            setCellY(Math.max(landingY, cellY() - PvzceConstants.SEED_RAIN_FALL_SPEED
                    / PvzceConstants.TICKS_PER_SECOND));
            falling = cellY() > landingY;
            return;
        }
        setHealth(health() - 1);
        if (health() <= 0) {
            remove();
        }
    }

    @Override
    public CompoundTag saveState() {
        CompoundTag tag = saveBaseState();
        tag.putString("card", card.toString());
        tag.putByte("Falling", (byte) (falling ? 1 : 0));
        tag.putFloat("LandingY", landingY);
        return tag;
    }

    @Override
    public void restoreState(CompoundTag tag) {
        restoreBaseState(tag);
        Identifier saved = Identifier.tryParse(tag.getString("card"));
        if (saved != null) {
            card = saved;
        }
        // Deliberately not restored: a held packet comes back lying where it fell. The hand is
        // not part of the level's state - it is the player's, and a resumed run starts with an
        // empty one (the packet itself is kept, so nothing the player earned is lost).
        held = false;
        falling = tag.getInt("Falling") != 0;
        landingY = tag.getFloat("LandingY");
    }
}
