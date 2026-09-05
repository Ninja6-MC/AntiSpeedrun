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
 * <p>The {@code /progress} and {@code /journeybook} commands declared in {@code plugin.yml} belong
 * to other tasks and are not implemented in this package yet.
 */
package com.ninja6.antispeedrun.commands;
