/**
 * Typed, immutable configuration model for AntiSpeedrun.
 *
 * <h2>Threading contract</h2>
 *
 * This plugin targets Folia, where event handlers run on many region threads at once and
 * {@code /asr reload} runs on the command thread. The rules below are binding on every type in
 * this plugin, not only on this package.
 *
 * <ol>
 *   <li>{@link com.ninja6.antispeedrun.config.PluginConfig} is an <strong>immutable
 *       snapshot</strong>. It is built once, never mutated, and is therefore safe to publish to
 *       any number of threads.</li>
 *   <li>The live snapshot is held behind a single {@code volatile} reference in
 *       {@link com.ninja6.antispeedrun.config.ConfigSnapshotHolder}. A reload parses and validates
 *       a complete new snapshot first and then swaps the reference in one assignment, so a reader
 *       observes either the whole old configuration or the whole new one and never a half-applied
 *       mixture.</li>
 *   <li>A handler reads the reference <strong>once</strong>, at the top of the handler body, and
 *       uses that local for the rest of the decision. Re-reading mid-handler can straddle a swap
 *       and mix two configurations inside one decision.</li>
 *   <li>A failed reload does not disable the plugin and does not clear anything: the previous
 *       snapshot stays live and a named
 *       {@link com.ninja6.antispeedrun.config.ConfigLoadException} is logged.</li>
 *   <li>No handler mutates state belonging to an entity outside its own region. Per-player shared
 *       state uses concurrent collections; plain {@code java.util.HashMap} is a corruption path
 *       here, not merely a slow one.</li>
 * </ol>
 *
 * <h2>Parsing</h2>
 *
 * Parsing is expressed against {@link com.ninja6.antispeedrun.config.ConfigSection} rather than
 * against Bukkit's {@code FileConfiguration}, so the whole model is exercisable off-server.
 * {@link com.ninja6.antispeedrun.config.BukkitConfigSection} is the thin server adapter;
 * {@link com.ninja6.antispeedrun.config.MapConfigSection} adapts any plain nested
 * {@code Map}, which is what a YAML parser produces.
 */
package com.ninja6.antispeedrun.config;
