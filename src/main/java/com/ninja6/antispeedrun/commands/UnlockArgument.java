package com.ninja6.antispeedrun.commands;

import java.util.Optional;

import com.ninja6.antispeedrun.storage.DimensionUnlock;

/**
 * What {@code /asr unlock <nether|end> [lock]} was actually asked to do.
 *
 * <p>Extracted from the executor for the usual reason in this package — {@code paper-api} is
 * {@code compileOnly}, so a decision left inside {@link AntiSpeedrunCommand} cannot be tested at
 * all — but here that matters more than usual. This subcommand is the one that writes durable,
 * server-wide state, and the two things it can do are exact opposites.
 *
 * <h2>An unrecognised third word is a failure, never a default</h2>
 *
 * The tempting form is {@code boolean relock = args.length >= 3 && "lock".equals(args[2])}, which
 * quietly treats every word that is not {@code lock} as if it were absent. {@code /asr unlock end
 * lcok} then opens The End to the entire server — the exact opposite of what was typed — persists
 * it, and reports success. {@link Reason#UNKNOWN_OPTION} exists so that path is impossible.
 */
public sealed interface UnlockArgument {

    /** Open {@code dimension} to everyone. */
    record Open(DimensionUnlock dimension) implements UnlockArgument {
    }

    /** Close {@code dimension} again, returning it to normal progression gating. */
    record Close(DimensionUnlock dimension) implements UnlockArgument {
    }

    /**
     * The arguments do not name an action.
     *
     * @param reason what was wrong
     * @param token  the offending word, or empty when there was none to point at
     */
    record Invalid(Reason reason, String token) implements UnlockArgument {
    }

    /** Why a parse failed. Each maps to its own message, so the operator is told which mistake. */
    enum Reason {

        /** No dimension was given at all. */
        MISSING_DIMENSION,

        /** The second word names no dimension this plugin gates. */
        UNKNOWN_DIMENSION,

        /** The third word is not {@code lock}, and is not assumed to mean anything else. */
        UNKNOWN_OPTION
    }

    /**
     * Parses the whole argument array, {@code args[0]} being the {@code unlock} label itself.
     *
     * @return an {@link Open}, a {@link Close}, or an {@link Invalid} naming what to tell the
     *         operator; never {@code null}
     */
    static UnlockArgument parse(String[] args) {
        if (args == null || args.length < 2) {
            return new Invalid(Reason.MISSING_DIMENSION, "");
        }
        Optional<DimensionUnlock> dimension = DimensionUnlock.parse(args[1]);
        if (dimension.isEmpty()) {
            return new Invalid(Reason.UNKNOWN_DIMENSION, args[1]);
        }
        if (args.length == 2) {
            return new Open(dimension.get());
        }
        if (args.length == 3 && "lock".equalsIgnoreCase(args[2].trim())) {
            return new Close(dimension.get());
        }
        // Also catches a fourth word: "/asr unlock end lock please" is not a close instruction
        // with a pleasantry, it is a command the operator does not think they are typing.
        return new Invalid(Reason.UNKNOWN_OPTION, args[2]);
    }
}
