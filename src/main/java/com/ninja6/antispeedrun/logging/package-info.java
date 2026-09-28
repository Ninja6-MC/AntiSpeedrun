/**
 * What the plugin does to text before it logs it.
 *
 * <h2>What lives here</h2>
 *
 * <ul>
 *   <li>{@link com.ninja6.antispeedrun.logging.LogLine} — an operator's own value, or the message of
 *       whatever rejected it, reduced to something a single log record can carry: whitespace
 *       collapsed, control characters neutralised, length bounded on a code-point boundary.</li>
 * </ul>
 *
 * <h2>Why it is a package of its own</h2>
 *
 * The callers are in {@code com.ninja6.antispeedrun.config} and
 * {@code com.ninja6.antispeedrun.progression}, so a package-private helper in either would have to
 * be duplicated in the other, which is exactly the drift this package exists to end. Nothing here
 * touches Bukkit, holds state, or logs anything itself — it only renders — so it is exercisable
 * off-server and safe to call from any thread.
 */
package com.ninja6.antispeedrun.logging;
