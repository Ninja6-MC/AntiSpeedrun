package com.ninja6.antispeedrun.commands;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import com.ninja6.antispeedrun.commands.JourneyBookPages.Paragraph;
import com.ninja6.antispeedrun.config.ConfigLoadException;
import com.ninja6.antispeedrun.config.MapConfigSection;
import com.ninja6.antispeedrun.config.CreditedActions;
import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.config.PluginConfig.DimensionGate;
import com.ninja6.antispeedrun.config.PluginConfig.DimensionGates;
import com.ninja6.antispeedrun.config.PluginConfig.ItemProgression;
import com.ninja6.antispeedrun.config.PluginConfig.ItemTier;
import com.ninja6.antispeedrun.config.PluginConfig.JourneyBook;
import com.ninja6.antispeedrun.config.PluginConfig.VillagerProgression;
import com.ninja6.antispeedrun.progression.MilestoneRequirement;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Journey Guide Book's text (#5): what each page says, generated from a configuration snapshot,
 * and how it is laid out onto pages a written book can show.
 */
class JourneyBookPagesTest {

    private static final PluginConfig.ItemProgression ITEMS =
            PluginConfig.defaults().itemProgression();

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** The config.yml this plugin ships, read off the classpath. */
    private static PluginConfig shipped() {
        try (InputStream in = JourneyBookPagesTest.class.getResourceAsStream("/config.yml");
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Map<?, ?> root = new Yaml().load(reader);
            return PluginConfig.from(MapConfigSection.of(root));
        } catch (IOException | ConfigLoadException failure) {
            throw new AssertionError("the shipped config.yml must load", failure);
        }
    }

    private static PluginConfig with(PluginConfig base, DimensionGates gates, ItemProgression items,
                                     VillagerProgression villager, JourneyBook book) {
        return new PluginConfig(base.profile(), gates, items, base.trimProgression(),
                base.idleReminder(), base.progressCard(), book, base.bossScaling(),
                base.antiCheese(), villager, base.warnings());
    }

    private static PluginConfig withGates(DimensionGates gates) {
        PluginConfig base = shipped();
        return with(base, gates, base.itemProgression(), base.villagerProgression(), base.journeyBook());
    }

    private static PluginConfig withTiers(List<ItemTier> tiers) {
        PluginConfig base = shipped();
        ItemProgression items = base.itemProgression();
        ItemProgression replaced = new ItemProgression(items.enabled(), items.dropRecallEnabled(),
                items.gateDispensers(), items.gateNestedBundles(), items.feedbackCooldownSeconds(),
                items.rejectionMessage(), tiers, items.requirePersonalCredit(),
                items.countStructureLoot());
        return with(base, base.dimensionGates(), replaced, base.villagerProgression(), base.journeyBook());
    }

    private static DimensionGate disabled(DimensionGate gate) {
        return new DimensionGate(false, gate.requirePlaytimeHours(), gate.requireAccountAgeDays(),
                gate.requireAdvancements(), gate.rejectionMessage());
    }

    private static String book(PluginConfig config) {
        return String.join("\n---\n", JourneyBookPages.pages(config));
    }

    @Nested
    @DisplayName("content")
    class Content {

        @Test
        @DisplayName("the shipped configuration produces a book every page of which is valid MiniMessage")
        void shippedBookParses() {
            List<String> pages = JourneyBookPages.pages(shipped());
            assertFalse(pages.isEmpty());
            assertTrue(pages.size() <= JourneyBookPages.MAX_PAGES);
            for (String page : pages) {
                assertDoesNotThrow(() -> MINI.deserialize(page), page);
                assertFalse(page.isBlank());
            }
        }

        @Test
        @DisplayName("the cover carries the configured title as markup")
        void cover() {
            String cover = JourneyBookPages.pages(shipped()).get(0);
            assertTrue(cover.startsWith("<bold><gold>Ninja6 Survival Guide</bold>"));
            assertTrue(cover.contains("/progress"), "the cover fits on one page");
        }

        @Test
        @DisplayName("both dimension gates list their advancements as client-side translations")
        void dimensionGates() {
            String text = book(shipped());
            assertTrue(text.contains("The Nether"));
            assertTrue(text.contains("The End"));
            assertTrue(text.contains(
                    "<lang_or:advancements.nether.find_fortress.title:'minecraft:nether/find_fortress'>"));
            assertFalse(text.contains("- minecraft:nether/find_fortress"),
                    "a vanilla key is shown by its title, not printed as a key");
        }

        @Test
        @DisplayName("a credited advancement is written as the action, with the loot note")
        void creditedAdvancementsNameTheAction() {
            String text = book(shipped());
            assertTrue(text.contains("- Mine iron ore (or loot iron from a chest you open first) "
                    + "and smelt iron in a furnace you loaded yourself"));
            assertTrue(text.contains("- Kill a blaze yourself"));
            assertFalse(text.contains("story.smelt_iron.title"),
                    "the vanilla title names a held item, which no longer counts");
            assertTrue(text.contains(CreditedActions.LOOT_NOTE));
        }

        @Test
        @DisplayName("a disabled gate is not described as a stage, and the book says it is open")
        void disabledGate() {
            PluginConfig shipped = shipped();
            String text = book(withGates(new DimensionGates(
                    disabled(shipped.dimensionGates().nether()), shipped.dimensionGates().theEnd())));
            assertFalse(text.contains("<dark_blue><bold>The Nether"));
            assertTrue(text.contains("The Nether is not gated."));
            assertFalse(text.contains("smelt_iron"));
        }

        @Test
        @DisplayName("with both gates off the book says so instead of listing nothing")
        void bothGatesOff() {
            PluginConfig shipped = shipped();
            String text = book(withGates(new DimensionGates(
                    disabled(shipped.dimensionGates().nether()), disabled(shipped.dimensionGates().theEnd()))));
            assertTrue(text.contains("The Nether and The End are open from the start."));
        }

        @Test
        @DisplayName("each item tier appears under a readable name with its configured hint")
        void itemTiers() {
            String text = book(shipped());
            assertTrue(text.contains("Iron Tier"));
            assertTrue(text.contains("Netherite Tier"));
            assertTrue(text.contains("Mine natural stone with a pickaxe yourself (Stone Age)"));
        }

        @Test
        @DisplayName("a hint is text: markup in it reaches the reader as characters")
        void hintIsEscaped() {
            String text = book(withTiers(List.of(new ItemTier("gold-tier", List.of("GOLD_*"), List.of(),
                    List.of(), List.of("minecraft:story/smelt_iron"), 0.0D, 0, "Earn <red>it</red>"))));
            assertTrue(text.contains(MINI.escapeTags("Earn <red>it</red>")));
            assertFalse(text.contains("Earn <red>it"));
        }

        @Test
        @DisplayName("a tier without a hint lists its requirements instead")
        void tierWithoutHint() {
            String text = book(withTiers(List.of(new ItemTier("gold-tier", List.of("GOLD_*"), List.of(),
                    List.of(), List.of("custom:quests/first"), 2.0D, 3, ""))));
            assertTrue(text.contains("Gold Tier"));
            assertTrue(text.contains("- custom:quests/first"),
                    "a non-vanilla key has no translation to guess at, so it is printed");
            assertTrue(text.contains("- Play 2 hours"));
            assertTrue(text.contains("- 3 days on this server"));
        }

        @Test
        @DisplayName("with no tiers, or item gating off, there is no items section")
        void noItemSection() {
            assertFalse(book(withTiers(List.of())).contains("You cannot pick up gated items"));
        }

        @Test
        @DisplayName("the Mending gate is described only while it is on")
        void mending() {
            PluginConfig base = shipped();
            assertFalse(book(base).contains("Mending"), "gate-mending-trade ships off");
            PluginConfig on = with(base, base.dimensionGates(), base.itemProgression(),
                    new VillagerProgression(true, "minecraft:story/cure_zombie_villager",
                            "Cure a Zombie Villager"),
                    base.journeyBook());
            String text = book(on);
            assertTrue(text.contains("Mending"));
            assertTrue(text.contains("- Cure a Zombie Villager"));
        }

        @Test
        @DisplayName("the early Eye of Ender rule is described, since it ships on")
        void eyeOfEnder() {
            String text = book(shipped());
            assertTrue(text.contains("Eyes of Ender"));
        }

        @Test
        @DisplayName("the closing page names both player commands")
        void commands() {
            List<String> pages = JourneyBookPages.pages(shipped());
            String last = pages.get(pages.size() - 1);
            assertTrue(last.contains("/progress"));
            assertTrue(last.contains("/journeybook"));
        }
    }

    @Nested
    @DisplayName("requirements")
    class Requirements {

        @Test
        @DisplayName("vanilla advancement keys map to their title translation keys")
        void translationKeys() {
            assertEquals(Optional.of("advancements.story.smelt_iron.title"),
                    JourneyBookPages.translationKey("minecraft:story/smelt_iron"));
            assertEquals(Optional.of("advancements.nether.find_fortress.title"),
                    JourneyBookPages.translationKey("minecraft:nether/find_fortress"));
            assertEquals(Optional.empty(), JourneyBookPages.translationKey("custom:story/smelt_iron"));
            assertEquals(Optional.empty(), JourneyBookPages.translationKey("minecraft:root"));
            assertEquals(Optional.empty(), JourneyBookPages.translationKey("minecraft:story/<red>"));
            assertEquals(Optional.empty(), JourneyBookPages.translationKey(null));
        }

        @Test
        @DisplayName("the three vanilla advancements whose translation key does not follow the id")
        void irregularTranslationKeys() {
            // Read from the 1.21.4 server jar: every other advancement's title key is its id with
            // slashes as dots; these three are not, and the id-derived key would render raw.
            assertEquals(Optional.of("advancements.husbandry.breed_all_animals.title"),
                    JourneyBookPages.translationKey("minecraft:husbandry/bred_all_animals"));
            assertEquals(Optional.of("advancements.husbandry.netherite_hoe.title"),
                    JourneyBookPages.translationKey("minecraft:husbandry/obtain_netherite_hoe"));
            assertEquals(Optional.of("advancements.adventure.read_power_from_chiseled_bookshelf.title"),
                    JourneyBookPages.translationKey("minecraft:adventure/read_power_of_chiseled_bookshelf"));
        }

        @Test
        @DisplayName("every advancement title falls back to its id if the client lacks the key")
        void translationFallsBackToTheId() {
            Component rendered = MINI.deserialize(
                    JourneyBookPages.advancement("minecraft:story/some_future_advancement").markup());
            TranslatableComponent title = translatable(rendered);
            assertEquals("advancements.story.some_future_advancement.title", title.key());
            assertEquals("minecraft:story/some_future_advancement", title.fallback());

            TranslatableComponent irregular = translatable(MINI.deserialize(
                    JourneyBookPages.advancement("minecraft:husbandry/bred_all_animals").markup()));
            assertEquals("advancements.husbandry.breed_all_animals.title", irregular.key());
            assertEquals("minecraft:husbandry/bred_all_animals", irregular.fallback());
        }

        private TranslatableComponent translatable(Component component) {
            if (component instanceof TranslatableComponent translatable) {
                return translatable;
            }
            for (Component child : component.children()) {
                TranslatableComponent found = translatableOrNull(child);
                if (found != null) {
                    return found;
                }
            }
            throw new AssertionError("no translatable component in " + component);
        }

        private TranslatableComponent translatableOrNull(Component component) {
            try {
                return translatable(component);
            } catch (AssertionError none) {
                return null;
            }
        }

        @Test
        @DisplayName("a requirement with nothing in it is open from the start")
        void emptyRequirement() {
            List<Paragraph> lines = JourneyBookPages.requirement(MilestoneRequirement.none(), ITEMS);
            assertEquals(1, lines.size());
            assertEquals("Open from the start.", lines.get(0).plain());
        }

        @Test
        @DisplayName("playtime reads in minutes below an hour and in hours above")
        void playtime() {
            assertEquals("- Play 30 minutes", JourneyBookPages.requirement(
                    new MilestoneRequirement(List.of(), 0.5D, 0), ITEMS).get(0).plain());
            assertEquals("- Play 1 hour", JourneyBookPages.requirement(
                    new MilestoneRequirement(List.of(), 1.0D, 0), ITEMS).get(0).plain());
            assertEquals("- Play 1.5 hours", JourneyBookPages.requirement(
                    new MilestoneRequirement(List.of(), 1.5D, 0), ITEMS).get(0).plain());
            assertEquals("- 1 day on this server", JourneyBookPages.requirement(
                    new MilestoneRequirement(List.of(), 0.0D, 1), ITEMS).get(0).plain());
        }

        @Test
        @DisplayName("tier ids read as words")
        void tierNames() {
            assertEquals("Iron Tier", JourneyBookPages.tierName("iron-tier"));
            assertEquals("End Tier", JourneyBookPages.tierName("end_tier"));
            assertEquals("Custom", JourneyBookPages.tierName("custom"));
            assertEquals("--", JourneyBookPages.tierName("--"));
        }
    }

    @Nested
    @DisplayName("title")
    class Title {

        @Test
        @DisplayName("the stored title is the configured one without its markup")
        void stripped() {
            assertEquals("Ninja6 Survival Guide", JourneyBookPages.plainTitle("<gold>Ninja6 Survival Guide"));
        }

        @Test
        @DisplayName("a title longer than a book allows is cut to fit rather than refused")
        void truncated() {
            String title = JourneyBookPages.plainTitle("<gold>" + "x".repeat(40));
            assertEquals(JourneyBookPages.MAX_TITLE_LENGTH, title.length());
        }

        @Test
        @DisplayName("a title that is only markup still gives the book a title")
        void blank() {
            assertEquals("Journey Guide", JourneyBookPages.plainTitle("<gold></gold>"));
        }
    }

    @Nested
    @DisplayName("layout")
    class Layout {

        private int linesOf(List<Paragraph> paragraphs) {
            return paragraphs.stream().mapToInt(Paragraph::lines).sum();
        }

        @Test
        @DisplayName("text wraps at spaces, and a word longer than a line is broken")
        void wrapping() {
            assertEquals(1, Paragraph.text("", "").lines());
            assertEquals(1, Paragraph.text("", "x".repeat(JourneyBookPages.CHARS_PER_LINE)).lines());
            assertEquals(2, Paragraph.text("", "x".repeat(JourneyBookPages.CHARS_PER_LINE + 1)).lines());
            // "aaaaaaaaaa bbbbbbbbbb" is 21 characters, but the second word moves down whole.
            assertEquals(2, Paragraph.text("", "aaaaaaaaaa bbbbbbbbbb").lines());
            assertEquals(3, Paragraph.text("", "x".repeat(JourneyBookPages.CHARS_PER_LINE * 2 + 1)).lines());
        }

        @Test
        @DisplayName("no page is given more lines than a book page shows")
        void pagesFit() {
            List<Paragraph> section = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                section.add(Paragraph.text("", "Line number " + i + " of a long section"));
            }
            List<String> pages = JourneyBookPages.layout(section);
            assertTrue(pages.size() > 1);

            // Re-pack the paragraphs the way layout did and check every page's budget.
            int page = 0;
            int used = 0;
            for (Paragraph paragraph : section) {
                if (used + paragraph.lines() > JourneyBookPages.LINES_PER_PAGE) {
                    page++;
                    used = 0;
                }
                used += paragraph.lines();
                assertTrue(used <= JourneyBookPages.LINES_PER_PAGE);
            }
            assertEquals(page + 1, pages.size());
            assertTrue(linesOf(section) > JourneyBookPages.LINES_PER_PAGE);
        }

        @Test
        @DisplayName("a blank line never opens or closes a page")
        void blankLinesDropped() {
            assertEquals(List.of("text"), JourneyBookPages.layout(List.of(
                    Paragraph.text("", ""), Paragraph.text("", "text"), Paragraph.text("", ""))));
            assertEquals(List.of("a<reset>\n\nb"), JourneyBookPages.layout(List.of(
                    Paragraph.text("", "a"), Paragraph.text("", ""), Paragraph.text("", "b"))));

            // Thirteen lines used: the blank and the next line do not both fit, so the blank is
            // dropped at the break instead of ending the first page.
            List<Paragraph> section = new ArrayList<>();
            for (int i = 0; i < JourneyBookPages.LINES_PER_PAGE - 1; i++) {
                section.add(Paragraph.text("", "line"));
            }
            section.add(Paragraph.text("", ""));
            section.add(Paragraph.text("", "next"));
            List<String> pages = JourneyBookPages.layout(section);
            assertEquals(2, pages.size());
            assertFalse(pages.get(0).endsWith("\n"));
            assertEquals("next", pages.get(1));
        }

        @Test
        @DisplayName("the shipped book uses more than one page and no page is empty")
        void shippedLayout() {
            List<String> pages = JourneyBookPages.pages(shipped());
            assertTrue(pages.size() >= 4, "cover, dimensions, items and commands at least");
            for (String page : pages) {
                assertFalse(page.startsWith("<reset>"), "a page must not open on a separator");
            }
        }
    }
}
