package com.pvzce.server.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.PvzceTags;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** MC ResourceLocation-like argument: parses {@code namespace:path} and suggests registry ids. */
public final class IdentifierArgumentType implements ArgumentType<Identifier> {
    private static final SimpleCommandExceptionType INVALID =
            new SimpleCommandExceptionType(new com.mojang.brigadier.LiteralMessage("Invalid identifier"));

    private final Supplier<Collection<Identifier>> suggestions;

    private IdentifierArgumentType(Supplier<Collection<Identifier>> suggestions) {
        this.suggestions = suggestions;
    }

    public static IdentifierArgumentType identifier(Supplier<Collection<Identifier>> suggestions) {
        return new IdentifierArgumentType(suggestions);
    }

    /**
     * Suggestion source for a category name.
     *
     * <p>Registry categories resolve through
     * {@link com.pvzce.common.core.PvzceRegistries#byCategory()} (the same table the
     * command tree builds its argument lists from), so a new registry is
     * autocompleted everywhere without editing a second list here. Only the three
     * synthetic categories - {@code team}, {@code tag} and {@code any_entity} - are
     * handled locally.
     */
    public static IdentifierArgumentType forCategory(String category) {
        String canonical = com.pvzce.common.core.PvzceRegistries.canonicalCategory(category);
        Supplier<Collection<Identifier>> supplier = switch (canonical) {
            case "team" -> () -> List.of(PvzceIds.PLANT_TEAM, PvzceIds.ZOMBIE_TEAM);
            case "tag" -> () -> {
                List<Identifier> ids = new ArrayList<>();
                for (var key : PvzceTags.keys()) {
                    ids.add(key.id());
                }
                return ids;
            };
            case "any_entity" -> () -> {
                List<Identifier> ids = new ArrayList<>(BuiltInRegistries.PLANTS.keySet());
                ids.addAll(BuiltInRegistries.ZOMBIES.keySet());
                ids.addAll(BuiltInRegistries.PROJECTILES.keySet());
                return ids;
            };
            default -> registrySuggestions(canonical);
        };
        return new IdentifierArgumentType(supplier);
    }

    /** Suggestions from a data-driven registry, or none for an unknown category. */
    @SuppressWarnings("unchecked")
    private static Supplier<Collection<Identifier>> registrySuggestions(String canonical) {
        var key = com.pvzce.common.core.PvzceRegistries.byCategory().get(canonical);
        if (key == null) {
            return List::of;
        }
        com.pvzce.api.registry.ResourceKey<com.pvzce.api.registry.Registry<Object>> typedKey =
                (com.pvzce.api.registry.ResourceKey<com.pvzce.api.registry.Registry<Object>>) (Object) key;
        return () -> {
            var registry = BuiltInRegistries.ACCESS.get(typedKey);
            return registry == null ? List.of() : new ArrayList<>(registry.keySet());
        };
    }

    @Override
    public Identifier parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
            reader.skip();
        }
        String text = reader.getString().substring(start, reader.getCursor());
        Identifier id = Identifier.tryParse(text);
        if (id == null) {
            reader.setCursor(start);
            throw INVALID.createWithContext(reader);
        }
        return id;
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        for (Identifier id : suggestions.get()) {
            if (id.toString().startsWith(builder.getRemaining())) {
                builder.suggest(id.toString());
            }
        }
        return builder.buildFuture();
    }

    @Override
    public Collection<String> getExamples() {
        return List.of("pvzce:demo_level", "pvzce:basic_zombie");
    }
}
