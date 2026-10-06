package com.ninja6.antispeedrun.commands;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs tasks one at a time, in the order they were submitted, on a backing executor that promises
 * neither.
 *
 * <p>{@code /asr credit} resolves its target and writes the store on the async scheduler, whose
 * tasks run concurrently on a pool. Two commands issued back to back — a revoke and then a grant of
 * the same credit — could therefore apply in either order and leave the player holding the opposite
 * of what the operator last asked for. Every credit change goes through one of these instead, so at
 * most one is ever in flight and each starts only after the one issued before it has finished.
 *
 * <p>At most one drain task is handed to the backing executor at a time. It runs the queue until
 * the queue is empty; a task submitted while it runs is picked up by the same drain. A task that
 * throws is logged and the drain carries on, so one failure cannot stall every change after it.
 */
final class SerialExecutor implements Executor {

    private final Executor backing;
    private final Logger logger;

    private final Object lock = new Object();
    private final Deque<Runnable> queue = new ArrayDeque<>();

    /** Whether a drain has been handed to {@link #backing} and not yet finished. Guarded by lock. */
    private boolean draining;

    SerialExecutor(Executor backing, Logger logger) {
        this.backing = Objects.requireNonNull(backing, "backing");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        synchronized (lock) {
            queue.add(task);
            if (draining) {
                return;
            }
            draining = true;
        }
        try {
            backing.execute(this::drain);
        } catch (RuntimeException refused) {
            // The plugin is being disabled and the scheduler refuses new work. Nothing queued can run,
            // so drop it rather than leave the next submission waiting on a drain that never comes.
            synchronized (lock) {
                queue.clear();
                draining = false;
            }
            throw refused;
        }
    }

    private void drain() {
        while (true) {
            Runnable next;
            synchronized (lock) {
                next = queue.poll();
                if (next == null) {
                    draining = false;
                    return;
                }
            }
            try {
                next.run();
            } catch (RuntimeException failure) {
                logger.log(Level.SEVERE, "A queued credit change failed: " + failure.getMessage(), failure);
            } catch (Error fatal) {
                // Not swallowed, but not allowed to wedge the queue either: the next submission starts
                // a fresh drain, which runs whatever is still queued.
                synchronized (lock) {
                    draining = false;
                }
                throw fatal;
            }
        }
    }
}
