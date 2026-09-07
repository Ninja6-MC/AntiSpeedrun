/**
 * Enforcement. Everything in this package answers the question "is this player allowed to do that?"
 * and then stops them, or does not.
 *
 * <h2>The shape every listener here follows</h2>
 *
 * The Bukkit class is wiring and nothing else: it reads the event, translates its types into
 * plain ones, calls a pure decision type, and applies the answer. The decision itself lives beside
 * it in a class with no Bukkit import — {@link com.ninja6.antispeedrun.listeners.DimensionGateRules},
 * {@link com.ninja6.antispeedrun.listeners.VehicleTransit},
 * {@link com.ninja6.antispeedrun.listeners.SafeRetreat} — which is what makes it testable, since
 * {@code paper-api} is {@code compileOnly} and Bukkit's event classes cannot be instantiated
 * without a server. {@link com.ninja6.antispeedrun.progression.MilestoneEvaluator} set the
 * precedent; a gate that can only be exercised by booting Paper is a gate that gets tested once.
 *
 * <h2>Reading the world</h2>
 *
 * <ul>
 *   <li>Configuration is read <strong>once</strong> per event, into a local, and that local is used
 *       for the whole handler. {@link com.ninja6.antispeedrun.config} carries the contract.</li>
 *   <li>Progression goes through {@link com.ninja6.antispeedrun.progression.ProgressionManager} and
 *       its cache, never through {@code Player#getAdvancementProgress} directly. A gate is a hot
 *       path; the cache is why it is cheap.</li>
 *   <li>A gate is waived by the {@code antispeedrun.bypass.*} permission, by an unexpired
 *       {@link com.ninja6.antispeedrun.storage.BypassStore} grant, or by an operator override —
 *       three separate things, all checked.</li>
 * </ul>
 *
 * <h2>Folia</h2>
 *
 * There is no global tick thread. Single-entity events arrive on the region owning that entity, so
 * reading and nudging that entity is legal inline; anything touching a <em>different</em> entity's
 * region, or a position that might be in one, goes through that entity's {@code EntityScheduler} or
 * through {@code teleportAsync}. No listener here performs file I/O or blocks.
 */
package com.ninja6.antispeedrun.listeners;
