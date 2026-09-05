package com.ninja6.antispeedrun.commands;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.storage.BypassGrant;
import com.ninja6.antispeedrun.storage.DimensionUnlock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything about {@code /asr} that can be decided without a server: which token names which
 * subcommand, which permission node gates it, what a tab press offers, and how a duration argument
 * is read.
 *
 * <p>{@code paper-api} is {@code compileOnly}, so a test that needed a {@code CommandSender} could
 * not run at all. That is why the grammar is factored out of the executor: this is the difference
 * between command behaviour that is asserted and command behaviour that is merely read.
 */
class CommandGrammarTest {

    private static final List<String> ONLINE = List.of("Alex", "Steve", "alexandra");

    private static Predicate<Subcommand> allowing(Subcommand... allowed) {
        Set<Subcommand> permitted = Set.of(allowed);
        return permitted::contains;
    }

    private static final Predicate<Subcommand> ADMIN = subcommand -> true;
    private static final Predicate<Subcommand> NOBODY = subcommand -> false;

    @Nested
    @DisplayName("subcommand table")
    class Table {

        @Test
        @DisplayName("every subcommand is gated on a node plugin.yml actually declares")
        void permissionsMatchPluginYml() {
            // Read out of plugin.yml, not copied from it. This assertion used to compare one
            // hardcoded list against another and never opened the file, so renaming a node in
            // plugin.yml left it green while the command enforced a node that no longer existed
            // (#76). With hasPermission on an undeclared node, that reads as denied for everyone
            // or, worse, is checked against a node nothing grants.
            Set<String> declared = PluginYml.declaredPermissions();
            for (Subcommand subcommand : Subcommand.values()) {
                assertTrue(declared.contains(subcommand.permission()),
                        subcommand + " is gated on \"" + subcommand.permission() + "\", which "
                                + "plugin.yml does not declare. Declared nodes: " + declared);
            }
        }

        @Test
        @DisplayName("every antispeedrun.admin child is enforced by exactly one subcommand")
        void everyAdminNodeIsEnforced() {
            // The other direction of the same drift: a node added to the admin tree that no
            // branch checks is a permission an operator can grant to no effect, and one removed
            // from it while the dispatcher still checks it is a branch nobody can reach.
            Set<String> enforced = new LinkedHashSet<>();
            for (Subcommand subcommand : Subcommand.values()) {
                assertTrue(enforced.add(subcommand.permission()),
                        subcommand + " shares its node with another subcommand");
            }
            assertEquals(PluginYml.childrenOf("antispeedrun.admin"), enforced,
                    "the children of antispeedrun.admin and the nodes /asr enforces must be the "
                            + "same set");
        }

        @Test
        @DisplayName("antispeedrun.bypass is not reachable as a child of antispeedrun.admin")
        void bypassPermissionIsNotAnAdminNode() {
            // The comment in plugin.yml explains why this matters: antispeedrun.admin defaults to
            // op, so nesting the standing exemption under it would silently exempt every operator
            // from every gate -- including staff playing survival, and including anyone trying to
            // verify that the gates work at all. Checked transitively, because a node three levels
            // down grants just as much as one directly beneath.
            Set<String> reachable = PluginYml.reachableFrom("antispeedrun.admin");
            assertFalse(reachable.contains("antispeedrun.bypass"),
                    "antispeedrun.bypass must not be reachable from antispeedrun.admin; it was "
                            + "reachable through " + reachable);
            for (String node : reachable) {
                assertFalse(node.startsWith("antispeedrun.bypass."),
                        node + " must not be reachable from antispeedrun.admin either");
            }

            // And the dispatcher itself never gates a branch on the exemption: granting a bypass
            // (antispeedrun.admin.bypass) is a different right from holding one.
            for (Subcommand subcommand : Subcommand.values()) {
                assertTrue(subcommand.permission().startsWith("antispeedrun.admin."),
                        subcommand + " must be gated on an antispeedrun.admin.* node");
                assertFalse(subcommand.permission().equals("antispeedrun.bypass"));
            }
        }

        @Test
        @DisplayName("the plugin.yml usage line lists exactly the subcommands that are accepted")
        void usageLineMatchesTheDispatcher() {
            // #73: the line advertised "progress" and "book", which belong to tasks #3 and #5 and
            // are not implemented, so an operator following it was told the subcommand does not
            // exist and handed the same list again. Asserted against Subcommand rather than
            // against a literal, so adding a subcommand without advertising it -- or advertising
            // one before it exists -- fails here.
            assertEquals("/asr <" + String.join("|", Subcommand.labels()) + ">",
                    PluginYml.usageOf("antispeedrun"));
        }

        @Test
        @DisplayName("nothing advertises an /asr subcommand the dispatcher does not accept")
        void permissionDescriptionsDoNotAdvertiseAbsentSubcommands() {
            // The descriptions on antispeedrun.progress and antispeedrun.book named /asr progress
            // and /asr book. An operator reads these in a permissions plugin, so they are as much
            // a promise as the usage line is -- and so is a description or usage line under
            // commands:, which /help prints. The scan therefore covers both sections rather than
            // permissions alone, and matches case-insensitively: "/asr Reload" is the same promise
            // as "/asr reload", and Subcommand.parse accepts either.
            Pattern invocation = Pattern.compile("/asr\\s+([A-Za-z]+)");
            PluginYml.advertisements().forEach((where, text) -> {
                Matcher named = invocation.matcher(text);
                while (named.find()) {
                    String label = named.group(1);
                    assertTrue(Subcommand.parse(label).isPresent(),
                            where + " advertises \"/asr " + label + "\", which the dispatcher "
                                    + "answers with \"Unknown subcommand\". Accepted: "
                                    + Subcommand.labels());
                }
            });
        }

        @Test
        @DisplayName("labels parse case-insensitively and nothing else parses")
        void parsing() {
            assertEquals(Subcommand.RELOAD, Subcommand.parse("Reload").orElseThrow());
            assertEquals(Subcommand.INSPECT, Subcommand.parse(" INSPECT ").orElseThrow());
            assertTrue(Subcommand.parse("relaod").isEmpty());
            assertTrue(Subcommand.parse("").isEmpty());
            assertTrue(Subcommand.parse(null).isEmpty());
            assertEquals(List.of("reload", "profile", "unlock", "bypass", "inspect"),
                    Subcommand.labels());
        }
    }

    @Nested
    @DisplayName("tab completion")
    class Completion {

        @Test
        @DisplayName("the first argument offers only subcommands the sender may run")
        void firstArgumentIsPermissionFiltered() {
            assertEquals(List.of("reload", "profile", "unlock", "bypass", "inspect"),
                    CommandCompletion.complete(new String[] {""}, ADMIN, ONLINE));
            assertEquals(List.of("reload"),
                    CommandCompletion.complete(new String[] {""}, allowing(Subcommand.RELOAD), ONLINE));
            assertEquals(List.of(),
                    CommandCompletion.complete(new String[] {""}, NOBODY, ONLINE));
        }

        @Test
        @DisplayName("a partial first argument is prefix-filtered case-insensitively")
        void prefixFiltered() {
            assertEquals(List.of("reload"), CommandCompletion.complete(new String[] {"re"}, ADMIN, ONLINE));
            assertEquals(List.of("unlock"), CommandCompletion.complete(new String[] {"UN"}, ADMIN, ONLINE));
        }

        @Test
        @DisplayName("nothing is offered under a subcommand the sender cannot run")
        void doesNotLeakPlayerNames() {
            // The failure this prevents: a player with no permissions presses tab after "bypass"
            // and is handed the online player list of a server that hides it.
            assertEquals(List.of(),
                    CommandCompletion.complete(new String[] {"bypass", ""}, NOBODY, ONLINE));
            assertEquals(List.of(),
                    CommandCompletion.complete(new String[] {"inspect", ""},
                            allowing(Subcommand.RELOAD), ONLINE));
        }

        @Test
        @DisplayName("profile completes apply, then the three presets")
        void profileArguments() {
            assertEquals(List.of("apply"),
                    CommandCompletion.complete(new String[] {"profile", ""}, ADMIN, ONLINE));
            assertEquals(List.of("CASUAL", "SMP_STANDARD", "HARDCORE"),
                    CommandCompletion.complete(new String[] {"profile", "apply", ""}, ADMIN, ONLINE));
            // CUSTOM is not a preset and must never be offered.
            assertFalse(CommandCompletion.complete(new String[] {"profile", "apply", ""}, ADMIN, ONLINE)
                    .contains("CUSTOM"));
        }

        @Test
        @DisplayName("unlock completes the two dimensions, then lock")
        void unlockArguments() {
            assertEquals(List.of("nether", "end"),
                    CommandCompletion.complete(new String[] {"unlock", ""}, ADMIN, ONLINE));
            assertEquals(List.of("lock"),
                    CommandCompletion.complete(new String[] {"unlock", "end", ""}, ADMIN, ONLINE));
            assertEquals(List.of(),
                    CommandCompletion.complete(new String[] {"unlock", "overworld", ""}, ADMIN, ONLINE));
        }

        @Test
        @DisplayName("bypass completes players, then durations; inspect takes no third argument")
        void playerArguments() {
            assertEquals(List.of("Alex", "alexandra"),
                    CommandCompletion.complete(new String[] {"bypass", "al"}, ADMIN, ONLINE));
            assertEquals(BypassDuration.suggestions(),
                    CommandCompletion.complete(new String[] {"bypass", "Steve", ""}, ADMIN, ONLINE));
            assertEquals(List.of(),
                    CommandCompletion.complete(new String[] {"inspect", "Steve", ""}, ADMIN, ONLINE));
            assertEquals(List.of(),
                    CommandCompletion.complete(new String[] {"reload", ""}, ADMIN, ONLINE));
        }

        @Test
        @DisplayName("an unknown subcommand completes to nothing")
        void unknownSubcommand() {
            assertEquals(List.of(),
                    CommandCompletion.complete(new String[] {"relaod", ""}, ADMIN, ONLINE));
            assertEquals(List.of(), CommandCompletion.complete(new String[0], ADMIN, ONLINE));
        }
    }

    @Nested
    @DisplayName("bypass durations")
    class Durations {

        @Test
        @DisplayName("single-unit and compound terms parse")
        void terms() {
            assertEquals(45_000L, BypassDuration.parse("45s").orElseThrow());
            assertEquals(30L * 60_000L, BypassDuration.parse("30m").orElseThrow());
            assertEquals(2L * 3_600_000L, BypassDuration.parse("2h").orElseThrow());
            assertEquals(24L * 3_600_000L, BypassDuration.parse("1d").orElseThrow());
            assertEquals(90L * 60_000L, BypassDuration.parse("1h30m").orElseThrow());
            assertEquals(30L * 60_000L, BypassDuration.parse(" 30M ").orElseThrow());
        }

        @Test
        @DisplayName("the words map to the two sentinels")
        void words() {
            assertEquals(BypassGrant.PERMANENT, BypassDuration.parse("permanent").orElseThrow());
            assertEquals(BypassGrant.PERMANENT, BypassDuration.parse("FOREVER").orElseThrow());
            assertEquals(BypassDuration.REVOKE, BypassDuration.parse("off").orElseThrow());
            assertEquals(BypassDuration.REVOKE, BypassDuration.parse("revoke").orElseThrow());
            assertEquals(BypassDuration.REVOKE, BypassDuration.parse("0").orElseThrow());
        }

        @Test
        @DisplayName("a bare number is rejected rather than guessed at")
        void barenumberRejected() {
            // "/asr bypass Steve 30" meaning thirty milliseconds is a silent misunderstanding, so
            // the operator is told instead.
            assertTrue(BypassDuration.parse("30").isEmpty());
            assertTrue(BypassDuration.parse("").isEmpty());
            assertTrue(BypassDuration.parse(null).isEmpty());
            assertTrue(BypassDuration.parse("later").isEmpty());
            assertTrue(BypassDuration.parse("30m please").isEmpty());
            assertTrue(BypassDuration.parse("m30").isEmpty());
            assertTrue(BypassDuration.parse("-5m").isEmpty());
        }

        @Test
        @DisplayName("absurd lengths are rejected instead of overflowing into the past")
        void boundsChecked() {
            assertTrue(BypassDuration.parse("400d").isEmpty());
            assertTrue(BypassDuration.parse("99999999999999999999d").isEmpty());
            assertTrue(BypassDuration.parse("365d").isPresent());
        }

        @Test
        @DisplayName("an unqualified grant is a defined, finite length")
        void defaultIsFinite() {
            // #57 requires an expiry; a grant that quietly never ends is how a server acquires a
            // permanently exempt player nobody remembers exempting.
            assertTrue(BypassDuration.DEFAULT_MILLIS > 0L);
            assertTrue(BypassDuration.DEFAULT_MILLIS < BypassDuration.MAX_MILLIS);
        }

        @Test
        @DisplayName("formatting reads back the way it was typed")
        void formatting() {
            assertEquals("30m", BypassDuration.format(BypassDuration.parse("30m").orElseThrow()));
            assertEquals("1h 30m", BypassDuration.format(BypassDuration.parse("1h30m").orElseThrow()));
            assertEquals("2d", BypassDuration.format(BypassDuration.parse("2d").orElseThrow()));
            assertEquals("45s", BypassDuration.format(45_000L));
            assertEquals("permanent", BypassDuration.format(BypassGrant.PERMANENT));
        }
    }

    @Nested
    @DisplayName("unlock arguments")
    class Unlock {

        @Test
        @DisplayName("two words open the dimension")
        void opens() {
            assertEquals(new UnlockArgument.Open(DimensionUnlock.THE_END),
                    UnlockArgument.parse(new String[] {"unlock", "end"}));
            assertEquals(new UnlockArgument.Open(DimensionUnlock.NETHER),
                    UnlockArgument.parse(new String[] {"unlock", "NETHER"}));
        }

        @Test
        @DisplayName("a third word of lock closes it")
        void closes() {
            assertEquals(new UnlockArgument.Close(DimensionUnlock.THE_END),
                    UnlockArgument.parse(new String[] {"unlock", "end", "lock"}));
            // Both spellings of the dimension, and the option, are case-insensitive.
            assertEquals(new UnlockArgument.Close(DimensionUnlock.THE_END),
                    UnlockArgument.parse(new String[] {"unlock", "the_end", "LOCK"}));
            assertEquals(new UnlockArgument.Close(DimensionUnlock.NETHER),
                    UnlockArgument.parse(new String[] {"unlock", "nether", " lock "}));
        }

        @Test
        @DisplayName("a misspelt lock is rejected, never treated as an unlock")
        void typoDoesNotInvertTheIntent() {
            // The regression. "/asr unlock end lcok" previously fell through to the unlock branch
            // and opened The End to the whole server, persisted it, and reported success -- the
            // exact opposite of what was typed, on durable state.
            UnlockArgument parsed = UnlockArgument.parse(new String[] {"unlock", "end", "lcok"});

            assertEquals(new UnlockArgument.Invalid(UnlockArgument.Reason.UNKNOWN_OPTION, "lcok"),
                    parsed);
            assertFalse(parsed instanceof UnlockArgument.Open,
                    "a typo must never resolve to opening a dimension");
        }

        @Test
        @DisplayName("a trailing extra word is rejected too")
        void extraWordRejected() {
            assertEquals(new UnlockArgument.Invalid(UnlockArgument.Reason.UNKNOWN_OPTION, "lock"),
                    UnlockArgument.parse(new String[] {"unlock", "end", "lock", "please"}));
        }

        @Test
        @DisplayName("a missing or unknown dimension each report their own reason")
        void dimensionFailures() {
            assertEquals(new UnlockArgument.Invalid(UnlockArgument.Reason.MISSING_DIMENSION, ""),
                    UnlockArgument.parse(new String[] {"unlock"}));
            assertEquals(new UnlockArgument.Invalid(UnlockArgument.Reason.UNKNOWN_DIMENSION, "overworld"),
                    UnlockArgument.parse(new String[] {"unlock", "overworld"}));
            assertEquals(new UnlockArgument.Invalid(UnlockArgument.Reason.MISSING_DIMENSION, ""),
                    UnlockArgument.parse(null));
        }
    }

    @Nested
    @DisplayName("bypass expiry")
    class Expiry {

        @Test
        @DisplayName("a grant is over at its expiry, not after it")
        void boundary() {
            assertTrue(BypassGrant.isActive(1_001L, 1_000L));
            assertFalse(BypassGrant.isActive(1_000L, 1_000L));
            assertFalse(BypassGrant.isActive(999L, 1_000L));
        }

        @Test
        @DisplayName("a permanent grant never elapses")
        void permanentNeverElapses() {
            assertTrue(BypassGrant.isActive(BypassGrant.PERMANENT, Long.MAX_VALUE - 1L));
        }
    }
}
