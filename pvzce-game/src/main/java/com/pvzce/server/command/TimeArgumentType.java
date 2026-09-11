package com.pvzce.server.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * MC {@code TimeArgument}-shaped time parser for 60tps PVZCE:
 * {@code <ticks>[t|s|d]}, where 1s = 60 ticks and 1d = 86400 ticks.
 */
public final class TimeArgumentType implements ArgumentType<Integer> {
    private static final SimpleCommandExceptionType INVALID =
            new SimpleCommandExceptionType(new com.mojang.brigadier.LiteralMessage("Invalid time"));
    private static final Collection<String> EXAMPLES = Arrays.asList("1t", "1s", "1d");

    private final int minimum;

    private TimeArgumentType(int minimum) {
        this.minimum = minimum;
    }

    public static TimeArgumentType time() {
        return new TimeArgumentType(0);
    }

    public static TimeArgumentType time(int minimum) {
        return new TimeArgumentType(minimum);
    }

    @Override
    public Integer parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        while (reader.canRead() && (Character.isDigit(reader.peek()) || (reader.peek() == '-' && reader.getCursor() == start))) {
            reader.skip();
        }
        int numberStart = reader.getCursor();
        while (reader.canRead() && Character.isDigit(reader.peek())) {
            reader.skip();
        }
        String numberText = reader.getString().substring(start, numberStart);
        int multiplier = 1;
        if (reader.canRead()) {
            char suffix = Character.toLowerCase(reader.peek());
            switch (suffix) {
                case 't' -> {
                    reader.skip();
                    multiplier = 1;
                }
                case 's' -> {
                    reader.skip();
                    multiplier = 60;
                }
                case 'd' -> {
                    reader.skip();
                    multiplier = 86_400;
                }
                default -> {
                    reader.setCursor(start);
                    throw INVALID.createWithContext(reader);
                }
            }
        }
        if (numberText.isEmpty()) {
            reader.setCursor(start);
            throw INVALID.createWithContext(reader);
        }
        int value;
        try {
            value = Math.multiplyExact(Integer.parseInt(numberText), multiplier);
        } catch (ArithmeticException e) {
            reader.setCursor(start);
            throw INVALID.createWithContext(reader);
        }
        if (value < minimum) {
            reader.setCursor(start);
            throw INVALID.createWithContext(reader);
        }
        return value;
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        for (String example : EXAMPLES) {
            if (example.startsWith(builder.getRemainingLowerCase())) {
                builder.suggest(example);
            }
        }
        return builder.buildFuture();
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}
