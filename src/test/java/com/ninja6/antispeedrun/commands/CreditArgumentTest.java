package com.ninja6.antispeedrun.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.commands.CreditArgument.Action;
import com.ninja6.antispeedrun.commands.CreditArgument.Change;
import com.ninja6.antispeedrun.commands.CreditArgument.Invalid;
import com.ninja6.antispeedrun.commands.CreditArgument.Reason;
import com.ninja6.antispeedrun.storage.CreditSource;
import com.ninja6.antispeedrun.storage.PersonalCredit;
import com.ninja6.antispeedrun.storage.PersonalCreditStore;
import com.ninja6.antispeedrun.storage.StateFile;

/** {@code /asr credit} (#217): its grammar, its permission node, and what a change does. */
class CreditArgumentTest {

    private static CreditArgument parse(String... args) {
        return CreditArgument.parse(args);
    }

    @Nested
    @DisplayName("parsing")
    class Parsing {

        @Test
        @DisplayName("a grant or revoke of one credit, case-insensitively")
        void oneCredit() {
            assertEquals(new Change(Action.GRANT, "Steve", Set.of(PersonalCredit.MINE_STONE)),
                    parse("credit", "grant", "Steve", "mine-stone"));
            assertEquals(new Change(Action.REVOKE, "Steve", Set.of(PersonalCredit.OBTAIN_BLAZE_ROD)),
                    parse("credit", "REVOKE", "Steve", "Obtain-Blaze-Rod"));
        }

        @Test
        @DisplayName("every stored id parses, and the advancement spelling with underscores too")
        void everyId() {
            for (PersonalCredit credit : PersonalCredit.values()) {
                assertEquals(Optional.of(EnumSet.of(credit)), CreditArgument.credits(credit.id()));
                assertEquals(Optional.of(EnumSet.of(credit)),
                        CreditArgument.credits(credit.id().replace('-', '_')));
            }
        }

        @Test
        @DisplayName("smelt-iron is both sub-credits and all is every credit")
        void groups() {
            assertEquals(Optional.of(EnumSet.of(PersonalCredit.MINED_IRON, PersonalCredit.SMELTED_IRON)),
                    CreditArgument.credits("smelt_iron"));
            assertEquals(Optional.of(EnumSet.allOf(PersonalCredit.class)), CreditArgument.credits("ALL"));
        }

        @Test
        @DisplayName("each missing or wrong word reports its own reason")
        void failures() {
            assertEquals(new Invalid(Reason.MISSING_ACTION, ""), parse("credit"));
            assertEquals(new Invalid(Reason.UNKNOWN_ACTION, "give"), parse("credit", "give", "Steve", "all"));
            assertEquals(new Invalid(Reason.MISSING_PLAYER, ""), parse("credit", "grant"));
            assertEquals(new Invalid(Reason.MISSING_CREDIT, ""), parse("credit", "grant", "Steve"));
            assertEquals(new Invalid(Reason.MISSING_CREDIT, ""), parse("credit", "grant", "Steve", " "));
            assertEquals(new Invalid(Reason.EXTRA_ARGUMENT, "now"),
                    parse("credit", "grant", "Steve", "all", "now"));
        }

        @Test
        @DisplayName("a misspelt credit is refused, never widened to all")
        void typoIsNotAll() {
            CreditArgument parsed = parse("credit", "revoke", "Steve", "mine-stnoe");
            assertEquals(new Invalid(Reason.UNKNOWN_CREDIT, "mine-stnoe"), parsed);
            assertTrue(CreditArgument.credits("story/mine_stone").isEmpty());
            assertTrue(CreditArgument.credits("").isEmpty());
        }

        @Test
        @DisplayName("the credit words are every id, then smelt-iron, then all")
        void words() {
            assertEquals(List.of("mine-stone", "mined-iron", "smelted-iron", "iron-tools",
                    "upgrade-tools", "mine-diamond", "obtain-blaze-rod", "smelt-iron", "all"),
                    CreditArgument.creditWords());
            for (String word : CreditArgument.creditWords()) {
                assertTrue(CreditArgument.credits(word).isPresent(), word);
            }
        }

        @Test
        @DisplayName("only a canonical UUID is read as one; anything else is a name")
        void uuidTargets() {
            UUID uuid = UUID.randomUUID();
            assertEquals(Optional.of(uuid), CreditArgument.asUuid(uuid.toString()));
            assertEquals(Optional.of(uuid), CreditArgument.asUuid(" " + uuid.toString().toUpperCase() + " "));
            assertTrue(CreditArgument.asUuid("1-2-3-4-5").isEmpty());
            assertTrue(CreditArgument.asUuid("Steve").isEmpty());
            assertTrue(CreditArgument.asUuid("zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz").isEmpty());
        }
    }

    @Nested
    @DisplayName("permission")
    class Permission {

        @Test
        @DisplayName("credit is gated on antispeedrun.admin.credit, an op-default child of antispeedrun.admin")
        void node() {
            assertEquals(Subcommand.CREDIT, Subcommand.parse("Credit").orElseThrow());
            assertEquals("antispeedrun.admin.credit", Subcommand.CREDIT.permission());
            assertTrue(Subcommand.CREDIT.administrative());
            assertTrue(PluginYml.childrenOf("antispeedrun.admin").contains("antispeedrun.admin.credit"));
            assertFalse(PluginYml.reachableFrom("antispeedrun.admin.credit").contains("antispeedrun.bypass"));
        }

        @Test
        @DisplayName("a sender without the node is offered neither the subcommand nor its arguments")
        void completionHidesIt() {
            List<String> online = List.of("Steve");
            assertFalse(CommandCompletion.complete(new String[] {""}, s -> s != Subcommand.CREDIT, online)
                    .contains("credit"));
            assertEquals(List.of(), CommandCompletion.complete(new String[] {"credit", "grant", ""},
                    s -> s != Subcommand.CREDIT, online));
        }
    }

    @Nested
    @DisplayName("grant and revoke")
    class Apply {

        private final UUID player = UUID.randomUUID();
        private final PersonalCreditStore store = new PersonalCreditStore(
                Logger.getLogger(CreditArgumentTest.class.getName()), new MemoryFile(), Runnable::run);

        private Change change(String... args) {
            return (Change) CreditArgument.parse(args);
        }

        @Test
        @DisplayName("a grant counts at once, with the loot setting off, and a repeat changes nothing")
        void grant() {
            assertTrue(store.loadNow());
            assertEquals(List.of(PersonalCredit.MINE_DIAMOND),
                    change("credit", "grant", "Steve", "mine-diamond").applyTo(store, player));
            assertTrue(store.has(player, PersonalCredit.MINE_DIAMOND, false));
            assertEquals(Set.of(CreditSource.ACTION), store.sources(player, PersonalCredit.MINE_DIAMOND));
            assertEquals(List.of(), change("credit", "grant", "Steve", "mine-diamond").applyTo(store, player));
        }

        @Test
        @DisplayName("a revoke takes the credit away at once, loot source included")
        void revoke() {
            assertTrue(store.loadNow());
            store.record(player, PersonalCredit.MINED_IRON, CreditSource.LOOT);
            store.record(player, PersonalCredit.SMELTED_IRON, CreditSource.ACTION);
            store.record(player, PersonalCredit.MINE_STONE, CreditSource.ACTION);

            assertEquals(List.of(PersonalCredit.MINED_IRON, PersonalCredit.SMELTED_IRON),
                    change("credit", "revoke", "Steve", "smelt-iron").applyTo(store, player));
            assertFalse(store.has(player, PersonalCredit.MINED_IRON, true));
            assertFalse(store.has(player, PersonalCredit.SMELTED_IRON, true));
            assertTrue(store.has(player, PersonalCredit.MINE_STONE, false));
        }

        @Test
        @DisplayName("all grants every credit and reports only the ones that were missing")
        void all() {
            assertTrue(store.loadNow());
            store.record(player, PersonalCredit.MINE_STONE, CreditSource.ACTION);
            List<PersonalCredit> granted = change("credit", "grant", "Steve", "all").applyTo(store, player);
            assertEquals(PersonalCredit.values().length - 1, granted.size());
            assertFalse(granted.contains(PersonalCredit.MINE_STONE));
            for (PersonalCredit credit : PersonalCredit.values()) {
                assertTrue(store.has(player, credit, false), credit.id());
            }
            assertEquals(List.of(PersonalCredit.values()),
                    change("credit", "revoke", "Steve", "all").applyTo(store, player));
        }
    }

    private static final class MemoryFile implements StateFile {

        private Map<String, Object> document = Map.of();

        @Override
        public Map<String, Object> load() {
            return document;
        }

        @Override
        public void save(Map<String, Object> document) {
            this.document = Map.copyOf(document);
        }

        @Override
        public Optional<String> quarantine() {
            return Optional.empty();
        }
    }
}
