package com.ninja6.antispeedrun.commands;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import com.ninja6.antispeedrun.config.PluginConfig.Profile;
import com.ninja6.antispeedrun.storage.DimensionUnlock;
import com.ninja6.antispeedrun.storage.ProfileApplier;

/**
 * Tab completion for {@code /antispeedrun}, as a pure function of the arguments typed so far.
 *
 * <p>Separated from the executor because completion is where an admin command leaks: the naive
 * implementation offers every subcommand and every online player's name to anyone who presses tab,
 * so a player with no permissions learns the command surface and, worse, gets a player list from a
 * server that hides one. {@link #complete} therefore takes the same permission predicate the
 * executor enforces and filters the first argument through it, and offers player names only under a
 * subcommand the sender may actually run.
 *
 * <p>Being Bukkit-free, all of that is asserted in unit tests rather than trusted.
 */
public final class CommandCompletion {

    private CommandCompletion() {
    }

    /**
     * The completions for {@code args}.
     *
     * @param args        the arguments typed so far, as Bukkit hands them over: the last element is
     *                    the partial word being completed and may be empty
     * @param permitted   whether the sender holds a subcommand's permission
     * @param playerNames names of the players the sender may be offered, already filtered for
     *                    visibility by the caller
     * @return matching completions, prefix-filtered case-insensitively; never null
     */
    public static List<String> complete(String[] args, Predicate<Subcommand> permitted,
                                        Collection<String> playerNames) {
        Objects.requireNonNull(permitted, "permitted");
        Objects.requireNonNull(playerNames, "playerNames");
        if (args == null || args.length == 0) {
            return List.of();
        }

        String partial = args[args.length - 1];
        if (args.length == 1) {
            return filter(labelsFor(permitted), partial);
        }

        Optional<Subcommand> subcommand = Subcommand.parse(args[0]);
        if (subcommand.isEmpty() || !permitted.test(subcommand.get())) {
            // Nothing is offered under a subcommand the sender cannot run, and nothing is offered
            // under one that does not exist -- otherwise a typo silently completes as if it did.
            return List.of();
        }

        return switch (subcommand.get()) {
            case RELOAD -> List.of();
            case PROFILE -> filter(profileArguments(args), partial);
            case UNLOCK -> filter(unlockArguments(args), partial);
            case BYPASS -> filter(bypassArguments(args, playerNames), partial);
            case INSPECT -> args.length == 2 ? filter(playerNames, partial) : List.of();
        };
    }

    private static List<String> labelsFor(Predicate<Subcommand> permitted) {
        List<String> labels = new ArrayList<>(Subcommand.values().length);
        for (Subcommand subcommand : Subcommand.values()) {
            if (permitted.test(subcommand)) {
                labels.add(subcommand.label());
            }
        }
        return labels;
    }

    private static List<String> profileArguments(String[] args) {
        if (args.length == 2) {
            return List.of("apply");
        }
        if (args.length == 3 && "apply".equalsIgnoreCase(args[1])) {
            List<String> names = new ArrayList<>();
            for (Profile profile : ProfileApplier.applicable()) {
                names.add(profile.name());
            }
            return names;
        }
        return List.of();
    }

    private static List<String> unlockArguments(String[] args) {
        if (args.length == 2) {
            return DimensionUnlock.arguments();
        }
        // A second word re-locks the dimension. Offering it only after a valid dimension keeps the
        // destructive half of the subcommand out of the way of the common case.
        if (args.length == 3 && DimensionUnlock.parse(args[1]).isPresent()) {
            return List.of("lock");
        }
        return List.of();
    }

    private static Collection<String> bypassArguments(String[] args, Collection<String> playerNames) {
        if (args.length == 2) {
            return playerNames;
        }
        return args.length == 3 ? BypassDuration.suggestions() : List.of();
    }

    private static List<String> filter(Collection<String> candidates, String partial) {
        String prefix = partial == null ? "" : partial.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>(candidates.size());
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(candidate);
            }
        }
        return List.copyOf(matches);
    }
}
