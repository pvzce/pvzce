package com.pvzce.client.renderer;

/**
 * Whether a resource drop on the board is drawn for the local player.
 *
 * <p>A drop belongs to a team, and only that team can pick it up. On a versus level the plant side's
 * sun keeps falling whether or not the player is the plant side, so a player on the zombie side used
 * to see suns lying on the lawn that no click of theirs could ever collect - drawn, that is a lie
 * about what is clickable, and it is the one thing on the board that looks exactly like something the
 * player should be doing.
 *
 * <p>Its own class rather than a condition inside the render loop so the rule can be tested without a
 * window: the renderer calls it with three strings, and everything interesting about it is in those
 * three strings.
 */
public final class PickupVisibility {
    private PickupVisibility() {
    }

    /**
     * True when this drop should be drawn.
     *
     * @param controlledTeam the id of the team the local player controls, or null/blank when nobody is
     *                       being controlled (the editor, a spectator) - in which case nothing is
     *                       hidden, because there is no "mine" to compare against
     * @param entityKind     the entity's kind, from {@code EntityKind}
     * @param dropTeam       the team the drop belongs to, or null for a drop that belongs to nobody
     */
    public static boolean isVisible(String controlledTeam, String entityKind, String dropTeam) {
        if (!com.pvzce.api.entity.EntityKind.RESOURCE.equals(entityKind)) {
            return true;
        }
        if (controlledTeam == null || controlledTeam.isBlank() || dropTeam == null) {
            return true;
        }
        return controlledTeam.equals(dropTeam);
    }
}
