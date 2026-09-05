/**
 * Durable state: the things the plugin must still know after a restart.
 *
 * <p>Finding C-08 is that three admin features imply durable state and nothing stores it — a
 * community-event dimension unlock evaporates on restart, a granted bypass silently expires at the
 * next reboot, and the journey book's {@code !player.hasPlayedBefore()} check is false for every
 * player who joined before the plugin was installed, so an established server rolls the book out
 * and nobody ever receives it.
 *
 * <h2>Two mechanisms, chosen per shape of the data</h2>
 *
 * <ul>
 *   <li><strong>Per-player booleans go in the player's PDC</strong> —
 *       {@link com.ninja6.antispeedrun.storage.BypassStore} and
 *       {@link com.ninja6.antispeedrun.storage.JourneyBookStore}. The server already persists a
 *       {@code PersistentDataContainer} with the playerdata it writes anyway, so this needs no I/O
 *       layer of its own, no flush, no eviction and no per-player map — which also means there is
 *       nothing here to register with {@code PlayerStateRegistry} and nothing to leak on quit
 *       (finding R-08). On Folia a player's PDC is owned by their region, so every method taking a
 *       {@code Player} states that it must run on that player's thread.</li>
 *   <li><strong>Server-wide state goes in a flat YAML file</strong> —
 *       {@link com.ninja6.antispeedrun.storage.DimensionUnlockStore} over
 *       {@link com.ninja6.antispeedrun.storage.StateFile}. There is no player to hang a dimension
 *       unlock on.</li>
 * </ul>
 *
 * <h2>Testability, and why {@code StateFile} exists</h2>
 *
 * {@code paper-api} is {@code compileOnly}, so Bukkit's {@code YamlConfiguration} is not on the
 * test classpath — the same constraint that made {@code PluginConfig} parse from
 * {@code ConfigSection} rather than {@code FileConfiguration}. The storage layer follows that
 * pattern: {@link com.ninja6.antispeedrun.storage.StateFile} is a flat
 * {@code Map<String, Object>} seam, {@link com.ninja6.antispeedrun.storage.UnlockState} and
 * {@link com.ninja6.antispeedrun.storage.ProfileApplier} are plain JDK code, and
 * {@link com.ninja6.antispeedrun.storage.YamlStateFile} is the one thin Bukkit-bound adapter.
 *
 * <h2>Threading</h2>
 *
 * File writes must never run on a region thread. {@link
 * com.ninja6.antispeedrun.storage.DimensionUnlockStore} therefore mutates its in-memory snapshot
 * synchronously behind one {@code volatile} — the same publication contract the configuration
 * snapshot uses — and hands the write to an executor the caller supplies, which in production is
 * Folia's {@code AsyncScheduler}.
 */
package com.ninja6.antispeedrun.storage;
