package com.ninja6.antispeedrun.commands;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.storage.BypassGrant;

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
        @DisplayName("every subcommand is gated on the node plugin.yml declares for it")
        void permissionsMatchPluginYml() {
            // These strings are the contract with plugin.yml's antispeedrun.admin children. A
            // subcommand whose node is misspelt is not merely unusable -- with hasPermission on an
            // undeclared node it can read as denied for everyone or, worse, be checked against a
            // node nothing grants.
            assertEquals("antispeedrun.admin.reload", Subcommand.RELOAD.permission());
            assertEquals("antispeedrun.admin.profile", Subcommand.PROFILE.permission());
            assertEquals("antispeedrun.admin.unlock", Subcommand.UNLOCK.permission());
            assertEquals("antispeedrun.admin.bypass", Subcommand.BYPASS.permission());
            assertEquals("antispeedrun.admin.inspect", Subcommand.INSPECT.permission());
        }

        @Test
        @DisplayName("no subcommand is gated on the standing bypass permission")
        void bypassPermissionIsNotAnAdminNode() {
            // antispeedrun.bypass is deliberately outside the antispeedrun.admin tree, because
            // antispeedrun.admin defaults to op and nesting it would exempt every operator from
            // every gate. Granting a bypass and holding one are different rights.
            for (Subcommand subcommand : Subcommand.values()) {
                assertTrue(subcommand.permission().startsWith("antispeedrun.admin."),
                        subcommand + " must be gated on an antispeedrun.admin.* node");
                assertFalse(subcommand.permission().equals("antispeedrun.bypass"));
            }
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
