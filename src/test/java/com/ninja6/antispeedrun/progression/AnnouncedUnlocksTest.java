package com.ninja6.antispeedrun.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The join-announcement decision of #84: a gate cleared by {@code require-account-age-days} while
 * the player was offline has to announce itself on their next join, without re-congratulating a
 * player on gates they cleared long ago.
 *
 * <p>Bukkit-free, like the type under test: {@code paper-api} is {@code compileOnly} and is not on
 * the test classpath.
 */
class AnnouncedUnlocksTest {

    private static final String NETHER = Milestone.NETHER_ID;
    private static final String END = Milestone.END_ID;

    @Nested
    @DisplayName("the join decision")
    class OnJoin {

        @Test
        @DisplayName("a gate cleared while offline is announced on the next join")
        void tenureGateClearedWhileOfflineIsAnnounced() {
            // The player was last told about the Nether only. While they were away their server
            // tenure crossed require-account-age-days on The End, so it is open before they log in
            // and no advancement and no online watch can ever have announced it.
            AnnouncedUnlocks decision =
                    AnnouncedUnlocks.onJoin(Optional.of(Set.of(NETHER)), List.of(NETHER, END));

            assertEquals(List.of(END), decision.announce());
            assertEquals(Set.of(NETHER, END), decision.record());
        }

        @Test
        @DisplayName("a returning player is not re-congratulated on gates cleared long ago")
        void alreadyRecordedGatesAreSilent() {
            AnnouncedUnlocks decision =
                    AnnouncedUnlocks.onJoin(Optional.of(Set.of(NETHER, END)), List.of(NETHER, END));

            assertTrue(decision.announce().isEmpty());
            assertEquals(Set.of(NETHER, END), decision.record());
        }

        @Test
        @DisplayName("a player with no record at all is primed silently")
        void firstJoinWithoutARecordAnnouncesNothing() {
            // Every player on an established server the day this ships, and everyone again after a
            // playerdata wipe. Announcing the difference against nothing would congratulate the
            // whole population at once.
            AnnouncedUnlocks decision =
                    AnnouncedUnlocks.onJoin(Optional.empty(), List.of(NETHER, END));

            assertTrue(decision.announce().isEmpty());
            assertEquals(Set.of(NETHER, END), decision.record());
        }

        @Test
        @DisplayName("an empty record is a real fact and is announced against")
        void emptyRecordIsNotTheSameAsNoRecord() {
            // Seeded on a previous join when nothing was eligible. "You have been told about
            // nothing" is knowledge, unlike an absent record.
            AnnouncedUnlocks decision =
                    AnnouncedUnlocks.onJoin(Optional.of(Set.of()), List.of(NETHER));

            assertEquals(List.of(NETHER), decision.announce());
            assertEquals(Set.of(NETHER), decision.record());
        }

        @Test
        @DisplayName("announcements keep the configured order")
        void announcementsKeepConfiguredOrder() {
            AnnouncedUnlocks decision =
                    AnnouncedUnlocks.onJoin(Optional.of(Set.of()), List.of(NETHER, END));

            assertEquals(List.of(NETHER, END), decision.announce());
        }

        @Test
        @DisplayName("a gate that is no longer eligible drops out of the record")
        void ineligibleGatesLeaveTheRecord() {
            // An /asr reload can tighten a gate the player had already cleared. Keeping the id would
            // mean never announcing it again when they re-clear it.
            AnnouncedUnlocks decision =
                    AnnouncedUnlocks.onJoin(Optional.of(Set.of(NETHER, END)), List.of(NETHER));

            assertTrue(decision.announce().isEmpty());
            assertEquals(Set.of(NETHER), decision.record());
        }

        @Test
        void nothingEligibleRecordsNothing() {
            AnnouncedUnlocks decision = AnnouncedUnlocks.onJoin(Optional.of(Set.of(NETHER)), List.of());

            assertTrue(decision.announce().isEmpty());
            assertTrue(decision.record().isEmpty());
        }

        @Test
        void nullArgumentsAreRejected() {
            assertThrows(NullPointerException.class,
                    () -> AnnouncedUnlocks.onJoin(null, List.of()));
            assertThrows(NullPointerException.class,
                    () -> AnnouncedUnlocks.onJoin(Optional.empty(), null));
        }
    }

    @Nested
    @DisplayName("the persisted form")
    class Codec {

        @Test
        @DisplayName("an absent value is not an empty one")
        void nullDecodesToNoRecord() {
            assertTrue(AnnouncedUnlocks.decode(null).isEmpty());

            Optional<Set<String>> empty = AnnouncedUnlocks.decode("");
            assertTrue(empty.isPresent());
            assertTrue(empty.get().isEmpty());
        }

        @Test
        void roundTripsEveryId() {
            String encoded = AnnouncedUnlocks.encode(List.of(NETHER, END));

            assertEquals(Optional.of(Set.of(NETHER, END)), AnnouncedUnlocks.decode(encoded));
        }

        @Test
        @DisplayName("the separator cannot appear inside a milestone id")
        void separatorIsNotAnIdCharacter() {
            assertFalse(NETHER.contains(AnnouncedUnlocks.SEPARATOR));
            assertFalse(END.contains(AnnouncedUnlocks.SEPARATOR));
        }

        @Test
        @DisplayName("a damaged value degrades to the ids that survived")
        void blankAndPaddedTokensAreIgnored() {
            // Hand-edited playerdata, or a truncated write. Dropping the debris is right: the
            // surviving ids are still "already told about", and treating the whole record as absent
            // would silently re-prime the player instead.
            Optional<Set<String>> decoded =
                    AnnouncedUnlocks.decode(NETHER + "\n\n  " + END + "  \n");

            assertEquals(Optional.of(Set.of(NETHER, END)), decoded);
        }
    }

    @Test
    void theRecordIsImmutable() {
        AnnouncedUnlocks decision = AnnouncedUnlocks.onJoin(Optional.of(Set.of()), List.of(NETHER));

        assertThrows(UnsupportedOperationException.class, () -> decision.record().add(END));
        assertThrows(UnsupportedOperationException.class, () -> decision.announce().add(END));
    }
}
