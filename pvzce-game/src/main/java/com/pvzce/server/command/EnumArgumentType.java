package com.pvzce.server.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** A word argument with fixed tab suggestions (kind/category enums). */
public final class EnumArgumentType implements ArgumentType<String> {
    private final List<String> values;

    private EnumArgumentType(List<String> values) {
        this.values = values;
    }

    public static EnumArgumentType of(String... values) {
        return new EnumArgumentType(List.of(values));
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        return reader.readUnquotedString();
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        for (String value : values) {
            if (value.startsWith(builder.getRemaining())) {
                builder.suggest(value);
            }
        }
        return builder.buildFuture();
    }

    @Override
    public Collection<String> getExamples() {
        return values;
    }
}
