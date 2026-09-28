package com.ninja6.antispeedrun.listeners;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A vanilla {@code /tp} or {@code /teleport}, read off the command line before it runs — #135.
 *
 * <h2>Why a command line has to be read at all</h2>
 *
 * On Folia a teleport announces nothing. {@code Entity#teleportAsync} carries a
 * {@code TeleportCause} and drops it where Folia's region-threading patch marks
 * {@code TODO any events that can modify go HERE}, and the vanilla teleport command is patched to
 * call it. So a cross-dimension {@code /tp} reaches {@code ProgressionGateListener} only as a
 * player turning up in another world, which is exactly what the unreported vehicle transit looks
 * like. The arrival cannot tell them apart, and the command line is the last point at which the
 * two are still different things: this is the one route of the two an operator types.
 *
 * <p>The reading is deliberately narrow. It recognises the forms an operator uses to move a player
 * between dimensions and gives up on anything else, and giving up costs nothing but the old
 * behaviour: no note is written, so the arrival is judged as before and an ineligible player is
 * returned. A misreading that <em>finds</em> a teleport where the server does not run one is the
 * failure that matters, which is why every rule below errs towards finding nothing.
 *
 * <h2>What is recognised</h2>
 *
 * <ul>
 *   <li>{@code tp} and {@code teleport}, bare or as {@code minecraft:}, in all four of vanilla's
 *       shapes: {@code <destination>}, {@code <location>}, {@code <targets> <destination>} and
 *       {@code <targets> <location> [rotation | facing ...]}.</li>
 *   <li>The same wrapped in {@code execute}, through the subcommands that decide who is moved and
 *       where the coordinates are: {@code in}, {@code as} and {@code at}, and the ones that only
 *       adjust position or rotation ({@code positioned}, {@code rotated}, {@code facing},
 *       {@code anchored}, {@code align}). A conditional ({@code if}, {@code unless}), a
 *       {@code store}, {@code on} or {@code summon} is not followed: whether and on whom the
 *       teleport runs then depends on world state this class cannot see.</li>
 * </ul>
 *
 * <h2>Selectors are resolved by the caller, in the sender's own context</h2>
 *
 * Bukkit resolves a selector as the sender would see it. Once {@code execute} has moved the
 * executor ({@code as}) or the position ({@code at}, {@code in}, {@code positioned},
 * {@code align}), that is no longer the context the command itself runs in, so a selector read then
 * could name a different player. Only an exact {@code @s}, which this class resolves itself, and a
 * plain name or UUID mean the same thing either way; any other selector after such a change makes
 * the line unreadable.
 *
 * <p>Nothing here knows a Bukkit type: resolving a {@link Ref} and a {@link Place} to entities and
 * worlds is the listener's job.
 */
public final class TeleportCommandLine {

    /** What the sender needs, per Paper's vanilla command wrapper, to run {@code tp} or {@code teleport}. */
    public static final String TELEPORT_PERMISSION = "minecraft.command.teleport";

    /** What the sender needs to run {@code execute}. */
    public static final String EXECUTE_PERMISSION = "minecraft.command.execute";

    /** One world coordinate or rotation angle: absolute, or relative with {@code ~}. Never empty. */
    private static final Pattern WORLD = Pattern.compile("~(-?(\\d+\\.?\\d*|\\.\\d+))?|-?(\\d+\\.?\\d*|\\.\\d+)");

    /** One local coordinate, {@code ^}, which vanilla accepts only when all three are local. */
    private static final Pattern LOCAL = Pattern.compile("\\^(-?(\\d+\\.?\\d*|\\.\\d+))?");

    private TeleportCommandLine() {
    }

    /** Who or what a token of the command refers to. */
    public sealed interface Ref permits Sender, Token {
    }

    /** Whoever ran the command. What {@code @s} means until an {@code execute as} changes it. */
    public record Sender() implements Ref {
    }

    /**
     * A player name, a UUID, or a selector for the caller to resolve in the sender's context.
     *
     * @param text the token exactly as typed
     */
    public record Token(String text) implements Ref {

        public Token {
            Objects.requireNonNull(text, "text");
        }
    }

    /** The dimension a coordinate destination is in. */
    public sealed interface Place permits WorldOf, Dimension {
    }

    /**
     * The world {@code entity} is in: the sender's own, until {@code execute at} moves it.
     *
     * @param entity whose world it is
     */
    public record WorldOf(Ref entity) implements Place {

        public WorldOf {
            Objects.requireNonNull(entity, "entity");
        }
    }

    /**
     * A dimension named by {@code execute in}.
     *
     * @param key the dimension's namespaced key, with {@code minecraft:} supplied if it was omitted
     */
    public record Dimension(String key) implements Place {

        public Dimension {
            Objects.requireNonNull(key, "key");
        }
    }

    /** Where the teleport goes. */
    public sealed interface Destination permits ToEntity, ToCoordinates {
    }

    /**
     * To wherever {@code entity} is, which is the world the caller must read off it.
     *
     * @param entity the destination entity
     */
    public record ToEntity(Ref entity) implements Destination {

        public ToEntity {
            Objects.requireNonNull(entity, "entity");
        }
    }

    /**
     * To coordinates in {@code place}.
     *
     * @param place the dimension the coordinates are in
     */
    public record ToCoordinates(Place place) implements Destination {

        public ToCoordinates {
            Objects.requireNonNull(place, "place");
        }
    }

    /**
     * A teleport this class could read.
     *
     * @param permissions every permission the sender needs for the line to run at all
     * @param targets     who is moved; may be a selector naming several players
     * @param destination where they go
     */
    public record Teleport(Set<String> permissions, Ref targets, Destination destination) {

        public Teleport {
            permissions = Set.copyOf(permissions);
            Objects.requireNonNull(targets, "targets");
            Objects.requireNonNull(destination, "destination");
        }
    }

    /**
     * Reads {@code commandLine} as a teleport, if it is one this class recognises.
     *
     * @param commandLine the line as the server received it, with or without a leading {@code /}
     * @return the teleport, or empty for any other command and for any teleport not read with
     *         certainty
     */
    public static Optional<Teleport> parse(String commandLine) {
        Objects.requireNonNull(commandLine, "commandLine");
        String line = commandLine.startsWith("/") ? commandLine.substring(1) : commandLine;
        Optional<List<String>> split = tokens(line);
        if (split.isEmpty()) {
            return Optional.empty();
        }
        List<String> tokens = split.get();
        Set<String> permissions = new LinkedHashSet<>();
        Ref executor = new Sender();
        Place place = new WorldOf(new Sender());
        boolean contextMoved = false;

        int i = 0;
        while (true) {
            if (i >= tokens.size()) {
                return Optional.empty();
            }
            // Only the typed label is case-folded, because that is the one Bukkit's command map
            // looks up in lower case. A label after "run" is read by Brigadier, whose literals are
            // case-sensitive, so "run TP" is not a teleport and must not be read as one.
            String label = stripNamespace(i == 0 ? tokens.get(i).toLowerCase(Locale.ROOT) : tokens.get(i));
            if (label.equals("tp") || label.equals("teleport")) {
                permissions.add(TELEPORT_PERMISSION);
                return teleport(tokens.subList(i + 1, tokens.size()), executor, place, contextMoved)
                        .map(parsed -> new Teleport(permissions, parsed.targets(), parsed.destination()));
            }
            if (!label.equals("execute")) {
                return Optional.empty();
            }
            permissions.add(EXECUTE_PERMISSION);
            i++;
            // The execute chain, up to and including "run".
            boolean ran = false;
            while (!ran) {
                if (i >= tokens.size()) {
                    return Optional.empty();
                }
                String sub = tokens.get(i);
                switch (sub) {
                    case "run" -> {
                        ran = true;
                        i++;
                    }
                    case "in" -> {
                        if (i + 1 >= tokens.size()) {
                            return Optional.empty();
                        }
                        Optional<String> key = dimensionKey(tokens.get(i + 1));
                        if (key.isEmpty()) {
                            return Optional.empty();
                        }
                        place = new Dimension(key.get());
                        contextMoved = true;
                        i += 2;
                    }
                    case "as" -> {
                        if (i + 1 >= tokens.size()) {
                            return Optional.empty();
                        }
                        Optional<Ref> ref = ref(tokens.get(i + 1), executor, contextMoved);
                        if (ref.isEmpty()) {
                            return Optional.empty();
                        }
                        executor = ref.get();
                        contextMoved = contextMoved || !(executor instanceof Sender);
                        i += 2;
                    }
                    case "at" -> {
                        if (i + 1 >= tokens.size()) {
                            return Optional.empty();
                        }
                        Optional<Ref> ref = ref(tokens.get(i + 1), executor, contextMoved);
                        if (ref.isEmpty()) {
                            return Optional.empty();
                        }
                        place = new WorldOf(ref.get());
                        contextMoved = true;
                        i += 2;
                    }
                    case "positioned" -> {
                        int width = positionedWidth(tokens, i + 1);
                        if (width == 0) {
                            return Optional.empty();
                        }
                        contextMoved = true;
                        i += 1 + width;
                    }
                    case "align" -> {
                        contextMoved = true;
                        i += 2;
                    }
                    case "anchored" -> i += 2;
                    // The forms that take a selector ("rotated as", "facing entity") are not
                    // followed: that selector would have to be read too, and nothing here needs it.
                    case "rotated" -> {
                        if (i + 2 >= tokens.size() || tokens.get(i + 1).equals("as")) {
                            return Optional.empty();
                        }
                        i += 3;
                    }
                    case "facing" -> {
                        if (!coordinates(tokens, i + 1)) {
                            return Optional.empty();
                        }
                        i += 4;
                    }
                    default -> {
                        return Optional.empty();
                    }
                }
            }
        }
    }

    /** What {@code tp} is given after its label, in whichever of its four shapes. */
    private static Optional<Teleport> teleport(List<String> args, Ref executor, Place place,
                                               boolean contextMoved) {
        int n = args.size();
        if (n == 0) {
            return Optional.empty();
        }
        if (n >= 3 && coordinates(args, 0)) {
            // <location>: the executor to coordinates. Vanilla takes nothing after it.
            return n == 3
                    ? Optional.of(new Teleport(Set.of(), executor, new ToCoordinates(place)))
                    : Optional.empty();
        }
        Optional<Ref> first = ref(args.get(0), executor, contextMoved);
        if (first.isEmpty()) {
            return Optional.empty();
        }
        if (n == 1) {
            // <destination>: the executor to an entity.
            return Optional.of(new Teleport(Set.of(), executor, new ToEntity(first.get())));
        }
        if (n == 2) {
            // <targets> <destination>.
            return ref(args.get(1), executor, contextMoved)
                    .map(to -> new Teleport(Set.of(), first.get(), new ToEntity(to)));
        }
        if (coordinates(args, 1) && tail(args.subList(4, n))) {
            // <targets> <location> and the rotation or facing vanilla allows after it, none of
            // which moves the destination into another dimension.
            return Optional.of(new Teleport(Set.of(), first.get(), new ToCoordinates(place)));
        }
        return Optional.empty();
    }

    /**
     * Whether what follows {@code <targets> <location>} is one of vanilla's three tails: nothing, a
     * rotation, {@code facing <x y z>}, or {@code facing entity <entity> [eyes|feet]}. The entity
     * there only turns the player, so it is checked for shape and not read.
     */
    private static boolean tail(List<String> rest) {
        int n = rest.size();
        if (n == 0) {
            return true;
        }
        if (n == 2) {
            // A rotation takes no local ("^") component.
            return WORLD.matcher(rest.get(0)).matches() && WORLD.matcher(rest.get(1)).matches();
        }
        if (n < 3 || !rest.get(0).equals("facing")) {
            return false;
        }
        if (n == 4 && coordinates(rest, 1)) {
            return true;
        }
        return rest.get(1).equals("entity")
                && (n == 3 || (n == 4 && (rest.get(3).equals("eyes") || rest.get(3).equals("feet"))));
    }

    /**
     * A token that names an entity, as the command will read it.
     *
     * <p>An exact {@code @s} is the current executor. Any other selector is left for the caller to
     * resolve in the sender's context, which is only the command's context while {@code execute}
     * has moved neither the executor nor the position.
     *
     * <p>A random selector — {@code @r}, or any selector sorted {@code random} — is never read. The
     * caller would draw its own sample, and the command draws another: the note would land on a
     * player the server did not teleport, where an unreported transit could spend it.
     */
    private static Optional<Ref> ref(String token, Ref executor, boolean contextMoved) {
        if (token.equals("@s")) {
            return Optional.of(executor);
        }
        if (random(token)) {
            return Optional.empty();
        }
        if (token.startsWith("@") && contextMoved) {
            return Optional.empty();
        }
        return Optional.of(new Token(token));
    }

    /**
     * Whether a selector could pick at random, so that two resolutions of it can disagree.
     *
     * <p>Deliberately blunt: any selector whose text contains {@code random} in any case, however it
     * is quoted or spaced, and {@code @r}; and any selector naming a {@code predicate}, which a
     * datapack can make random. Brigadier accepts quoted option names and several kinds of
     * whitespace around {@code =}, and a narrower match was bypassed by exactly those. A selector
     * refused here that was not random ({@code name=random}) costs only the note.
     */
    private static boolean random(String token) {
        String folded = token.toLowerCase(Locale.ROOT);
        return folded.startsWith("@") && (folded.startsWith("@r") || folded.contains("random")
                // A predicate can be random too -- a datapack's random_chance -- and which
                // predicates exist is not visible from here.
                || folded.contains("predicate"));
    }

    /**
     * Whether the three tokens from {@code from} are a coordinate triple as vanilla reads one:
     * all three local ({@code ^}), or none of them.
     */
    private static boolean coordinates(List<String> args, int from) {
        if (args.size() < from + 3) {
            return false;
        }
        List<String> triple = args.subList(from, from + 3);
        return triple.stream().allMatch(token -> LOCAL.matcher(token).matches())
                || triple.stream().allMatch(token -> WORLD.matcher(token).matches());
    }

    /** How many tokens {@code execute positioned} takes, or 0 if it cannot be read. */
    private static int positionedWidth(List<String> tokens, int from) {
        if (from >= tokens.size()) {
            return 0;
        }
        String next = tokens.get(from);
        if (next.equals("over")) {
            return 2;
        }
        return coordinates(tokens, from) ? 3 : 0;
    }

    /** A dimension argument as a namespaced key, or empty if it is not one. */
    private static Optional<String> dimensionKey(String token) {
        if (!token.matches("([a-z0-9_.-]+:)?[a-z0-9_./-]+")) {
            return Optional.empty();
        }
        return Optional.of(token.indexOf(':') >= 0 ? token : "minecraft:" + token);
    }

    private static String stripNamespace(String label) {
        return label.startsWith("minecraft:") ? label.substring("minecraft:".length()) : label;
    }

    /**
     * Splits a command line on spaces, keeping a selector's brackets, braces and quoted strings in
     * one token, as Brigadier reads them.
     *
     * @return empty if a bracket or quote is left open
     */
    static Optional<List<String>> tokens(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int k = 0; k < line.length(); k++) {
            char c = line.charAt(k);
            if (quote != 0) {
                current.append(c);
                if (c == '\\' && k + 1 < line.length()) {
                    current.append(line.charAt(++k));
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                current.append(c);
            } else if (c == '[' || c == '{') {
                depth++;
                current.append(c);
            } else if (c == ']' || c == '}') {
                depth--;
                if (depth < 0) {
                    return Optional.empty();
                }
                current.append(c);
            } else if (c == ' ' && depth == 0) {
                if (!current.isEmpty()) {
                    out.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (quote != 0 || depth != 0) {
            return Optional.empty();
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return Optional.of(out);
    }
}
