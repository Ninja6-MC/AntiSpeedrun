/**
 * Command handling.
 *
 * <p>{@link com.ninja6.antispeedrun.commands.AntiSpeedrunCommand} is the {@code /antispeedrun}
 * (alias {@code /asr}) administrative dispatcher from Task 8.1.1. Everything about it that can be
 * decided without a server — which subcommand a token names, which permission node gates it, what
 * to offer on a tab press, and how to read a duration argument — is factored into
 * {@link com.ninja6.antispeedrun.commands.Subcommand},
 * {@link com.ninja6.antispeedrun.commands.CommandCompletion} and
 * {@link com.ninja6.antispeedrun.commands.BypassDuration}, which hold no Bukkit types and are unit
 * tested directly. {@code paper-api} is {@code compileOnly} here, so that split is the difference
 * between command logic that is tested and command logic that is merely read.
 *
 * <p>{@link com.ninja6.antispeedrun.commands.ProgressCommand} is {@code /progress} (#3) and
 * {@link com.ninja6.antispeedrun.commands.JourneyBookCommand} is {@code /journeybook} (#5); each is
 * also reachable as an {@code /asr} subcommand that delegates to it. The journey book's text is
 * {@link com.ninja6.antispeedrun.commands.JourneyBookPages}', Bukkit-free for the same reason.
 */
package com.ninja6.antispeedrun.commands;
