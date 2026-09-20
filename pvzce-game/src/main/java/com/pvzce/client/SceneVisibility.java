package com.pvzce.client;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.PvzceRegistries;
import com.pvzce.common.tag.PvzceTags;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which scene elements a level does not draw.
 *
 * <p>A level whose backdrop already has the lawn painted on it still has to paint the terrain
 * for the simulation - that is what decides where a plant may go - but drawing it as well puts
 * a second lawn on top of the first. Such a level lists what not to draw, one entry per line:
 *
 * <pre>{@code
 * "hidden_scene_elements": ["pvzce:grass"]      // this element
 * "hidden_scene_elements": ["#pvzce:lawn"]      // every element carrying this tag
 * }</pre>
 *
 * <p>Both spellings are accepted on purpose. A tag is how a pack says "these are all the same
 * kind of thing" and lets one line cover tiles the level has never heard of; an id is what an
 * author can write in the editor without shipping a data pack first.
 *
 * <p>An entry that names nothing - a typo, or a tag no pack declares - hides nothing rather
 * than everything, which is the failure a level author can actually see: the lawn is still
 * there, and the id they typed is not in any tag file.
 */
public final class SceneVisibility {
    /** Draws the whole board. */
    public static final SceneVisibility NONE = new SceneVisibility(List.of());

    private final Set<Identifier> elements = new LinkedHashSet<>();
    private final Set<Identifier> tags = new LinkedHashSet<>();

    private SceneVisibility(List<String> declared) {
        for (String entry : declared) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String trimmed = entry.trim();
            boolean isTag = trimmed.startsWith("#");
            Identifier id = Identifier.tryParse(isTag ? trimmed.substring(1) : trimmed);
            if (id == null) {
                continue;
            }
            (isTag ? tags : elements).add(id);
        }
    }

    /** The visibility a level's own {@code hidden_scene_elements} list describes. */
    public static SceneVisibility of(List<String> declared) {
        return declared == null || declared.isEmpty() ? NONE : new SceneVisibility(declared);
    }

    /** True when this element is one the level does not draw. */
    public boolean hides(String elementId) {
        if (elementId == null || (elements.isEmpty() && tags.isEmpty())) {
            return false;
        }
        Identifier id = Identifier.tryParse(elementId);
        if (id == null) {
            return false;
        }
        if (elements.contains(id)) {
            return true;
        }
        for (Identifier tag : tags) {
            if (PvzceTags.contains(PvzceTags.key(PvzceRegistries.SCENE_ELEMENTS, tag), id)) {
                return true;
            }
        }
        return false;
    }
}
