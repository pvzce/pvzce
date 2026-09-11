package com.pvzce.client.gui.mods;

import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.Person;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Display model for one loaded mod (ModMenu {@code Mod} simplified). */
public record ModInfo(
        String id,
        String name,
        String description,
        String version,
        List<String> authors,
        List<String> license,
        Set<String> badges,
        Map<String, String> contact,
        boolean hidden,
        String parentId,
        String iconPath
) {
    public static ModInfo from(ModContainer container) {
        var metadata = container.getMetadata();
        List<String> authors = metadata.getAuthors().stream().map(Person::getName).toList();
        Set<String> badges = new LinkedHashSet<>();
        if (metadata.getId().startsWith("fabric") || "java".equals(metadata.getId())) {
            badges.add("library");
        }
        if (metadata.getEnvironment() == net.fabricmc.loader.api.metadata.ModEnvironment.CLIENT) {
            badges.add("client");
        }
        Map<String, String> contact = metadata.getContact().asMap();
        String parentId = container.getContainingMod().map(c -> c.getMetadata().getId()).orElse(null);
        String iconPath = metadata.getIconPath(128).orElse("assets/" + metadata.getId() + "/icon.png");
        return new ModInfo(
                metadata.getId(),
                metadata.getName(),
                metadata.getDescription(),
                metadata.getVersion().getFriendlyString(),
                authors,
                List.copyOf(metadata.getLicense()),
                badges,
                contact,
                false,
                parentId,
                iconPath
        );
    }

    public boolean matches(String search) {
        if (search == null || search.isBlank()) {
            return true;
        }
        String lower = search.toLowerCase();
        return id.toLowerCase().contains(lower)
                || name.toLowerCase().contains(lower)
                || description.toLowerCase().contains(lower)
                || String.join(" ", authors).toLowerCase().contains(lower);
    }
}
