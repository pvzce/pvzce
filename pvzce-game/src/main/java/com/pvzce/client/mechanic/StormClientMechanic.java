package com.pvzce.client.mechanic;

import com.pvzce.api.content.StormData;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.renderer.PvzceCamera;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.level.mechanic.StormState;
import com.pvzce.common.network.PacketByteBuf;

/**
 * 4-10's thunderstorm, on the client: a blacked-out stage that lightning keeps revealing, with
 * rain falling over it.
 *
 * <p>The fog's opposite number, and it is worth saying how they differ, because they look alike:
 * the fog <em>covers part of the board all the time</em> and the answer to "how dark is it here"
 * depends on where you are; a storm covers <em>everything, some of the time</em> and the answer
 * depends only on what the server last said. Both hide what stands where they are dark, through
 * the same test in the board's render loop, which is why a zombie that walks into the fog and a
 * zombie standing in the dark disappear the same way.
 *
 * <p>Three jobs:
 *
 * <ul>
 *   <li><b>Draw the darkening.</b> One quad over the <em>whole visible stage</em> - not the board:
 *       a storm is weather, and a curtain that stopped at the lawn's edge left the road, the house
 *       and the mower strip lit, which reads as a rectangle of night pasted onto the picture rather
 *       than as a storm over it. There is no separate flash sprite either: light <em>is</em> the
 *       absence of the dark, so the strike's own brightness curve is what the player sees.</li>
 *   <li><b>Hide what is in it.</b> Between the flickers nothing that moves is drawn. This is the
 *       level's whole idea - the player defends a lawn they cannot see - and it is deliberately
 *       the same "not drawn at all" the fog does, rather than a dimmed draw: a half-visible
 *       zombie is one the player will argue about.</li>
 *   <li><b>Be the weather.</b> Rain loops for as long as the storm is up, and thunder lands on
 *       the tick a strike begins. The original's 4-10 is the one level in the game with no
 *       background music for exactly this reason - the rain is the soundtrack - which is what an
 *       empty {@code music} block on the level file means.</li>
 * </ul>
 *
 * <p>The cycle comes from the server ({@link StormState.Wire}) and so does the brightness:
 * nothing here re-derives when the next strike is or what shape it has, so a paused, resumed or
 * sped-up run flickers on the server's clock rather than the client's.
 */
public final class StormClientMechanic implements ClientMechanic {
    /**
     * One line per phase, for {@code -Dpvzce.traceStorm}.
     *
     * <p>The storm is invisible when it fails: a mechanic the client never registered, an overlay
     * that was not built and a board that is dark for some other reason all look the same on
     * screen - a lawn nobody can see. That is the same family as {@code pvzce.traceMusic} and
     * {@code pvzce.traceEffects}: a state that only shows up as a picture needs a line of text.
     */
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("PVZCE/Storm");

    /** Above the board and below the HUD; the fog's own layer, since no level has both. */
    private static final float Z = 0.30F;
    /** Overdraw past the visible area, in cells, so no rounding leaves a lit seam at the edge. */
    private static final float SCREEN_OVERDRAW_CELLS = 0.5F;
    /** The dark's colour: a night sky rather than pure black, so water still reads as water. */
    private static final float DARK_R = 0.02F;
    private static final float DARK_G = 0.03F;
    private static final float DARK_B = 0.07F;

    /**
     * Whether this client has already seen a storm state from this level.
     *
     * <p>Needed because the level's first state is also the strike the board opens on, and the two
     * situations have to sound different: a level opens lit so the player can see the board they
     * are starting on, and that is not a strike anybody hears. Only a change of pulse is.
     */
    private boolean sawState;

    @Override
    public Identifier id() {
        return PvzceIds.MECHANIC_STORM;
    }

    @Override
    public void applySync(ClientLevel level, PacketByteBuf payload) {
        StormState.Wire previous = stateOf(level);
        StormState.Wire next = StormState.Wire.decode(payload);
        level.setMechanicState(PvzceIds.MECHANIC_STORM, next);
        StormData data = dataOf(level);
        // A strike begins at the first state with a new pulse number, and the pulse is what makes
        // "a new one" decidable: every flicker of a strike carries the same pulse, and the level's
        // opening strike is pulse zero with nobody there to hear it.
        boolean newStrike = sawState && (previous == null || previous.pulse() != next.pulse());
        if (data != null && newStrike && next.exposure() >= STRIKE_EXPOSURE) {
            level.effects().add(new com.pvzce.common.network.packet.EffectEventS2C(
                    "", 0F, 0F, PvzceSounds.EFFECT_THUNDER.toString(), 1F, 1F));
            trace("strike " + next.pulse(), data);
        }
        sawState = true;
    }

    /**
     * How bright the first state of a strike has to be before it is heard as one.
     *
     * <p>Not zero: a pattern may open at a low flicker and rise, and thunder that landed on the
     * dim start would come before the light it belongs to.
     */
    private static final float STRIKE_EXPOSURE = 0.9F;

    /** One line, when {@code -Dpvzce.traceStorm} is on. */
    private static void trace(String what, StormData data) {
        if (!Boolean.getBoolean("pvzce.traceStorm") || data == null) {
            return;
        }
        LOGGER.info("storm trace: {} (cycle {} ticks, strike {}, dark {})",
                what, data.intervalTicks(), data.flashTicks(), data.maxAlpha());
    }

    @Override
    public WorldOverlay createWorldOverlay(ClientLevel level) {
        StormData data = dataOf(level);
        if (data == null || data.maxAlpha() <= 0F) {
            return null;
        }
        return new Storm(data);
    }

    /**
     * The storm this level declared, or {@code null} when it has none.
     *
     * <p>Read from the level's own block rather than from the state slot: the block is what the
     * level file said and is what decides whether there is a storm at all, while the slot is only
     * where in the cycle it is.
     */
    public static StormData dataOf(ClientLevel level) {
        return level.mechanicData(PvzceIds.MECHANIC_STORM, StormData.class);
    }

    /** The strike and brightness the server last reported, or {@code null} before the first one. */
    public static StormState.Wire stateOf(ClientLevel level) {
        return level.mechanicStateOrNull(PvzceIds.MECHANIC_STORM, StormState.Wire.class);
    }

    /**
     * How dark the whole stage is at this instant, 0 when there is no storm or the lightning is
     * at its brightest.
     *
     * <p>Asked by the board's render loop for the same reason the fog is: the dark hides things
     * standing in it. A level with no storm answers zero, so every ordinary level pays one
     * dictionary lookup per drawn entity.
     */
    public static float darkness(ClientLevel level) {
        StormData data = dataOf(level);
        if (data == null || data.maxAlpha() <= 0F) {
            return 0F;
        }
        StormState.Wire state = stateOf(level);
        return state == null ? 0F : data.maxAlpha() * (1F - state.exposure());
    }

    /**
     * True when something standing on this board is inside the dark and is not to be drawn.
     *
     * <p>The storm's half of the hiding rule the board already asks the fog. It takes no position
     * on purpose - a storm has no edge, so where an entity is has nothing to do with whether it
     * can be seen.
     */
    public static boolean hides(ClientLevel level) {
        StormData data = dataOf(level);
        if (data == null || data.maxAlpha() < StormState.HIDING_CEILING) {
            return false;
        }
        StormState.Wire state = stateOf(level);
        return state != null && darkness(level) >= data.maxAlpha() * StormState.HIDE_FRACTION;
    }

    /** How loud the rain loops, under the sound effects and over nothing else. */
    private static final float RAIN_VOLUME = 0.55F;

    /**
     * Starts the level's rain, if this level is a storm.
     *
     * <p>Called from {@code PvzceClient.onLevelInit}, and not from the overlay's constructor:
     * overlays are rebuilt on a mutation that changes the board, and starting the weather from a
     * place that runs more than once per level is how a loop is started twice. The sound engine
     * refuses a restart of the event it is already looping, so this is belt and braces - but the
     * call belongs somewhere that means "a level began".
     */
    public static void startWeather(PvzceClient client) {
        StormData data = dataOf(client.level());
        if (data != null && data.maxAlpha() > 0F) {
            client.sound().playAmbient(PvzceSounds.AMBIENT_RAIN.toString(), RAIN_VOLUME);
        }
    }

    /** The darkening itself; one per level instance, holding the block it was made from. */
    private static final class Storm implements WorldOverlay {
        private final StormData data;
        /**
         * Which phase the last {@code pvzce.traceStorm} line was written for, or {@code null}
         * when none has been: the overlay's own "have I said anything yet" flag, which is also
         * what keeps the trace to one line per phase rather than one per frame.
         */
        private Boolean tracedLit;

        private Storm(StormData data) {
            this.data = data;
        }

        @Override
        public void render(PvzceClient client, PvzceCamera camera) {
            StormState.Wire state = stateOf(client.level());
            if (state == null) {
                if (tracedLit == null) {
                    tracedLit = Boolean.FALSE;
                    trace("overlay built, but no state has arrived yet", data);
                }
                return;
            }
            if (tracedLit == null) {
                trace("drawing the storm", data);
            }
            float dark = data.maxAlpha() * (1F - state.exposure());
            tracePhase(state, dark);
            if (dark <= 0.002F) {
                // Full light: nothing to draw. A strike's brightest instant is the one frame of a
                // storm that is exactly the board as it is without one.
                return;
            }
            // The whole viewport, not the board: the lawn is not the only thing out there - the
            // road zombies walk in on, the house, the mower strip and the sky above them are all
            // part of the picture, and a curtain that stopped at the lawn's edge left them lit.
            // `camera.worldLeft/Right/Bottom/Top` are the visible rectangle in cells, so this is
            // one quad covering exactly what the player can see, at any zoom.
            float left = camera.worldLeft() - SCREEN_OVERDRAW_CELLS;
            float bottom = camera.worldBottom() - SCREEN_OVERDRAW_CELLS;
            float width = camera.worldWidth() + SCREEN_OVERDRAW_CELLS * 2F;
            float height = camera.worldHeight() + SCREEN_OVERDRAW_CELLS * 2F;
            client.drawSolid(left, bottom, width, height, Z, DARK_R, DARK_G, DARK_B, dark);
        }

        /**
         * The light's own line, written when the board changes between lit and dark.
         *
         * <p>One line per phase rather than per frame: a strike flickers several times a second,
         * and a trace that printed sixty lines a second is one nobody reads. What it answers is
         * the question the picture cannot - "did the lightning actually happen, and when".
         */
        private void tracePhase(StormState.Wire state, float dark) {
            boolean lit = dark < data.maxAlpha() * 0.5F;
            if (tracedLit != null && lit == tracedLit) {
                return;
            }
            tracedLit = lit;
            trace((lit ? "lit" : "dark") + " at strike " + state.pulse()
                    + " (exposure " + state.exposure() + ", dark " + dark + ")", data);
        }
    }
}
