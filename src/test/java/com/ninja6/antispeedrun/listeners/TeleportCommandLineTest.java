package com.ninja6.antispeedrun.listeners;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ninja6.antispeedrun.listeners.TeleportCommandLine.Dimension;
import com.ninja6.antispeedrun.listeners.TeleportCommandLine.Sender;
import com.ninja6.antispeedrun.listeners.TeleportCommandLine.Teleport;
import com.ninja6.antispeedrun.listeners.TeleportCommandLine.ToCoordinates;
import com.ninja6.antispeedrun.listeners.TeleportCommandLine.ToEntity;
import com.ninja6.antispeedrun.listeners.TeleportCommandLine.Token;
import com.ninja6.antispeedrun.listeners.TeleportCommandLine.WorldOf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a vanilla teleport off the command line — #135.
 *
 * <p>The half that matters most is the last nested class: every line this reader cannot be sure
 * of must come back empty, because an empty answer only means the arrival is judged as it was
 * before, while a wrong answer is a note on someone nobody teleported.
 */
class TeleportCommandLineTest {

    private static final Set<String> TP = Set.of(TeleportCommandLine.TELEPORT_PERMISSION);
    private static final Set<String> EXECUTE_TP = Set.of(TeleportCommandLine.TELEPORT_PERMISSION,
            TeleportCommandLine.EXECUTE_PERMISSION);

    private static Teleport read(String line) {
        Optional<Teleport> parsed = TeleportCommandLine.parse(line);
        assertTrue(parsed.isPresent(), () -> "expected to read a teleport from: " + line);
        return parsed.get();
    }

    private static void unreadable(String line, String why) {
        assertTrue(TeleportCommandLine.parse(line).isEmpty(), why + ": " + line);
    }

    @Nested
    @DisplayName("the four shapes of tp")
    class Shapes {

        @Test
        @DisplayName("tp <destination> moves the sender to an entity")
        void toEntity() {
            assertEquals(new Teleport(TP, new Sender(), new ToEntity(new Token("Alex"))), read("/tp Alex"));
        }

        @Test
        @DisplayName("tp <location> moves the sender within the sender's own world")
        void toLocation() {
            assertEquals(new Teleport(TP, new Sender(), new ToCoordinates(new WorldOf(new Sender()))),
                    read("/tp 0 64 0"));
        }

        @Test
        @DisplayName("tp <targets> <destination> moves the targets to an entity")
        void targetsToEntity() {
            assertEquals(new Teleport(TP, new Token("Steve"), new ToEntity(new Token("Alex"))),
                    read("/tp Steve Alex"));
        }

        @Test
        @DisplayName("tp <targets> <location> [rotation] moves the targets within the sender's world")
        void targetsToLocation() {
            Teleport expected = new Teleport(TP, new Token("Steve"),
                    new ToCoordinates(new WorldOf(new Sender())));
            assertEquals(expected, read("/tp Steve ~ ~10 ~-3.5"));
            assertEquals(expected, read("/tp Steve ^ ^ ^1 90 0"));
            assertEquals(expected, read("/tp Steve 0 64 0 facing entity Alex eyes"));
            assertEquals(expected, read("/tp Steve 0 64 0 facing entity Alex"));
            assertEquals(expected, read("/tp Steve 0 64 0 facing 1 2 3"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"tp", "/tp", "/teleport", "/minecraft:tp", "/minecraft:teleport", "/TP"})
        @DisplayName("every label for the vanilla command, which Paper guards with one permission")
        void labels(String label) {
            assertEquals(TP, read(label + " Steve Alex").permissions());
        }

        @Test
        @DisplayName("@s is the sender, and other selectors are left for the caller")
        void selectors() {
            assertEquals(new Teleport(TP, new Sender(), new ToEntity(new Token("@p"))), read("/tp @s @p"));
            assertEquals(new Token("@a[distance=..5,name=\"a b\"]"),
                    read("/tp @a[distance=..5,name=\"a b\"] Alex").targets());
        }
    }

    @Nested
    @DisplayName("tp inside execute")
    class Execute {

        @Test
        @DisplayName("execute in names the dimension of the coordinates")
        void in() {
            assertEquals(new Teleport(EXECUTE_TP, new Sender(),
                            new ToCoordinates(new Dimension("minecraft:the_nether"))),
                    read("/execute in minecraft:the_nether run tp @s 0 70 0"));
            assertEquals(new Dimension("minecraft:the_end"),
                    ((ToCoordinates) read("execute in the_end run tp Steve 0 70 0").destination()).place());
        }

        @Test
        @DisplayName("execute as changes who @s is")
        void as() {
            assertEquals(new Teleport(EXECUTE_TP, new Token("Steve"), new ToEntity(new Token("Alex"))),
                    read("/execute as Steve run tp @s Alex"));
        }

        @Test
        @DisplayName("execute at puts the coordinates in the world of that entity")
        void at() {
            assertEquals(new Teleport(EXECUTE_TP, new Token("Steve"),
                            new ToCoordinates(new WorldOf(new Token("Alex")))),
                    read("/execute at Alex run tp Steve ~ ~ ~"));
            assertEquals(new WorldOf(new Token("Steve")),
                    ((ToCoordinates) read("/execute as Steve at @s run tp @s ~ ~5 ~").destination()).place());
        }

        @Test
        @DisplayName("the later of in and at decides the dimension")
        void lastPlaceWins() {
            assertEquals(new Dimension("minecraft:the_nether"),
                    ((ToCoordinates) read("/execute at Alex in the_nether run tp Steve 0 70 0")
                            .destination()).place());
            assertEquals(new WorldOf(new Token("Alex")),
                    ((ToCoordinates) read("/execute in the_nether at Alex run tp Steve 0 70 0")
                            .destination()).place());
        }

        @Test
        @DisplayName("subcommands that only move or turn the position are stepped over")
        void positionOnly() {
            Teleport expected = new Teleport(EXECUTE_TP, new Sender(),
                    new ToCoordinates(new Dimension("minecraft:the_nether")));
            assertEquals(expected, read("/execute in the_nether positioned 0 70 0 run tp @s ~ ~ ~"));
            assertEquals(expected, read("/execute in the_nether positioned over world_surface run tp @s ~ ~ ~"));
            assertEquals(expected, read("/execute in the_nether rotated 90 0 anchored eyes align xz run tp @s ~ ~ ~"));
            assertEquals(expected, read("/execute in the_nether facing 1 2 3 run tp @s ~ ~ ~"));
        }

        @Test
        @DisplayName("a nested execute is followed through")
        void nested() {
            assertEquals(new Teleport(EXECUTE_TP, new Token("Steve"),
                            new ToCoordinates(new Dimension("minecraft:the_nether"))),
                    read("/execute as Steve run minecraft:execute in the_nether run teleport @s 0 70 0"));
        }

        @Test
        @DisplayName("a selector read before the context moves is still the sender's to resolve")
        void selectorBeforeTheMove() {
            assertEquals(new Token("@a"), read("/execute as @a in the_nether run tp @s 0 70 0").targets());
        }
    }

    @Nested
    @DisplayName("anything not read with certainty is not read at all")
    class Unreadable {

        @ParameterizedTest
        @ValueSource(strings = {"", "/", "/say tp Steve Alex", "/tpa Steve", "/spawn", "/asr bypass Steve 1h"})
        @DisplayName("another command")
        void otherCommands(String line) {
            unreadable(line, "not a teleport");
        }

        @ParameterizedTest
        @ValueSource(strings = {"/tp", "/tp 0 64 0 90 0", "/tp Steve 1 2", "/tp Steve Alex Bob"})
        @DisplayName("a tp vanilla would reject")
        void malformed(String line) {
            unreadable(line, "vanilla has no such shape");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "/execute if entity @s run tp @s 0 70 0",
            "/execute unless block ~ ~ ~ air run tp @s 0 70 0",
            "/execute store result score x y run tp @s 0 70 0",
            "/execute on vehicle run tp @s 0 70 0",
            "/execute summon pig run tp @s 0 70 0",
            "/execute in the_nether",
            "/execute in the_nether run",
            "/execute in the_nether run say hi",
            "/execute in The_Nether run tp @s 0 70 0",
            "/execute IN the_nether run tp @s 0 70 0",
        })
        @DisplayName("an execute this reader does not follow")
        void unfollowedExecute(String line) {
            unreadable(line, "whether or where the teleport runs is not decided by the line alone");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "/execute as Steve run tp @p 0 70 0",
            "/execute at Alex run tp @p 0 70 0",
            "/execute in the_nether run tp @a 0 70 0",
            "/execute positioned 0 0 0 run tp @e[limit=1,sort=nearest] Alex",
            "/execute align xyz run tp Steve @p",
            "/execute at Alex as @p run tp @s 0 70 0",
            "/execute as Steve run tp @s[type=player] 0 70 0",
        })
        @DisplayName("a selector after execute moved the context it is resolved in")
        void selectorAfterTheMove(String line) {
            unreadable(line, "the sender's context is no longer the command's");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "/tp @r Alex",
            "/tp @r[limit=2] 0 70 0",
            "/tp Steve @r",
            "/tp @a[sort=random,limit=1] Alex",
            "/tp @e[type=player, sort = random] Alex",
            "/tp @p[sort=\"random\"] Alex",
            "/tp @a[\"sort\"=random,limit=1] Alex",
            "/tp @a['sort'=random,limit=1] Alex",
            "/tp @a[sort= random,limit=1] Alex",
            "/tp @a[sort=	random,limit=1] Alex",
            "/tp @a[sort= random,limit=1] Alex",
            "/tp @a[sort=　random,limit=1] Alex",
            "/tp @a[sort=RANDOM,limit=1] Alex",
            "/tp @a[Sort=Random,limit=1] Alex",
            "/tp @a[\"sort\" = \"RaNdOm\",limit=1] Alex",
            "/tp @R Alex",
            "/execute in the_nether as @r run tp @s 0 70 0",
            "/execute at @e[sort=random,limit=1] run tp Steve ~ ~ ~",
        })
        @DisplayName("a random selector, which the caller and the command would each draw differently")
        void random(String line) {
            unreadable(line, "the player noted need not be the player teleported");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "/execute in the_nether run TP @s 0 70 0",
            "/execute in the_nether run Teleport @s 0 70 0",
            "/execute in the_nether run MINECRAFT:tp @s 0 70 0",
            "/execute in the_nether run minecraft:TP @s 0 70 0",
            "/execute as Steve run EXECUTE in the_nether run tp @s 0 70 0",
        })
        @DisplayName("a label after run in another case, which Brigadier does not accept")
        void labelCaseAfterRun(String line) {
            unreadable(line, "Brigadier literals are case-sensitive");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "/tp Steve 0 70 0 junk",
            "/tp Steve 0 70 0 90",
            "/tp Steve 0 70 0 90 0 junk",
            "/tp Steve 0 70 0 facing",
            "/tp Steve 0 70 0 facing 1 2",
            "/tp Steve 0 70 0 facing entity Alex head",
            "/tp Steve 0 70 0 facing entity Alex eyes junk",
        })
        @DisplayName("extra tokens after the coordinates")
        void trailingTokens(String line) {
            unreadable(line, "vanilla takes only a rotation or a facing there");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "/execute in the_nether positioned as Alex run tp @s ~ ~ ~",
            "/execute in the_nether rotated as Alex run tp @s ~ ~ ~",
            "/execute in the_nether facing entity Alex feet run tp @s ~ ~ ~",
            "/execute facing entity @r eyes in the_nether run tp @s 0 70 0",
        })
        @DisplayName("an execute subcommand that takes a selector only to move or turn the position")
        void selectorModifiers(String line) {
            unreadable(line, "the selector would have to be read and is not");
        }

        @ParameterizedTest
        @ValueSource(strings = {"/tp @a[name=\"Steve] Alex", "/tp @a[distance=..5 Alex", "/tp @a] Alex"})
        @DisplayName("an unbalanced selector")
        void unbalanced(String line) {
            unreadable(line, "brackets or quotes left open");
        }
    }

    @Test
    @DisplayName("tokens keep a selector's spaces, brackets and quotes together")
    void tokens() {
        assertEquals(Optional.of(List.of("tp", "@e[type=pig, name='a b']", "~", "~", "~")),
                TeleportCommandLine.tokens("tp  @e[type=pig, name='a b'] ~ ~ ~ "));
    }
}
