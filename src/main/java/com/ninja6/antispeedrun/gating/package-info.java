/**
 * Compiles the configured item tiers into a material lookup that an event handler can query
 * without allocating.
 *
 * <h2>Why this is not in {@code config}</h2>
 *
 * {@link com.ninja6.antispeedrun.config.PluginConfig} is deliberately free of every Bukkit type so
 * the whole configuration model parses and is tested without a server. A compiled lookup cannot be:
 * it exists to name {@code org.bukkit.Material}. Keeping the two apart preserves that property
 * rather than dragging the server API into the parser.
 *
 * <p>{@link com.ninja6.antispeedrun.gating.ItemGateTable} is therefore generic in the material
 * enum. Production code binds it to {@code Material} through
 * {@link com.ninja6.antispeedrun.gating.MaterialGates}; tests bind it to a fixture enum whose
 * constants carry the real 1.21 material names, so the compiler is exercised end to end off-server.
 *
 * <h2>Threading contract</h2>
 *
 * The same contract as {@code com.ninja6.antispeedrun.config}, and for the same reason. A compiled
 * {@code ItemGateTable} is immutable and is built once per configuration snapshot. A reload
 * compiles a complete new table and publishes it with one {@code volatile} write; nothing is ever
 * mutated in place, so a handler that reads the reference once per event sees one whole table and
 * never a half-rebuilt one. A compilation that fails changes nothing: the previous table stays
 * live.
 *
 * <h2>Cost model</h2>
 *
 * Every string operation happens once, at compile time, over a cached copy of the material
 * universe. {@link com.ninja6.antispeedrun.gating.ItemGateTable#isGated} and
 * {@link com.ninja6.antispeedrun.gating.ItemGateTable#tierFor} are array-indexed
 * {@code EnumMap}/{@code EnumSet} reads: no allocation, no string comparison, no regular
 * expression. {@code ItemGateTableStructureTest} asserts that structurally, against the compiled
 * class file, rather than by timing a loop.
 */
package com.ninja6.antispeedrun.gating;
