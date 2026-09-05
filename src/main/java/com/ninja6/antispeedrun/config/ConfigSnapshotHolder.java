package com.ninja6.antispeedrun.config;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Holds the live {@link PluginConfig} behind a single {@code volatile} reference and performs the
 * reload swap.
 *
 * <p>This is the whole of the concurrency contract, in one small class so it can be reviewed and
 * tested as one thing:
 *
 * <ul>
 *   <li>{@link #get()} is a single volatile read. Callers read it <em>once</em> per event and use
 *       the returned local for the rest of the handler, so a decision cannot straddle a swap.</li>
 *   <li>{@link #reload(ConfigSource)} parses and validates an entire new snapshot before touching
 *       the reference, then publishes it with one volatile write. Its
 *       {@link #reload(ConfigSource, SnapshotBinding)} overload extends that to state compiled
 *       <em>from</em> a snapshot: the derived value is built from the candidate before the
 *       candidate is published, so a failure to build it leaves both untouched rather than
 *       publishing a configuration whose derived state was rejected. Because {@link PluginConfig} is
 *       deeply immutable, that write is a safe publication of the whole graph: a reader sees the
 *       complete old configuration or the complete new one, never a mixture.</li>
 *   <li>A reload that fails changes nothing. The previous snapshot stays live, a named
 *       {@link ConfigLoadException} is logged at {@code SEVERE}, and the caller gets {@code false}
 *       so it can tell the operator. The plugin is not disabled.</li>
 * </ul>
 *
 * <h2>Where the work happens</h2>
 *
 * <p>Reload is split deliberately, and callers must respect the split:
 *
 * <ul>
 *   <li><strong>Parsing is off-thread and outside the lock.</strong> Reading {@code config.yml} is
 *       file I/O and must not run on a Folia region thread or the main thread; the caller is
 *       expected to invoke {@link #reload(ConfigSource, SnapshotBinding)} from the
 *       {@code AsyncScheduler}. {@link ConfigSource#load()} and {@link PluginConfig#from} are both
 *       called with no lock held, so a slow disk never blocks a second reload or any reader.</li>
 *   <li><strong>The swap is synchronous and serialised.</strong> Publishing the candidate and
 *       logging its warnings happen together under a private lock, held across nothing else. Two
 *       concurrent reloads therefore commit one after the other rather than interleaving: the
 *       later commit wins, and the warning lines of one reload never interleave with the other's.
 *       The lock is uncontended in practice — reload is single-writer by convention, one
 *       {@code /asr reload} at a time — and readers never take it at all.</li>
 * </ul>
 *
 * <p>A consequence worth stating: because the parse is outside the lock, two reloads that overlap
 * commit in the order they <em>finish parsing</em>, not the order they were requested. Every
 * published snapshot is still whole and self-consistent, which is the guarantee this class exists
 * to provide; ordering between simultaneous operator-triggered reloads is not one.
 *
 * <p>Deliberately free of any Bukkit type — it takes a {@code java.util.logging.Logger}, which is
 * exactly what {@code JavaPlugin#getLogger()} returns — so the reload and swap behaviour is unit
 * tested directly rather than inferred.
 */
public final class ConfigSnapshotHolder {

    private final Logger logger;

    /**
     * Serialises the publish half of a reload, and nothing else. Held only across the snapshot
     * write and the logging of that snapshot's warnings — never across parsing, file I/O or a
     * caller's binding — so a reload can never block a reader and a slow parse can never hold off
     * another reload's commit.
     */
    private final Object swapLock = new Object();

    /**
     * The live snapshot. Volatile is the entire synchronisation mechanism here: reads are lock-free
     * on every region thread and the swap is one write.
     */
    private volatile PluginConfig snapshot;

    public ConfigSnapshotHolder(Logger logger, PluginConfig initial) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.snapshot = Objects.requireNonNull(initial, "initial");
    }

    /**
     * The live snapshot. Read this once per event and reuse the local; never call it twice inside
     * one decision.
     */
    public PluginConfig get() {
        return snapshot;
    }

    /**
     * Parses a new snapshot from {@code source} and, only if that succeeds completely, swaps it in.
     *
     * @return {@code true} if the new snapshot is now live; {@code false} if it was rejected and
     *         the previous snapshot remains live
     */
    public boolean reload(ConfigSource source) {
        return reload(source, candidate -> Boolean.TRUE).isPresent();
    }

    /**
     * Derives whatever must be swapped alongside a snapshot, from the <em>candidate</em>, before the
     * candidate is published.
     *
     * <p>Anything compiled out of the configuration — the item gate table is the first such thing —
     * has to be built from a snapshot that is not live yet, or a failure to build it leaves the new
     * configuration published beside derived state from the old one. Throwing from
     * {@link #bind(PluginConfig)} rejects the whole reload, so a caller's derived state and the
     * snapshot it came from are never out of step.
     *
     * @param <T> the derived value handed back to the caller on success
     */
    @FunctionalInterface
    public interface SnapshotBinding<T> {

        /**
         * @param candidate the parsed but not yet published snapshot
         * @return the derived value to hand back; must not be {@code null}
         * @throws Exception to reject the candidate. Nothing is published and the previous snapshot
         *                   stays live
         */
        T bind(PluginConfig candidate) throws Exception;
    }

    /**
     * Parses a candidate snapshot, lets {@code binding} derive from it, and publishes the candidate
     * only once both have succeeded.
     *
     * <p>This is the all-or-nothing form of {@link #reload(ConfigSource)}: parse, derive, then one
     * volatile write. A failure in either half changes nothing at all.
     *
     * <p>Call this off the region and main threads — from the {@code AsyncScheduler} — because
     * {@code source.load()} reads a file. The parse and the binding run with no lock held; only the
     * publish is serialised, against any other thread calling either {@code reload} overload. It is
     * safe to call concurrently even though it is single-writer by convention.
     *
     * @return the derived value if the new snapshot is now live; empty if the reload was rejected,
     *         in which case the previous snapshot — and whatever the caller derived from it —
     *         remains live and the plugin is not disabled
     */
    public <T> Optional<T> reload(ConfigSource source, SnapshotBinding<T> binding) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(binding, "binding");

        PluginConfig candidate;
        try {
            candidate = PluginConfig.from(source.load());
        } catch (ConfigLoadException failure) {
            reject(failure);
            return Optional.empty();
        } catch (RuntimeException failure) {
            reject(new ConfigLoadException("config.yml could not be read: " + failure, failure));
            return Optional.empty();
        }

        T derived;
        try {
            derived = Objects.requireNonNull(binding.bind(candidate), "binding returned null");
        } catch (Exception rejection) {
            logger.log(Level.SEVERE,
                    rejection.getClass().getSimpleName() + ": config.yml parsed, but state derived "
                            + "from it could not be built, so the new configuration was NOT applied. "
                            + "The previously loaded configuration and everything derived from it "
                            + "remain live and the plugin stays enabled. Cause: "
                            + rejection.getMessage(),
                    rejection);
            return Optional.empty();
        }

        // The commit. Everything above ran unlocked; this is the only serialised part, and it does
        // no I/O and no parsing -- one reference write plus the warning lines that belong to it.
        synchronized (swapLock) {
            this.snapshot = candidate;
            logWarnings(candidate.warnings());
        }
        return Optional.of(derived);
    }

    /** Logs the recoverable problems recorded on a snapshot, one line each. */
    public void logWarnings(List<String> warnings) {
        for (String warning : warnings) {
            logger.warning("config.yml: " + warning);
        }
    }

    private void reject(ConfigLoadException failure) {
        logger.log(Level.SEVERE,
                "ConfigLoadException: config.yml was rejected and has NOT been applied. "
                        + "The previously loaded configuration remains live and the plugin stays "
                        + "enabled. Fix the file and run /asr reload again. Cause: "
                        + failure.getMessage(),
                failure);
    }
}
