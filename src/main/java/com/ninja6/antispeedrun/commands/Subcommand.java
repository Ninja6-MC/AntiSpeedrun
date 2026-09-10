package com.ninja6.antispeedrun.commands;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The {@code /antispeedrun} subcommands, each paired with the permission node that gates it and the
 * usage line shown when it is misused.
 *
 * <p>Kept as an enum, free of Bukkit, for two reasons. It is the single place the permission
 * mapping lives, so a subcommand cannot be added without one — the ordinary way an admin command
 * ends up with an unprotected branch. And it makes both the parse and the completion testable
 * without a server, which is the only form of test this build can run for command code.
 *
 * <p>The nodes here must match {@code plugin.yml} exactly. Every administrative one is a child of
 * {@code antispeedrun.admin}, which defaults to {@code op}. {@code antispeedrun.bypass} — the
 * standing exemption — is deliberately <em>not</em> in that tree and is not referenced here:
 * {@code antispeedrun.admin.bypass} is the right to hand a bypass out, which is a different thing
 * from holding one.
 *
 * <p>{@link #PROGRESS} is the exception to the admin tree, and is why {@link #administrative()}
 * exists. It is a delegate to the standalone {@code /progress}, so it is gated on that command's
 * own {@code antispeedrun.progress} — which defaults to {@code true} — rather than on a node under
 * {@code antispeedrun.admin}. Folding it into the admin tree would make a player's view of their
 * own progression an operator privilege depending on which spelling they typed.
 */
public enum Subcommand {

    /** {@code /asr reload} — re-reads {@code config.yml} and applies it, all or nothing. */
    RELOAD("reload", "antispeedrun.admin.reload", "/asr reload"),

    /** {@code /asr profile apply <PROFILE>} — backs up the configuration and applies a preset. */
    PROFILE("profile", "antispeedrun.admin.profile", "/asr profile apply <CASUAL|SMP_STANDARD|HARDCORE>"),

    /** {@code /asr unlock <nether|end>} — opens a dimension gate server-wide, durably. */
    UNLOCK("unlock", "antispeedrun.admin.unlock", "/asr unlock <nether|end> [lock]"),

    /** {@code /asr bypass <player> [duration]} — grants or revokes a temporary bypass. */
    BYPASS("bypass", "antispeedrun.admin.bypass", "/asr bypass <player> [duration|off]"),

    /** {@code /asr inspect <player>} — reports a player's progression state. */
    INSPECT("inspect", "antispeedrun.admin.inspect", "/asr inspect <player>"),

    /**
     * {@code /asr progress} — the delegate to {@code /progress}, Task 2.1.2 (#3).
     *
     * <p>The one constant here that is not administration, and the one gated outside the
     * {@code antispeedrun.admin} tree. {@code plugin.yml} advertised this token before the command
     * existed and it was withdrawn in #73; this is it arriving properly.
     *
     * <p>It reuses {@code antispeedrun.progress} rather than declaring a node of its own, which is
     * the whole of the naming decision: a second node for the same capability, reached by a
     * different spelling of the same command, is a node an operator has to discover and grant
     * before {@code /asr progress} works for someone {@code /progress} already worked for.
     */
    PROGRESS("progress", "antispeedrun.progress", "/asr progress");

    private final String label;
    private final String permission;
    private final String usage;

    Subcommand(String label, String permission, String usage) {
        this.label = label;
        this.permission = permission;
        this.usage = usage;
    }

    /** The literal an operator types. */
    public String label() {
        return label;
    }

    /** The permission node required, exactly as declared in {@code plugin.yml}. */
    public String permission() {
        return permission;
    }

    /** The usage line, shown on a malformed invocation. */
    public String usage() {
        return usage;
    }

    /** Every label, in declaration order. */
    public static List<String> labels() {
        return List.of(RELOAD.label, PROFILE.label, UNLOCK.label, BYPASS.label, INSPECT.label,
                PROGRESS.label);
    }

    /**
     * Whether this subcommand is gated on a node under {@code antispeedrun.admin}.
     *
     * <p>The predicate {@code CommandGrammarTest} partitions on: the admin tree and the set of
     * nodes {@code /asr} enforces have to be exactly the same set, and that assertion stops holding
     * the moment a subcommand is gated outside the tree. Stated as a method rather than as a list
     * in the test so that a future non-admin subcommand has one place to say so.
     */
    public boolean administrative() {
        return permission.startsWith("antispeedrun.admin.");
    }

    /** Resolves a typed token case-insensitively; empty when it names no subcommand. */
    public static Optional<Subcommand> parse(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String normalised = token.trim().toLowerCase(Locale.ROOT);
        for (Subcommand subcommand : values()) {
            if (subcommand.label.equals(normalised)) {
                return Optional.of(subcommand);
            }
        }
        return Optional.empty();
    }
}
