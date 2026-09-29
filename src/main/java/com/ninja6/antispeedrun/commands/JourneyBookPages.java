package com.ninja6.antispeedrun.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.ninja6.antispeedrun.config.PluginConfig;
import com.ninja6.antispeedrun.config.PluginConfig.ItemTier;
import com.ninja6.antispeedrun.progression.Milestone;
import com.ninja6.antispeedrun.progression.MilestoneRequirement;

import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * The text of the Journey Guide Book, Task 2.2.2 (#5): one MiniMessage string per page, generated
 * from the live configuration snapshot.
 *
 * <p>Generated rather than written into {@code config.yml} because the book describes the rules the
 * server is actually enforcing. A hand-written book is correct on the day it is written and wrong
 * after the first edit to a gate; this one is rebuilt from the snapshot every time a copy is handed
 * out, so an {@code /asr reload} or a profile change is reflected in the next copy without anyone
 * remembering to update a second file.
 *
 * <p>Bukkit-free, like {@link ProgressCardRenderer}: {@code paper-api} is {@code compileOnly}, so
 * this split is what lets the content and the page layout be unit tested. {@link JourneyBookCommand}
 * turns these strings into a {@code BookMeta}.
 *
 * <h2>What goes in, and what stays out</h2>
 *
 * Only rules this build enforces: the two dimension gates, the item tiers, the early Eye of Ender
 * rule and the Mending trade gate, each only while it is switched on. Features that are configured
 * but not implemented yet (boss scaling, trim progression) are left out on purpose — a guide that
 * promises a rule the server does not apply is worse than one that says nothing about it.
 *
 * <p>Everything read from configuration is escaped before it is placed in a page: tier ids and
 * hints are plain text, and a {@code <} in one must reach the reader as a character rather than
 * be applied as markup. The configured title is the exception and is MiniMessage by contract.
 *
 * <h2>Advancement names</h2>
 *
 * A vanilla advancement is written as a {@code <lang>} tag on its title translation key, so the
 * reader's own client renders "Acquire Hardware" in their own language rather than the plugin
 * printing {@code minecraft:story/smelt_iron}. A key in any other namespace is shown as the key:
 * its translation key is whatever its datapack chose, and guessing one would print a raw
 * translation key instead of anything readable.
 */
public final class JourneyBookPages {

    /**
     * Lines a written book page shows before the text runs off the bottom. Vanilla lays out
     * fourteen lines of the default font on a page.
     */
    public static final int LINES_PER_PAGE = 14;

    /**
     * Characters per line the layout assumes. A page is 114 pixels wide and an average glyph of
     * the default font is six, so nineteen fit; eighteen leaves room for the wider glyphs and for
     * bold, which adds a pixel to each one. Too low wastes some space, too high hides text.
     */
    public static final int CHARS_PER_LINE = 18;

    /**
     * Width assumed for a {@code <lang>} advancement title, whose text is only known on the
     * client. With the bullet in front, that budgets two lines for every advancement: a vanilla
     * title such as "A Terrible Fortress" does not fit on one line beside it, and none needs three.
     */
    static final int ADVANCEMENT_TITLE_WIDTH = CHARS_PER_LINE;

    /** Most characters a written book title may hold; longer ones are refused by the item. */
    public static final int MAX_TITLE_LENGTH = 32;

    /** Most pages a written book may hold. */
    public static final int MAX_PAGES = 100;

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final String HEADING = "<dark_purple><bold>";
    private static final String BODY = "<black>";
    private static final String NOTE = "<dark_gray>";

    private JourneyBookPages() {
    }

    /**
     * One paragraph of a page: the MiniMessage that is rendered, and the text it renders as, which
     * is what the layout measures.
     */
    record Paragraph(String markup, String plain) {
        Paragraph {
            Objects.requireNonNull(markup, "markup");
            Objects.requireNonNull(plain, "plain");
        }

        /** A paragraph whose rendered text is exactly {@code plain}, styled with {@code style}. */
        static Paragraph text(String style, String plain) {
            return new Paragraph(style + MINI.escapeTags(plain), plain);
        }

        boolean blank() {
            return plain.isEmpty();
        }

        /**
         * Lines this paragraph takes on a page of {@link #CHARS_PER_LINE} characters, wrapped at
         * spaces the way the client wraps it: a word that does not fit moves to the next line, and a
         * word longer than a whole line is broken.
         */
        int lines() {
            int lines = 1;
            int column = 0;
            for (String word : plain.split(" ", -1)) {
                int length = word.length();
                int needed = column == 0 ? length : column + 1 + length;
                if (needed <= CHARS_PER_LINE) {
                    column = needed;
                    continue;
                }
                if (column > 0) {
                    lines++;
                }
                while (length > CHARS_PER_LINE) {
                    lines++;
                    length -= CHARS_PER_LINE;
                }
                column = length;
            }
            return lines;
        }
    }

    /**
     * Every page, in reading order, as MiniMessage.
     *
     * @param config the snapshot the book describes
     * @return at least one page, and never more than {@link #MAX_PAGES}
     */
    public static List<String> pages(PluginConfig config) {
        Objects.requireNonNull(config, "config");
        List<List<Paragraph>> sections = new ArrayList<>();
        sections.add(cover(config));
        sections.add(dimensions(config));
        items(config).ifPresent(sections::add);
        otherRules(config).ifPresent(sections::add);
        sections.add(commands());

        List<String> pages = new ArrayList<>();
        for (List<Paragraph> section : sections) {
            pages.addAll(layout(section));
        }
        return pages.size() > MAX_PAGES ? List.copyOf(pages.subList(0, MAX_PAGES)) : List.copyOf(pages);
    }

    /**
     * The configured title as the book item stores it: plain text, at most
     * {@link #MAX_TITLE_LENGTH} characters.
     *
     * <p>A written book's title is a plain string, and one longer than 32 characters is rejected by
     * the item rather than shortened, which would hand out a book with no title at all. The styled
     * form is still shown, as the item's name; this is only the stored title.
     */
    public static String plainTitle(String miniMessageTitle) {
        String plain = MINI.stripTags(Objects.requireNonNull(miniMessageTitle, "miniMessageTitle")).strip();
        if (plain.isEmpty()) {
            plain = "Journey Guide";
        }
        return plain.length() > MAX_TITLE_LENGTH ? plain.substring(0, MAX_TITLE_LENGTH) : plain;
    }

    // -----------------------------------------------------------------------------------------
    // Sections
    // -----------------------------------------------------------------------------------------

    private static List<Paragraph> cover(PluginConfig config) {
        List<Paragraph> page = new ArrayList<>();
        String title = config.journeyBook().title();
        page.add(new Paragraph("<bold>" + title + "</bold>", MINI.stripTags(title)));
        page.add(Paragraph.text(BODY, ""));
        page.add(Paragraph.text(BODY, "This server opens the game in stages. The pages that "
                + "follow list what each stage asks of you."));
        page.add(Paragraph.text(BODY, ""));
        page.add(Paragraph.text(NOTE, "Run /progress to see where you stand."));
        return page;
    }

    private static List<Paragraph> dimensions(PluginConfig config) {
        List<Paragraph> section = new ArrayList<>();
        section.add(Paragraph.text(HEADING, "Dimensions"));
        List<Milestone> gates = Milestone.dimensionGates(config);
        if (gates.isEmpty()) {
            section.add(Paragraph.text(BODY, "The Nether and The End are open from the start."));
            return section;
        }
        for (Milestone gate : gates) {
            section.add(Paragraph.text(BODY, ""));
            section.add(Paragraph.text("<dark_blue><bold>", gate.displayName()));
            section.addAll(requirement(gate.requirement()));
        }
        if (gates.size() == 1) {
            String open = gates.get(0).id().equals(Milestone.NETHER_ID) ? "The End" : "The Nether";
            section.add(Paragraph.text(BODY, ""));
            section.add(Paragraph.text(NOTE, open + " is not gated."));
        }
        return section;
    }

    private static Optional<List<Paragraph>> items(PluginConfig config) {
        PluginConfig.ItemProgression items = config.itemProgression();
        if (!items.enabled() || items.gatedItems().isEmpty()) {
            return Optional.empty();
        }
        List<Paragraph> section = new ArrayList<>();
        section.add(Paragraph.text(HEADING, "Items"));
        section.add(Paragraph.text(NOTE, "You cannot pick up gated items until you unlock "
                + "their tier."));
        for (ItemTier tier : items.gatedItems()) {
            section.add(Paragraph.text(BODY, ""));
            section.add(Paragraph.text("<dark_blue><bold>", tierName(tier.id())));
            if (!tier.hint().isBlank()) {
                section.add(Paragraph.text(BODY, tier.hint()));
            } else {
                section.addAll(requirement(MilestoneRequirement.of(tier)));
            }
        }
        return Optional.of(section);
    }

    private static Optional<List<Paragraph>> otherRules(PluginConfig config) {
        List<Paragraph> rules = new ArrayList<>();
        Milestone.earlyEyeThrowAdvancement(config).ifPresent(key -> {
            rules.add(Paragraph.text(BODY, ""));
            rules.add(Paragraph.text("<dark_blue><bold>", "Eyes of Ender"));
            rules.add(Paragraph.text(BODY, "An Eye of Ender will not fly until you have earned:"));
            rules.add(advancement(key));
        });
        if (config.villagerProgression().gateMendingTrade()) {
            rules.add(Paragraph.text(BODY, ""));
            rules.add(Paragraph.text("<dark_blue><bold>", "Mending"));
            rules.add(Paragraph.text(BODY, "Villagers will not trade Mending until you:"));
            String hint = config.villagerProgression().hint();
            if (!hint.isBlank()) {
                rules.add(Paragraph.text(BODY, "- " + hint));
            } else {
                Milestone.villagerTradeAdvancement(config)
                        .ifPresent(key -> rules.add(advancement(key)));
            }
        }
        if (rules.isEmpty()) {
            return Optional.empty();
        }
        List<Paragraph> section = new ArrayList<>(rules.size() + 1);
        section.add(Paragraph.text(HEADING, "Other rules"));
        section.addAll(rules);
        return Optional.of(section);
    }

    private static List<Paragraph> commands() {
        return List.of(
                Paragraph.text(HEADING, "Commands"),
                Paragraph.text(BODY, ""),
                Paragraph.text("<dark_blue>", "/progress"),
                Paragraph.text(BODY, "Your stages, and what is next."),
                Paragraph.text(BODY, ""),
                Paragraph.text("<dark_blue>", "/journeybook"),
                Paragraph.text(BODY, "A new copy of this book."));
    }

    // -----------------------------------------------------------------------------------------
    // Requirement lines
    // -----------------------------------------------------------------------------------------

    /** One line per requirement; "Open from the start" when there is none. */
    static List<Paragraph> requirement(MilestoneRequirement requirement) {
        List<Paragraph> lines = new ArrayList<>();
        if (requirement.isEmpty()) {
            lines.add(Paragraph.text(BODY, "Open from the start."));
            return lines;
        }
        for (String key : requirement.advancements()) {
            lines.add(advancement(key));
        }
        if (requirement.playtimeHours() > 0.0D) {
            lines.add(Paragraph.text(BODY, "- Play " + hours(requirement.playtimeHours())));
        }
        if (requirement.accountAgeDays() > 0) {
            int days = requirement.accountAgeDays();
            lines.add(Paragraph.text(BODY, "- " + days + (days == 1 ? " day" : " days")
                    + " on this server"));
        }
        return lines;
    }

    /**
     * A bullet naming one advancement. See the class comment for why a vanilla key becomes a
     * {@code <lang>} tag and anything else is printed as written.
     */
    static Paragraph advancement(String key) {
        Optional<String> translation = translationKey(key);
        if (translation.isPresent()) {
            return new Paragraph(BODY + "- <dark_green><lang:" + translation.get() + ">",
                    "- " + "x".repeat(ADVANCEMENT_TITLE_WIDTH));
        }
        return Paragraph.text(BODY, "- " + key);
    }

    /**
     * The title translation key of a vanilla advancement: {@code minecraft:story/smelt_iron} is
     * {@code advancements.story.smelt_iron.title}.
     *
     * @return empty for a key outside the {@code minecraft} namespace, or one that is not shaped
     *         like a vanilla advancement path
     */
    static Optional<String> translationKey(String key) {
        String prefix = "minecraft:";
        if (key == null || !key.startsWith(prefix)) {
            return Optional.empty();
        }
        String path = key.substring(prefix.length());
        if (path.isEmpty() || !path.matches("[a-z0-9_]+(/[a-z0-9_]+)+")) {
            return Optional.empty();
        }
        return Optional.of("advancements." + path.replace('/', '.') + ".title");
    }

    /** {@code iron-tier} as a reader would write it: {@code Iron Tier}. */
    static String tierName(String id) {
        StringBuilder name = new StringBuilder(id.length());
        boolean startOfWord = true;
        for (char c : id.toCharArray()) {
            if (c == '-' || c == '_' || c == ' ') {
                if (name.length() > 0 && name.charAt(name.length() - 1) != ' ') {
                    name.append(' ');
                }
                startOfWord = true;
            } else {
                name.append(startOfWord ? Character.toUpperCase(c) : c);
                startOfWord = false;
            }
        }
        String result = name.toString().strip();
        return result.isEmpty() ? id : result;
    }

    private static String hours(double hours) {
        if (hours < 1.0D) {
            long minutes = Math.max(1L, Math.round(hours * 60.0D));
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        if (hours == Math.rint(hours)) {
            long whole = (long) hours;
            return whole + (whole == 1 ? " hour" : " hours");
        }
        return String.format(Locale.ROOT, "%.1f hours", hours);
    }

    // -----------------------------------------------------------------------------------------
    // Layout
    // -----------------------------------------------------------------------------------------

    /**
     * Packs one section's paragraphs onto as many pages as it needs. A section always starts on a
     * fresh page, a paragraph is never split across two, and a blank line that would open or close
     * a page is dropped rather than wasting a line of it.
     *
     * <p>A single paragraph taller than a page — only possible from a very long configured hint —
     * gets a page of its own and overflows it. Cutting it would lose the end of the only sentence
     * that tells a player how to unlock the tier.
     */
    static List<String> layout(List<Paragraph> section) {
        List<String> pages = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        int used = 0;
        boolean pendingBlank = false;
        for (Paragraph paragraph : section) {
            if (paragraph.blank()) {
                // Held back until the next paragraph is placed, so a blank line never opens a page
                // and never closes one.
                pendingBlank = used > 0;
                continue;
            }
            int lines = paragraph.lines();
            int gap = pendingBlank ? 1 : 0;
            if (used > 0 && used + gap + lines > LINES_PER_PAGE) {
                pages.add(page.toString());
                page.setLength(0);
                used = 0;
                gap = 0;
            }
            if (used > 0) {
                page.append("<reset>\n");
                if (gap > 0) {
                    page.append('\n');
                }
            }
            page.append(paragraph.markup());
            used += gap + lines;
            pendingBlank = false;
        }
        if (used > 0) {
            pages.add(page.toString());
        }
        return pages;
    }
}
