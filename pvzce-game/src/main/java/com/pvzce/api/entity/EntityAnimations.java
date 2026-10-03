package com.pvzce.api.entity;

/**
 * Animation state names published by the server and played by the client.
 * These strings are a wire contract and are also the keys used by
 * {@code AttackAnimations} / {@code AnimationBindings} overrides in entity JSON.
 */
public final class EntityAnimations {
    public static final String IDLE = "idle";
    /** A permanently filled carrier, also published in the normal entity snapshot. */
    public static final String IDLE_WATER = "idle_water";
    public static final String WALK = "walk";
    public static final String EAT = "eat";
    public static final String SHOOT = "shoot";
    /**
     * A plant hitting something that is standing on it, rather than shooting at it.
     *
     * <p>Its own state because it is its own gesture: the spikeweed has no target and no
     * projectile, and asking for {@code shoot} would play whatever the plant's shooting clip
     * happens to be - or, for a plant that has none, silently fall back to {@code idle} and make
     * the stab invisible. See {@code SpikeCapability}.
     */
    public static final String ATTACK = "attack";
    public static final String PRODUCE = "produce";
    public static final String CHEW = "chew";
    public static final String EXPLODE = "explode";
    public static final String GROW = "grow";
    /**
     * The glance a leaping plant gives the zombie that tripped it, one clip per side.
     *
     * <p>Two states rather than one with a mirror flag: the original draws the squash turning its
     * eyes left or right as two animations, and the art for both is in the file. The server picks
     * by which side the target is on (see {@code SquashCapability}).
     */
    public static final String LOOK_LEFT = "look_left";
    public static final String LOOK_RIGHT = "look_right";
    public static final String ARMED = "armed";
    /**
     * The pose an armed mine holds while it waits.
     *
     * <p>{@link #ARMED} is the *emergence* - a one-shot that ends on this - and the two are
     * separate states because a mine waits for a zombie for up to fifteen seconds: asking for
     * the emergence again on every tick restarts it every time its clip hands over, which is
     * a potato mine that pops out of the ground over and over.
     */
    public static final String ARMED_LOOP = "armed_loop";
    /**
     * A hurt pose, kept for packs written against it.
     *
     * <p>The shipped zombies have no hurt animation and the server never publishes this: the
     * feedback for taking a hit is the impact particle and the sound, and the legs keep
     * walking, which is what the original does. A definition may still map this state to a
     * clip of its own, and a clip named {@code hit} is still perfectly readable.
     */
    public static final String HIT = "hit";
    /**
     * A shot a torchwood has lit.
     *
     * <p>A visual state, not a behaviour one: the damage was already applied by the time this is
     * set, and what the name buys is the fire pea the client draws in place of the pea. The
     * binding lives on the projectile's own definition ({@code pea.json}'s
     * {@code animations.lit}), so a projectile that has no burning art keeps whatever it had.
     */
    public static final String LIT = "lit";
    public static final String ANGRY = "angry";
    public static final String DEATH = "death";
    /**
     * A death that leaves a charred body: the ash line, a fire hit.
     *
     * <p>A separate state rather than a flag inside {@link #DEATH} because the art is a
     * different model - the original draws the burnt corpse from {@code Zombie_charred.reanim},
     * which shares no bones with the zombie it used to be. The state name is the wire string and
     * the clip name, so a definition opts in by mapping this state to the charred file
     * ({@code "animations": {"death_burned": "pvzce:zombie/charred/zombie_charred"}}); a zombie
     * whose art has no such clip falls back to its own {@code idle}, the same silent fallback
     * every other unknown clip gets.
     */
    public static final String DEATH_BURNED = "death_burned";
    /**
     * The original's other way of falling over, as a clip name inside the same file.
     *
     * <p>{@code Zombie.reanim} carries three death sequences and the game picks one at
     * random when a zombie dies ({@code Zombie::PlayDeathAnim}), so a lane of dying zombies
     * does not fall in unison. Which one a body gets is the <em>client's</em> choice among
     * the death clips its art actually defines: the server publishes {@link #DEATH} and
     * nothing else, because only the client can see the file. A state the file does not
     * define falls back to {@code idle} - a corpse standing about instead of falling over -
     * so the pool is exactly the clips that are there.
     */
    public static final String DEATH2 = "death2";
    /**
     * The drawn-out death a heavy body gets.
     *
     * <p>Not part of the client's random pool: it is a different kind of death rather than a
     * variation on an ordinary one, so a definition that wants it points its {@code death}
     * state at the clip's own file ({@code "death": "pvzce:zombie/giant/gargantuar"}).
     */
    public static final String DEATH_SUPERLONG = "death_superlong";
    /**
     * Drowning: the original's fourth death sequence, played when a body goes under water.
     *
     * <p>A state rather than a flag because it is a whole different sequence, not a tint on
     * the ordinary one. Art without the clip drowns with its ordinary death: the client
     * treats this as a member of the death family, so a zombie whose file only drew
     * {@code death} sinks with {@code death} instead of standing about in {@code idle}.
     */
    public static final String DEATH_WATER = "death_water";
    public static final String FALL = "fall";
    public static final String FLY = "fly";
    public static final String DIG = "dig";
    public static final String DIG_EXIT = "dig_exit";
    public static final String JUMP = "jump";
    /**
     * A zombie walking while it still carries what it walks with.
     *
     * <p>The pole vaulter's approach: the original draws it jogging with the pole held out
     * ({@code anim_run}), then vaulting, then walking empty-handed. Without this it walked
     * its *post-vault* clip from the start, pole already gone.
     */
    public static final String RUN = "run";
    public static final String HAMMER = "hammer";
    public static final String LANDED = "landed";
    /** A plant that is moving under its own power (bowling Wall-nut). */
    public static final String ROLL = "roll";
    /**
     * A nocturnal plant asleep in daylight (Puff-shroom, Doom-shroom).
     *
     * <p>Like every other state this is a wire string and a clip name: the art has to
     * define a {@code sleep} clip, or the client silently falls back to {@code idle} and
     * a sleeping mushroom looks exactly like a wide-awake one.
     */
    public static final String SLEEP = "sleep";
    /**
     * A plant that has ducked behind its own cap because something is close.
     *
     * <p>The scaredy-shroom. Like the potato mine's arming it is two clips - a one-shot that
     * puts the head down and a loop that holds it there - and the model chains them itself
     * ({@code on_end}), so this is the only state either side publishes.
     */
    public static final String HIDE = "hide";
    /**
     * A zombie's fast entrance gait: the dancing zombie's moonwalk.
     *
     * <p>A clip name and a wire string like every other state here. It is separate from
     * {@link #WALK} because it is a <em>different</em> gait at a different speed - the
     * original draws the moonwalk for the approach and the ordinary dance step once the
     * dancers are out, which is the same pair of facts the pole vaulter's {@link #RUN} and
     * {@code walk} are.
     */
    public static final String MOONWALK = "moonwalk";
    /**
     * The pose a zombie strikes to call its backup.
     *
     * <p>The dancing zombie's summon. A named state rather than a bare string because the
     * clip it plays is part of the content contract: a definition whose art has no
     * {@code armraise} clip silently falls back to {@code idle}, and this is the name it
     * would be missing.
     */
    public static final String ARM_RAISE = "armraise";

    /**
     * The bungee zombie's four beats.
     *
     * <p>One state per mask in its own reanim, which is why they are named rather than folded into
     * an existing one: the file draws a descent, a grab, a hanging hold and an ascent, and the
     * capability publishes exactly those four as it runs them. A missing clip would fall back to
     * {@code idle} and the whole raid would look like a zombie hanging in the sky.
     */
    public static final String BUNGEE_DROP = "bungee_drop";
    public static final String BUNGEE_GRAB = "bungee_grab";
    public static final String BUNGEE_HOLD = "bungee_hold";
    public static final String BUNGEE_RISE = "bungee_rise";

    /** The zamboni's crunch: the original's two wheelie poses. */
    public static final String DRIVE = "drive";
    public static final String WHEELIE = "wheelie";

    /**
     * A pogo zombie up on its stick.
     *
     * <p>A named state rather than a bare string for the same reason the bungee's four are: the
     * server publishes it for the whole of every bounce, and a file without the clip falls back
     * to {@code idle} - which is a zombie standing still while the simulation sails it across a
     * plant. The original keeps it in its own model ({@code Zombie_pogo.reanim}), where the
     * stick is drawn for the eleven frames of this mask and for no other.
     */
    public static final String POGO = "pogo";

    /**
     * The jack-in-the-box's lid coming up.
     *
     * <p>Its own state because it is its own event: the zombie stands still for the whole of it
     * (see {@code JackInTheBoxCapability}), and the file's {@code anim_pop} mask is the lid, the
     * spring and the clown's head - art that appears in no other clip. A definition missing it
     * falls back to {@code idle}, which is the explosion arriving out of a body that never moved.
     */
    public static final String POP = "pop";

    private EntityAnimations() {
    }
}
