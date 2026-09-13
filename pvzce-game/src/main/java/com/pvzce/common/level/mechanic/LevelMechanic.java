package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.level.LevelServer;

import java.util.List;

/**
 * One registered level mechanic: the opt-in behaviour a level declares in its
 * {@code "mechanics"} list.
 *
 * <p>Modelled after the capability system one layer up: a mechanic owns a JSON block
 * ({@link #codec()}), is looked up by id, and may plug into several layers of the game.
 * Unlike a capability it is <em>level-scoped</em> - there is one instance per level, not
 * one per entity - which is why the hooks below take a {@link LevelServer} and the
 * mechanic's own decoded data rather than being methods on the data itself.
 *
 * <p>Before this existed, "a level that is not an ordinary level" was three named fields
 * on {@link LevelDef} ({@code belt}, {@code placementZone}) plus five {@code belt != null}
 * branches inside {@code LevelServer} and two more on the client. Adding a fourth
 * mini-game meant adding a fifth field and finding every branch. Now a level lists its
 * mechanics and the level definition never grows again.
 *
 * <p>Layering: this interface lives in {@code common} rather than in {@code api} because
 * its hooks speak of {@code LevelServer}, exactly like the built-in capability
 * implementations in {@code common.capability}. Only the data half
 * ({@link MechanicData}, {@link com.pvzce.api.content.mechanic.TypedMechanic}) lives in
 * {@code api}, so {@link LevelDef} can hold a mechanic without depending on the server.
 *
 * <p>Client presentation is registered separately, by id, in
 * {@code com.pvzce.client.mechanic.ClientMechanic}: the client is a layer above this one
 * and must not be referenced from here.
 *
 * @param <D> the mechanic's own data block
 */
public interface LevelMechanic<D extends MechanicData> {
    /** The JSON block, decoded flat: {@code {"type": "pvzce:conveyor", "capacity": 6, ...}}. */
    MapCodec<D> codec();

    /**
     * True when this mechanic decides where the player's cards come from.
     *
     * <p>Card sources are mutually exclusive - two of them would be two answers to "what
     * is in the card bar" - and a level that declares none gets
     * {@link DeckMechanic the ordinary deck}. This is the one hard-coded exclusivity rule;
     * there is deliberately no general {@code excludes()} declaration yet.
     */
    default boolean cardSource() {
        return false;
    }

    /**
     * True when this card source deals the cards itself: no seed chooser, and no prices.
     *
     * <p>Needed on the mechanic rather than only on the {@code CardSource} because the
     * question is asked before a level instance exists - the level list decides which
     * screen to open, and the server decides whether to sanitise a seed selection it is
     * about to throw away.
     */
    default boolean dealsItsOwnCards() {
        return false;
    }

    /**
     * Builds the card bar this mechanic drives, or {@code null} when it is not a card
     * source.
     *
     * <p>Returning the implementation rather than naming a class is what makes a new way
     * of dealing cards a single registration: the server asks the level's card source
     * mechanic for an object and never learns what kind it is.
     */
    default com.pvzce.server.level.cardsource.CardSource createCardSource(
            com.pvzce.server.level.cardsource.CardSource.Context context, D data) {
        return null;
    }

    /**
     * Reports every problem with this mechanic's block, one message per problem.
     *
     * <p>Called from {@code LevelValidator}, so a bad mechanic is reported next to every
     * other problem in the level instead of throwing somewhere in the simulation.
     */
    default List<String> validate(LevelDef def, D data) {
        return List.of();
    }

    /**
     * What an editor may change, declared as plain data.
     *
     * <p>An empty list means "this mechanic has no UI yet" - which is a supported state,
     * not an error: the level file can still be edited by hand, and the editor leaves the
     * block untouched. See {@link FieldSpec}.
     */
    default List<FieldSpec> editorFields() {
        return List.of();
    }

    // ------------------------------------------------------------------
    // Server hooks. All of them are optional; a mechanic overrides what it needs.
    // ------------------------------------------------------------------

    /** Runs once, when the level instance is constructed. */
    default void onLevelCreated(LevelServer level, D data) {
    }

    /** Runs once per server tick, before entities move. */
    default void tick(LevelServer level, D data) {
    }

    /**
     * A veto on planting, layered on top of terrain and stacking.
     *
     * <p>Returning false must mean "this mechanic does not allow it here"; the ordinary
     * placement rules are checked separately by {@code LevelServer.canPlacePlant}. The
     * plantable area uses this - it is a property of the level, not of the plant or the
     * tile, which is why a red line painted over grass cannot be a terrain tag.
     */
    default boolean canPlacePlant(LevelServer level, D data, PlantDef def, int x, int y) {
        return true;
    }

    /** Writes this mechanic's run state into the level's save tag. */
    default void collectSave(LevelServer level, D data, CompoundTag root) {
    }

    /** Reads back what {@link #collectSave} wrote. Must tolerate a missing block. */
    default void applySave(LevelServer level, D data, CompoundTag root) {
    }
}
