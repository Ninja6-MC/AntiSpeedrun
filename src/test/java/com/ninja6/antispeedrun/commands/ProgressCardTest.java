package com.ninja6.antispeedrun.commands;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.commands.ProgressCardRenderer.Row;
import com.ninja6.antispeedrun.commands.ProgressCardRenderer.Status;
import com.ninja6.antispeedrun.commands.ProgressCardRenderer.Style;
import com.ninja6.antispeedrun.config.PluginConfig.SimpleCard;
import com.ninja6.antispeedrun.progression.EligibilityResult;
import com.ninja6.antispeedrun.progression.IdleReminderRules;
import com.ninja6.antispeedrun.progression.IdleReminderRules.MilestoneProgress;
import com.ninja6.antispeedrun.progression.Milestone;
import com.ninja6.antispeedrun.progression.MilestoneRequirement;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code /progress} card, Task 2.1.2 (#3), asserted without a server.
 *
 * <p>The Bedrock criterion is the reason this file is worth reading. #3 asked for a card that
 * "renders cleanly across Java &amp; Bedrock (Geyser) clients", which audit finding R-21 rejected as
 * unverifiable — nothing in CI can look at a Bedrock client. What replaced it is a statement about
 * a character set, and {@link Glyphs} below is that statement being checked: the simple card is
 * rendered through MiniMessage to plain text and every character of the result is compared against
 * the set {@link ProgressCardRenderer#withinBedrockGlyphs} defines.
 */
class ProgressCardTest {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static MilestoneProgress cleared(String displayName) {
        return new MilestoneProgress(
                new Milestone("dimension:" + displayName, displayName, MilestoneRequirement.none()),
                EligibilityResult.pass());
    }

    private static MilestoneProgress missingAdvancement(String displayName, String key) {
        return new MilestoneProgress(
                new Milestone("dimension:" + displayName, displayName, MilestoneRequirement.none()),
                new EligibilityResult(false, List.of(key), List.of(), 0.0D, 0, false));
    }

    private static MilestoneProgress waivedOnly(String displayName) {
        // Outstanding, but with nothing a player could go and do: every remaining requirement was
        // waived because the server cannot resolve it.
        return new MilestoneProgress(
                new Milestone("dimension:" + displayName, displayName, MilestoneRequirement.none()),
                new EligibilityResult(false, List.of(), List.of("minecraft:story/nonexistent"),
                        0.0D, 0, false));
    }

    /** The whole card as one plain-text blob, the way a player would read it. */
    private static String plain(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            Component rendered = MINI.deserialize(line);
            text.append(PLAIN.serialize(rendered)).append('\n');
        }
        return text.toString();
    }

    @Nested
    @DisplayName("rows")
    class Rows {

        @Test
        @DisplayName("a cleared milestone is complete and has nothing outstanding")
        void clearedMilestone() {
            List<Row> rows = ProgressCardRenderer.rows(List.of(cleared("The Nether")));

            assertEquals(List.of(new Row(Status.COMPLETE, "The Nether", Optional.empty())), rows);
        }

        @Test
        @DisplayName("the first uncleared milestone is in progress and the rest are locked")
        void oneInProgressAtATime() {
            List<Row> rows = ProgressCardRenderer.rows(List.of(
                    cleared("The Nether"),
                    missingAdvancement("The End", "minecraft:story/smelt_iron"),
                    missingAdvancement("Beyond", "minecraft:end/root")));

            assertEquals(Status.COMPLETE, rows.get(0).status());
            assertEquals(Status.IN_PROGRESS, rows.get(1).status(),
                    "the first uncleared gate is the one the player can work on");
            assertEquals(Status.LOCKED, rows.get(2).status(),
                    "a gate behind an unopened one is locked, not in progress");
        }

        @Test
        @DisplayName("an outstanding milestone with nothing actionable still gets a row")
        void waivedMilestoneIsNotDropped() {
            // IdleReminderRules#nextStep deliberately says nothing about this case -- a reminder
            // with a blank next step is a nag with no content. A card is different: dropping the
            // row would tell the player the gate does not exist.
            List<Row> rows = ProgressCardRenderer.rows(List.of(waivedOnly("The End")));

            assertEquals(1, rows.size());
            assertEquals(Status.IN_PROGRESS, rows.get(0).status());
            assertEquals(Optional.empty(), rows.get(0).detail());
            assertTrue(plain(ProgressCardRenderer.card("Steve", List.of(waivedOnly("The End")),
                    Style.FULL)).contains("cannot check"));
        }
    }

    @Nested
    @DisplayName("the next step")
    class NextStep {

        @Test
        @DisplayName("the card and the idle reminder agree, because they are the same call")
        void oneImplementation() {
            List<MilestoneProgress> progress = List.of(
                    cleared("The Nether"),
                    missingAdvancement("The End", "minecraft:story/smelt_iron"));

            String step = IdleReminderRules.nextStep(progress).orElseThrow();

            assertTrue(plain(ProgressCardRenderer.card("Steve", progress, Style.FULL))
                            .contains("NEXT STEP: " + step),
                    "the card's next step must be the string the idle reminder would say; a "
                            + "second implementation here is how the two silently diverge");
        }

        @Test
        @DisplayName("the next step is highlighted with the arrow #3 asks for")
        void arrowAndHighlight() {
            List<MilestoneProgress> progress =
                    List.of(missingAdvancement("The End", "minecraft:story/smelt_iron"));
            List<String> lines = ProgressCardRenderer.card("Steve", progress, Style.FULL);
            String last = lines.get(lines.size() - 1);

            assertTrue(last.startsWith("<gold>"), "the highlight colour #3 names is gold");
            assertTrue(last.contains("➔ NEXT STEP: "));
        }

        @Test
        @DisplayName("a player who has cleared everything is congratulated, not handed a blank line")
        void everythingCleared() {
            String card = plain(ProgressCardRenderer.card("Steve",
                    List.of(cleared("The Nether"), cleared("The End")), Style.FULL));

            assertFalse(card.contains("NEXT STEP"));
            assertTrue(card.contains("Every gate on this server is open to you."));
        }

        @Test
        @DisplayName("a server with no gate enabled says so rather than rendering an empty card")
        void noGates() {
            String card = plain(ProgressCardRenderer.card("Steve", List.of(), Style.FULL));

            assertTrue(card.contains("No dimension gate is enabled"));
        }
    }

    @Nested
    @DisplayName("the glyph-safe card — R-21")
    class Glyphs {

        private static final List<MilestoneProgress> EVERY_STATUS = List.of(
                cleared("The Nether"),
                missingAdvancement("The End", "minecraft:story/smelt_iron"),
                missingAdvancement("Beyond", "minecraft:end/root"));

        @Test
        @DisplayName("the simple card contains nothing outside the Bedrock glyph set")
        void simpleCardIsGlyphSafe() {
            // The criterion R-21 asked for, and the whole reason SIMPLE exists: a testable
            // character-set assertion rather than "renders cleanly", which nothing in CI can check.
            // Rendered to plain text first, so this is asserted about what the player sees and not
            // about the MiniMessage source -- a mark hidden inside a tag would pass the latter.
            for (String line : ProgressCardRenderer.card("Steve", EVERY_STATUS, Style.SIMPLE)) {
                String text = PLAIN.serialize(MINI.deserialize(line));
                assertTrue(ProgressCardRenderer.withinBedrockGlyphs(text),
                        "the simple card must stay inside the Bedrock glyph set, but rendered \""
                                + text + "\"");
            }
        }

        @Test
        @DisplayName("every simple-card line of a finished and of a gateless server is safe too")
        void theOtherTwoEndings() {
            List<List<MilestoneProgress>> shapes = List.of(
                    List.of(),
                    List.of(cleared("The Nether")),
                    List.of(waivedOnly("The End")));
            for (List<MilestoneProgress> progress : shapes) {
                for (String line : ProgressCardRenderer.card("Steve", progress, Style.SIMPLE)) {
                    assertTrue(ProgressCardRenderer.withinBedrockGlyphs(
                                    PLAIN.serialize(MINI.deserialize(line))),
                            "a simple card over " + progress + " left the glyph set");
                }
            }
        }

        @Test
        @DisplayName("the full card really does leave the set, so the assertion above means something")
        void fullCardIsNotGlyphSafe() {
            // A negative control. If the marks were ever quietly replaced with ASCII the test
            // above would keep passing while SIMPLE stopped being a distinct mode at all.
            boolean anyOutside = false;
            for (String line : ProgressCardRenderer.card("Steve", EVERY_STATUS, Style.FULL)) {
                anyOutside |= !ProgressCardRenderer.withinBedrockGlyphs(
                        PLAIN.serialize(MINI.deserialize(line)));
            }
            assertTrue(anyOutside, "the full card is supposed to use marks Bedrock lacks");
        }

        @Test
        @DisplayName("the guarantee is about the plugin's own furniture, not the operator's strings")
        void operatorTextPassesThrough() {
            // Stated so nobody reads the criterion as more than it is. A display name read from
            // config.yml is reproduced verbatim, simple card or not; what SIMPLE guarantees is
            // that the card adds nothing of its own outside the set.
            List<MilestoneProgress> exotic = List.of(cleared("Le Néant ✦"));
            String text = plain(ProgressCardRenderer.card("Steve", exotic, Style.SIMPLE));

            assertTrue(text.contains("Le Néant ✦"));
            assertFalse(ProgressCardRenderer.withinBedrockGlyphs(text));
        }

        @Test
        @DisplayName("a display name containing markup is escaped, not parsed")
        void configuredTextIsNotMarkup() {
            String card = plain(ProgressCardRenderer.card("Steve",
                    List.of(cleared("<red>not a tag")), Style.FULL));

            assertTrue(card.contains("<red>not a tag"),
                    "a display name from config.yml must reach the player as text");
        }
    }

    @Nested
    @DisplayName("choosing a style")
    class StyleChoice {

        @Test
        @DisplayName("AUTO follows the client, ALWAYS and NEVER override it")
        void styleFor() {
            assertEquals(Style.SIMPLE, ProgressCardRenderer.styleFor(SimpleCard.AUTO, true));
            assertEquals(Style.FULL, ProgressCardRenderer.styleFor(SimpleCard.AUTO, false));
            assertEquals(Style.SIMPLE, ProgressCardRenderer.styleFor(SimpleCard.ALWAYS, false));
            assertEquals(Style.FULL, ProgressCardRenderer.styleFor(SimpleCard.NEVER, true));
        }

        @Test
        @DisplayName("a Floodgate id has no high bits; a Java player's version-4 id does")
        void floodgateIds() {
            // Floodgate builds a Bedrock player's id as new UUID(0, xuid). A version-4 UUID always
            // carries its version nibble in the high half, so the two cannot collide.
            assertTrue(ProgressCardRenderer.floodgateId(new UUID(0L, 2_535_000_000_000_000L)));
            assertFalse(ProgressCardRenderer.floodgateId(UUID.randomUUID()));
            assertFalse(ProgressCardRenderer.floodgateId(
                    UUID.nameUUIDFromBytes("Steve".getBytes())));
        }
    }
}
