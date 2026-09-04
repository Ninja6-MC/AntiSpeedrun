package com.ninja6.antispeedrun.storage;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The persisted shape of {@code /asr unlock}, and how forgiving reading it back is. */
class UnlockStateTest {

    @Nested
    @DisplayName("mutation")
    class Mutation {

        @Test
        @DisplayName("an unlock is recorded with its timestamp")
        void recordsTimestamp() {
            UnlockState state = UnlockState.empty().unlocked(DimensionUnlock.NETHER, 1_700_000_000_000L);

            assertTrue(state.isUnlocked(DimensionUnlock.NETHER));
            assertEquals(1_700_000_000_000L, state.unlockedAt(DimensionUnlock.NETHER).orElseThrow());
            assertFalse(state.isUnlocked(DimensionUnlock.THE_END));
        }

        @Test
        @DisplayName("re-unlocking is identity, so a repeated command writes nothing")
        void reUnlockIsIdentity() {
            UnlockState once = UnlockState.empty().unlocked(DimensionUnlock.THE_END, 1_000L);

            // Same instance, not merely equal: DimensionUnlockStore uses reference identity to
            // decide whether a file write is needed at all.
            assertSame(once, once.unlocked(DimensionUnlock.THE_END, 9_999L));
            assertEquals(1_000L, once.unlockedAt(DimensionUnlock.THE_END).orElseThrow());
        }

        @Test
        @DisplayName("locking removes the record, and locking twice is identity")
        void locking() {
            UnlockState unlocked = UnlockState.empty().unlocked(DimensionUnlock.NETHER, 5L);
            UnlockState relocked = unlocked.locked(DimensionUnlock.NETHER);

            assertFalse(relocked.isUnlocked(DimensionUnlock.NETHER));
            assertSame(relocked, relocked.locked(DimensionUnlock.NETHER));
        }

        @Test
        @DisplayName("the previous state is unchanged by a mutation")
        void immutable() {
            UnlockState before = UnlockState.empty();
            before.unlocked(DimensionUnlock.NETHER, 1L);

            assertFalse(before.isUnlocked(DimensionUnlock.NETHER));
        }
    }

    @Nested
    @DisplayName("persistence round trip")
    class RoundTrip {

        @Test
        @DisplayName("both dimensions survive a write and a read")
        void roundTrips() {
            UnlockState original = UnlockState.empty()
                    .unlocked(DimensionUnlock.NETHER, 111L)
                    .unlocked(DimensionUnlock.THE_END, 222L);

            assertEquals(original, UnlockState.fromDocument(original.toDocument()));
        }

        @Test
        @DisplayName("keys carry the configuration spelling, not the command spelling")
        void keysUseStorageSpelling() {
            Map<String, Object> document =
                    UnlockState.empty().unlocked(DimensionUnlock.THE_END, 7L).toDocument();

            // "the_end" matches dimension-gates.the_end in config.yml. "end" is the command
            // argument only; a persisted file that used it would not read as the same thing.
            assertEquals(Map.of("dimension-unlocks.the_end", 7L), document);
        }

        @Test
        @DisplayName("an empty state persists as an empty document")
        void emptyDocument() {
            assertTrue(UnlockState.empty().toDocument().isEmpty());
            assertEquals(UnlockState.empty(), UnlockState.fromDocument(Map.of()));
        }

        @Test
        @DisplayName("a mangled entry is skipped rather than re-locking every dimension")
        void toleratesDamage() {
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("dimension-unlocks.nether", 42L);
            document.put("dimension-unlocks.the_end", "yesterday");   // not a number
            document.put("dimension-unlocks.aether", 1L);             // no such dimension
            document.put("something-else", 1L);                       // not ours

            UnlockState state = UnlockState.fromDocument(document);

            assertTrue(state.isUnlocked(DimensionUnlock.NETHER));
            assertFalse(state.isUnlocked(DimensionUnlock.THE_END));
        }

        @Test
        @DisplayName("an integer timestamp reads back as a long")
        void acceptsAnyNumber() {
            // YamlConfiguration hands back an Integer for anything that fits in 32 bits, so a
            // hand-written or older file will not always contain a Long.
            UnlockState state = UnlockState.fromDocument(Map.of("dimension-unlocks.nether", 3));

            assertEquals(3L, state.unlockedAt(DimensionUnlock.NETHER).orElseThrow());
        }
    }

    @Nested
    @DisplayName("argument parsing")
    class Parsing {

        @Test
        @DisplayName("both spellings of The End resolve, case-insensitively")
        void parsesEnd() {
            assertEquals(DimensionUnlock.THE_END, DimensionUnlock.parse("end").orElseThrow());
            assertEquals(DimensionUnlock.THE_END, DimensionUnlock.parse("the_end").orElseThrow());
            assertEquals(DimensionUnlock.THE_END, DimensionUnlock.parse("The-End").orElseThrow());
            assertEquals(DimensionUnlock.NETHER, DimensionUnlock.parse(" NETHER ").orElseThrow());
        }

        @Test
        @DisplayName("anything else is rejected rather than guessed at")
        void rejectsUnknown() {
            assertTrue(DimensionUnlock.parse("overworld").isEmpty());
            assertTrue(DimensionUnlock.parse("").isEmpty());
            assertTrue(DimensionUnlock.parse(null).isEmpty());
        }

        @Test
        @DisplayName("each dimension maps to the milestone id the gates already use")
        void milestoneIdsAgree() {
            assertEquals(com.ninja6.antispeedrun.progression.Milestone.NETHER_ID,
                    DimensionUnlock.NETHER.milestoneId());
            assertEquals(com.ninja6.antispeedrun.progression.Milestone.END_ID,
                    DimensionUnlock.THE_END.milestoneId());
            assertEquals(DimensionUnlock.NETHER, DimensionUnlock
                    .byMilestone(com.ninja6.antispeedrun.progression.Milestone.NETHER_ID).orElseThrow());
        }
    }
}
