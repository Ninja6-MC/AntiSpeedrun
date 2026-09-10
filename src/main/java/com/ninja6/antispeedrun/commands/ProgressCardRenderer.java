package com.ninja6.antispeedrun.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.ninja6.antispeedrun.config.PluginConfig.SimpleCard;
import com.ninja6.antispeedrun.progression.IdleReminderRules;
import com.ninja6.antispeedrun.progression.IdleReminderRules.MilestoneProgress;

import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * The {@code /progress} card, as MiniMessage lines, decided from values alone — Task 2.1.2 (#3).
 *
 * <p>The same split {@link com.ninja6.antispeedrun.progression.IdleReminderRules} makes against its
 * engine, and for the same reason: {@code paper-api} is {@code compileOnly}, so anything that needs
 * a {@code Player} or a {@code CommandSender} cannot be tested at all in this build.
 * {@link ProgressCommand} reads the player and sends what this class returns; everything worth
 * asserting about the card — which mark each milestone gets, what the next step says, and which
 * characters the glyph-safe shape is allowed to contain — is asserted here.
 *
 * <h2>The next step is not computed twice</h2>
 *
 * The {@code ➔ NEXT STEP:} line and the per-row detail both come from
 * {@link IdleReminderRules#nextStep(List)} and {@link IdleReminderRules#outstanding} rather than
 * from a second copy of that logic living here. A card and an idle reminder that disagreed about
 * what a player owes a gate would be a real bug, and the ordinary way that happens is a second
 * implementation written because the first returned the wrong shape. So the shape moved instead.
 *
 * <h2>Two styles, because Bedrock's font is not Java's — finding R-21</h2>
 *
 * #3 as written asked for a card of status marks and arrows that "renders cleanly across Java and
 * Bedrock (Geyser) clients". The audit rejected that as both probably false and not checkable:
 * Bedrock's default font does not carry {@code ✔}, {@code ⏳}, {@code ❌} or {@code ➔}, and what
 * Geyser substitutes for them is not something CI can assert. {@link Style#SIMPLE} is the
 * resolution — the same card in characters Bedrock definitely has — and
 * {@link #withinBedrockGlyphs(String)} is the criterion, which is a statement about a character set
 * and so can be tested rather than looked at.
 */
public final class ProgressCardRenderer {

    /** Lowest character {@link #withinBedrockGlyphs} admits: the space. */
    public static final char BEDROCK_LOWEST = 0x20;

    /** Highest character {@link #withinBedrockGlyphs} admits: the tilde. */
    public static final char BEDROCK_HIGHEST = 0x7E;

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private ProgressCardRenderer() {
    }

    /** Which of the two shapes the card is drawn in. */
    public enum Style {

        /** Status marks and an arrow. What a Java client sees. */
        FULL,

        /** The glyph-safe shape: nothing outside {@link #withinBedrockGlyphs}. */
        SIMPLE
    }

    /**
     * What one milestone row says about itself.
     *
     * <p>{@link #IN_PROGRESS} is the milestone the player is actually working on, and there is at
     * most one: the first that is not yet cleared. Everything uncleared behind it is
     * {@link #LOCKED}, which is the honest reading of a progression the plugin walks in configured
     * order — the End gate is not something a player can make progress on while the Nether gate is
     * still shut.
     */
    public enum Status { COMPLETE, IN_PROGRESS, LOCKED }

    /**
     * One rendered line's worth of facts, before any styling.
     *
     * @param status    which mark the row gets
     * @param milestone the milestone's configured display name
     * @param detail    what it is waiting on, or empty for a cleared milestone
     */
    public record Row(Status status, String milestone, Optional<String> detail) {
        public Row {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(milestone, "milestone");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /**
     * The milestones as rows, in configured order.
     *
     * <p>A milestone that is outstanding but has nothing actionable — every remaining requirement
     * waived because this server cannot resolve it — still gets a row, with no detail. That case
     * is the one {@link IdleReminderRules#nextStep(List)} deliberately says nothing about, because
     * a reminder with a blank next step is a nag with no content. A card is different: leaving the
     * milestone out entirely would tell the player it does not exist.
     */
    public static List<Row> rows(List<MilestoneProgress> progress) {
        Objects.requireNonNull(progress, "progress");
        List<Row> rows = new ArrayList<>(progress.size());
        boolean seenOutstanding = false;
        for (MilestoneProgress entry : progress) {
            if (entry.result().eligible()) {
                rows.add(new Row(Status.COMPLETE, entry.milestone().displayName(), Optional.empty()));
                continue;
            }
            Status status = seenOutstanding ? Status.LOCKED : Status.IN_PROGRESS;
            seenOutstanding = true;
            rows.add(new Row(status, entry.milestone().displayName(),
                    IdleReminderRules.outstanding(entry.result())));
        }
        return List.copyOf(rows);
    }

    /**
     * The whole card, one MiniMessage string per line, ready to deserialise and send.
     *
     * <p>Everything interpolated — milestone display names, advancement keys, the next step — is
     * escaped first. All three are read from {@code config.yml}, so all three can contain a
     * {@code <}, and a card that deserialised them as markup would let an operator's typo mangle
     * the line or inject formatting. Same split as {@code IdleReminderRules#template}: the
     * plugin's own markup is markup, everything that came from outside is text.
     *
     * @param playerName the viewer's name, for the header
     * @param progress   every milestone with the verdict on it, in configured order
     * @param style      which shape to draw
     */
    public static List<String> card(String playerName, List<MilestoneProgress> progress, Style style) {
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(style, "style");
        List<Row> rows = rows(progress);

        List<String> lines = new ArrayList<>(rows.size() + 3);
        lines.add(header(playerName));
        if (rows.isEmpty()) {
            lines.add("  <gray>No dimension gate is enabled on this server, so there is nothing "
                    + "left to unlock.");
            return List.copyOf(lines);
        }
        for (Row row : rows) {
            lines.add(line(row, style));
        }
        IdleReminderRules.nextStep(progress)
                .ifPresentOrElse(step -> lines.add(nextStep(step, style)),
                        () -> lines.add(finished(style)));
        return List.copyOf(lines);
    }

    /**
     * The card's one heading.
     *
     * <p>The same in both styles, and that is R-21's other half: the resolution it offered was
     * "drop the box frame in favour of colour and indentation, <em>or</em> add a simple-card
     * toggle", and there is no reason not to do both. A frame around a chat card is the part
     * Bedrock renders worst and the part that buys least, so nothing here draws one — the rows are
     * indented two spaces and the marks carry the colour.
     */
    private static String header(String playerName) {
        return "<gold><bold>Progression<reset> <dark_gray>|<reset> <white>" + escape(playerName);
    }

    private static String line(Row row, Style style) {
        String mark = mark(row.status(), style);
        String colour = switch (row.status()) {
            case COMPLETE -> "<green>";
            case IN_PROGRESS -> "<yellow>";
            case LOCKED -> "<red>";
        };
        String detail = row.detail()
                .map(text -> " <dark_gray>-<reset> <gray>" + escape(text))
                .orElseGet(() -> switch (row.status()) {
                    case COMPLETE -> " <dark_gray>-<reset> <gray>unlocked";
                    // No detail on an uncleared milestone means every remaining requirement was
                    // waived as unresolvable. Saying so beats a bare name with nothing after it.
                    default -> " <dark_gray>-<reset> <gray>waiting on a requirement this server "
                            + "cannot check";
                });
        return "  " + colour + mark + "<reset> <white>" + escape(row.milestone()) + detail;
    }

    private static String mark(Status status, Style style) {
        if (style == Style.SIMPLE) {
            return switch (status) {
                case COMPLETE -> "[x]";
                case IN_PROGRESS -> "[>]";
                case LOCKED -> "[ ]";
            };
        }
        return switch (status) {
            case COMPLETE -> "✔";
            case IN_PROGRESS -> "⏳";
            case LOCKED -> "❌";
        };
    }

    private static String nextStep(String step, Style style) {
        String arrow = style == Style.FULL ? "➔ " : "-> ";
        return "<gold>" + arrow + "NEXT STEP: <white>" + escape(step);
    }

    private static String finished(Style style) {
        String mark = style == Style.FULL ? "✔ " : "";
        return "<green>" + mark + "Every gate on this server is open to you.";
    }

    /**
     * Whether every character of {@code text} is one a Bedrock client is certain to have a glyph
     * for.
     *
     * <p>Printable ASCII, and deliberately narrower than Bedrock's actual font, which carries a
     * good deal of Latin-1 besides. The point of finding R-21 is that "renders cleanly" is not a
     * criterion anything can check, so what replaces it has to be a set that is written down. A
     * conservative set is checkable and a generous one is an argument about which Geyser version
     * and which resource pack; this is the former.
     *
     * <p>Note what it is <em>not</em>: a claim about the operator's own strings. Milestone display
     * names and advancement keys are read from {@code config.yml} and are reproduced verbatim, so
     * a card built over a display name containing an emoji contains that emoji. What the simple
     * style guarantees is that the card adds nothing of its own outside this set — which is the
     * half the plugin controls, and the half R-21 was about.
     */
    public static boolean withinBedrockGlyphs(String text) {
        Objects.requireNonNull(text, "text");
        for (int i = 0; i < text.length(); i++) {
            char at = text.charAt(i);
            if (at < BEDROCK_LOWEST || at > BEDROCK_HIGHEST) {
                return false;
            }
        }
        return true;
    }

    /**
     * The style to draw for one viewer.
     *
     * @param setting     {@code progress-card.simple-card}
     * @param bedrock     whether the viewer is on a Bedrock client
     */
    public static Style styleFor(SimpleCard setting, boolean bedrock) {
        Objects.requireNonNull(setting, "setting");
        return switch (setting) {
            case ALWAYS -> Style.SIMPLE;
            case NEVER -> Style.FULL;
            case AUTO -> bedrock ? Style.SIMPLE : Style.FULL;
        };
    }

    /**
     * Whether a player id is one Floodgate minted for a Bedrock client.
     *
     * <p>Floodgate has no compile dependency here — it is a {@code softdepend} in
     * {@code plugin.yml} and nothing more — so this reads the one property of a Bedrock player that
     * is visible without its API: Floodgate builds the player's id as {@code new UUID(0, xuid)},
     * so the high 64 bits are zero. A Java player's id is a version-4 UUID, whose high bits carry
     * the version nibble and are therefore never zero.
     *
     * <p>Only consulted when Floodgate is actually enabled — {@link ProgressCommand} checks that
     * first — because without it the server has no Bedrock players to mistake anything for, and a
     * bare bit test on every id is a heuristic looking for a reason to be wrong. An operator whose
     * setup this reads incorrectly has {@code simple-card: ALWAYS} and {@code NEVER}; that is what
     * they are for.
     */
    public static boolean floodgateId(UUID id) {
        return Objects.requireNonNull(id, "id").getMostSignificantBits() == 0L;
    }

    /** Neutralises MiniMessage markup in text that came from configuration or from a player. */
    private static String escape(String raw) {
        return MINI.escapeTags(raw);
    }
}
