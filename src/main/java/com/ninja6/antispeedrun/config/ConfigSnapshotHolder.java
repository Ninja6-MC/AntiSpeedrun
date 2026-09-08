package com.ninja6.antispeedrun.config;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
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
        return reload(source, binding, failure -> { });
    }

    /**
     * As {@link #reload(ConfigSource, SnapshotBinding)}, additionally handing the rejected-document
     * failure to {@code onRejected} before returning empty.
     *
     * <p>This exists because the caller cannot tell the two failures apart from an empty
     * {@link Optional} alone, and at startup it has to: an
     * {@link UnenforceableGateException} means the file described gating this server would not
     * enforce, which {@code AntiSpeedrunPlugin} refuses to start on, while any other
     * {@link ConfigLoadException} means the file described nothing at all and the shipped defaults
     * are a legible place to land (#91). The listener is <em>not</em> called when the binding
     * rejects the candidate — that failure is the caller's own and it already knows about it — so a
     * caller that distinguishes three outcomes tracks the binding's rejection separately, as it
     * always did.
     *
     * <p>The listener runs on the calling thread with no lock held, before this returns. It must not
     * block; log or record, nothing more.
     */
    public <T> Optional<T> reload(ConfigSource source, SnapshotBinding<T> binding,
            Consumer<ConfigLoadException> onRejected) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(onRejected, "onRejected");

        PluginConfig candidate;
        try {
            candidate = PluginConfig.from(source.load());
        } catch (ConfigLoadException failure) {
            reject(failure);
            onRejected.accept(failure);
            return Optional.empty();
        } catch (RuntimeException failure) {
            ConfigLoadException wrapped =
                    new ConfigLoadException("config.yml could not be read: " + failure, failure);
            reject(wrapped);
            onRejected.accept(wrapped);
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

    /**
     * Reports a rejected document without asserting what surviving on the previous snapshot means,
     * because that differs between a reload and a startup and this class cannot tell them apart.
     *
     * <p>The message used to say "the previously loaded configuration remains live… run /asr reload
     * again" on both paths. On a reload that is exactly right. At startup there is no previously
     * loaded configuration — the holder was constructed on {@code PluginConfig.defaults()}, which
     * gates no items — and no reload to run, so the line reassured an operator about the one case
     * where the news is worst. {@code AntiSpeedrunPlugin} follows this with the honest startup line,
     * but a {@code SEVERE} is read on its own often enough that it has to be true on its own.
     */
    private void reject(ConfigLoadException failure) {
        // The two sentences differ because the outcomes do (#91). This class still cannot tell a
        // reload from a startup, but it can tell which failure it is holding, and that is what
        // decides whether the startup half of the sentence is "runs on defaults" or "does not
        // start" -- so neither reader is told something false about their own case.
        String consequence = failure instanceof UnenforceableGateException
                ? "After a reload the previous configuration stays live and the plugin stays "
                        + "enabled. At startup the plugin does NOT enable: the shipped defaults "
                        + "gate no items, so running on them would disarm item gating server-wide "
                        + "over this one key."
                : "The plugin stays enabled and keeps running on the configuration it already had: "
                        + "after a reload that is the previous file, at startup it is the shipped "
                        + "defaults, which gate NO items. Check the next log line for which.";
        logger.log(Level.SEVERE,
                "ConfigLoadException: config.yml was rejected and has NOT been applied. "
                        + consequence + " Fix the file, then run /asr reload or restart. Cause: "
                        + failure.getMessage(),
                failure);
    }
}
