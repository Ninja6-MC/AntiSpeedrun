package com.ninja6.antispeedrun.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ninja6.antispeedrun.commands.CooldownArgument.Action;
import com.ninja6.antispeedrun.commands.CooldownArgument.Invalid;
import com.ninja6.antispeedrun.commands.CooldownArgument.Reason;
import com.ninja6.antispeedrun.commands.CooldownArgument.Request;

/** {@code /asr cooldown <show|clear> <player>} (#272). */
class CooldownArgumentTest {

    private static CooldownArgument parse(String... args) {
        return CooldownArgument.parse(args);
    }

    @Test
    @DisplayName("show and clear, in any case, name a player")
    void parses() {
        assertEquals(new Request(Action.SHOW, "Steve"), parse("cooldown", "show", "Steve"));
        assertEquals(new Request(Action.CLEAR, "Steve"), parse("cooldown", "CLEAR", " Steve "));
    }

    @Test
    @DisplayName("a missing or unknown word is refused and changes nothing")
    void refuses() {
        assertEquals(new Invalid(Reason.MISSING_ACTION, ""), parse("cooldown"));
        assertEquals(new Invalid(Reason.UNKNOWN_ACTION, "reset"), parse("cooldown", "reset", "Steve"));
        assertEquals(new Invalid(Reason.MISSING_PLAYER, ""), parse("cooldown", "clear"));
        assertEquals(new Invalid(Reason.MISSING_PLAYER, ""), parse("cooldown", "clear", " "));
        assertEquals(new Invalid(Reason.EXTRA_ARGUMENT, "now"), parse("cooldown", "clear", "Steve", "now"));
    }

    @Test
    @DisplayName("cooldown is gated on antispeedrun.admin.cooldown, an op-default child of antispeedrun.admin")
    void gated() {
        assertEquals(Subcommand.COOLDOWN, Subcommand.parse("Cooldown").orElseThrow());
        assertEquals("antispeedrun.admin.cooldown", Subcommand.COOLDOWN.permission());
        assertTrue(Subcommand.COOLDOWN.administrative());
        assertTrue(PluginYml.childrenOf("antispeedrun.admin").contains("antispeedrun.admin.cooldown"));
        assertFalse(PluginYml.reachableFrom("antispeedrun.admin.cooldown").contains("antispeedrun.bypass"));
    }

    @Test
    @DisplayName("completion offers the actions, then online names after a valid one")
    void completes() {
        Predicate<Subcommand> all = s -> true;
        List<String> online = List.of("Steve", "Alex");
        assertEquals(List.of("show", "clear"), CommandCompletion.complete(new String[] {"cooldown", ""}, all, online));
        assertEquals(online, CommandCompletion.complete(new String[] {"cooldown", "clear", ""}, all, online));
        assertEquals(List.of(), CommandCompletion.complete(new String[] {"cooldown", "reset", ""}, all, online));
        assertEquals(List.of(), CommandCompletion.complete(new String[] {"cooldown", ""},
                s -> s != Subcommand.COOLDOWN, online));
    }
}
