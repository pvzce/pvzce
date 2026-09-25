package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.api.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Vases standing on the lawn when the level starts, each holding a card.
 *
 * <p>The block of the {@code pvzce:vase_field} level mechanic, and 4-4's opening board: the
 * level's own plants are all inside vases, so the player breaks them open to arm up. It is a
 * mechanic rather than part of the level's {@code scene} map because a vase is not only a
 * picture - what is <em>inside</em> it has to be written down somewhere, and the scene map
 * holds element ids and nothing else.
 *
 * <pre>
 * { "type": "pvzce:vase_field",
 *   "vases": [ { "x": 2, "y": 0, "card": "pvzce:peashooter" },
 *              { "x": 3, "y": 4, "card": "pvzce:sunflower" } ] }
 * </pre>
 *
 * <p>Placed rather than scattered: unlike a gravestone field, whose whole point is that the
 * lawn is different every time, a vase the level hands out is a <em>gift</em>, and a gift that
 * lands in a different place every attempt is a level whose difficulty is a dice roll. Written
 * work is also what makes the board authorable at all - "put the peashooter where the player
 * needs it" is a decision, not a random draw.
 *
 * <p>A cell that already holds a vase in the level's own {@code scene} block is the same thing
 * written twice; the mechanic writes the cell itself, so a level only has to name the vases
 * here.
 *
 * @param vases the vases to stand up, in the order they are written
 */
public record VaseFieldData(List<Vase> vases) implements MechanicData {
    public static final MapCodec<VaseFieldData> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Vase.CODEC.listOf().optionalFieldOf("vases", List.of()).forGetter(VaseFieldData::vases)
    ).apply(i, VaseFieldData::new));

    public static final Codec<VaseFieldData> CODEC = MAP_CODEC.codec();

    public VaseFieldData {
        vases = vases == null ? List.of() : List.copyOf(vases);
    }

    /**
     * Every problem with this block that can be seen from the shape alone: vases off the board,
     * and two vases written for one cell.
     *
     * <p>Whether the card inside resolves is not asked here - card resolution lives a layer up,
     * and the mechanic's own {@code validate} asks it.
     */
    public List<String> validate(int width, int height) {
        List<String> errors = new ArrayList<>();
        List<Long> seen = new ArrayList<>();
        for (Vase vase : vases) {
            if (vase.x() < 0 || vase.x() >= width || vase.y() < 0 || vase.y() >= height) {
                errors.add("vase at (" + vase.x() + "," + vase.y() + ") is outside the "
                        + width + "x" + height + " board");
                continue;
            }
            long key = ((long) vase.x() << 32) | (vase.y() & 0xFFFFFFFFL);
            if (seen.contains(key)) {
                errors.add("two vases are written for cell (" + vase.x() + "," + vase.y() + ")");
            }
            seen.add(key);
        }
        return errors;
    }

    /**
     * One vase: where it stands and what is inside it.
     *
     * @param x    its column
     * @param y    its row
     * @param card the card inside, as a card id ({@code pvzce:peashooter}) rather than a plant id -
     *             a broken vase hands the player a <em>card</em>, and a tool in a vase is a
     *             perfectly good reward
     */
    public record Vase(int x, int y, Identifier card) {
        public static final Codec<Vase> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("x").forGetter(Vase::x),
                Codec.INT.fieldOf("y").forGetter(Vase::y),
                Identifier.CODEC.fieldOf("card").forGetter(Vase::card)
        ).apply(i, Vase::new));
    }
}
