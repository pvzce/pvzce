package com.pvzce.client.api;

/**
 * An {@link Animatable} that has a ground speed.
 *
 * <p>Locomotion is the one clip speed the engine derives rather than reads: a walk or flight
 * cycle is drawn so that the planted foot stays put, which makes the cycle's own length a
 * statement about how fast the creature was travelling when it was drawn. Playing that cycle at
 * a different speed than the creature moves is what "the feet slide" means, so the playback is
 * scaled by the ratio between the two - see {@code AnimationPlayback#measureLocomotion}.
 *
 * <p>A separate interface rather than a method on {@link Animatable} because most animatables do
 * not move: a level mechanic's prop, a UI preview and a resource drop all play clips and have no
 * ground speed, and giving them a "position" that nothing reads would be inventing an answer for
 * them. It is also what makes the difference testable - a playback can be handed a target that
 * moves without a GL context or a level.
 *
 * <p>The position reported is the <em>drawn</em> one (the same value the sprite and its shadow
 * are placed with), not the authoritative simulation position: those differ between server
 * updates, and it is the drawn position whose motion the feet have to match.
 */
public interface MovingTarget extends Animatable {
    /** Where this target is drawn, in world cells, x right and y up. */
    float drawnX();

    /** Where this target is drawn, in world cells. */
    float drawnY();
}
