package com.ninja6.antispeedrun.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The grammar of {@code /asr cooldown <show|clear> <player>} (#272), parsed without a server so it
 * can be tested. The player is resolved by the command, as {@code credit} resolves it.
 */
sealed interface CooldownArgument {

    /** What to do with the cooldown. */
    enum Action {
        SHOW, CLEAR;

        /** The words, in completion order. */
        static List<String> labels() {
            List<String> labels = new ArrayList<>();
            for (Action action : values()) {
                labels.add(action.name().toLowerCase(Locale.ROOT));
            }
            return List.copyOf(labels);
        }

        static Optional<Action> parse(String word) {
            String normalised = word.trim().toLowerCase(Locale.ROOT);
            for (Action action : values()) {
                if (action.name().toLowerCase(Locale.ROOT).equals(normalised)) {
                    return Optional.of(action);
                }
            }
            return Optional.empty();
        }
    }

    /** A well-formed request. */
    record Request(Action action, String target) implements CooldownArgument {
    }

    /** Not a request; {@code token} is the offending word, or empty when one is missing. */
    record Invalid(Reason reason, String token) implements CooldownArgument {
    }

    /** Why a parse failed. */
    enum Reason {
        MISSING_ACTION, UNKNOWN_ACTION, MISSING_PLAYER, EXTRA_ARGUMENT
    }

    /** Parses {@code args}, whose first word is the subcommand itself. */
    static CooldownArgument parse(String[] args) {
        if (args.length < 2 || args[1].isBlank()) {
            return new Invalid(Reason.MISSING_ACTION, "");
        }
        Optional<Action> action = Action.parse(args[1]);
        if (action.isEmpty()) {
            return new Invalid(Reason.UNKNOWN_ACTION, args[1]);
        }
        if (args.length < 3 || args[2].isBlank()) {
            return new Invalid(Reason.MISSING_PLAYER, "");
        }
        if (args.length > 3) {
            return new Invalid(Reason.EXTRA_ARGUMENT, args[3]);
        }
        return new Request(action.get(), args[2].trim());
    }
}
