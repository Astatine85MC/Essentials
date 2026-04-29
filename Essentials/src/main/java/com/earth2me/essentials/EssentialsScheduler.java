package com.earth2me.essentials;

import io.papermc.lib.PaperLib;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

final class EssentialsScheduler {
    private static final long TICK_MILLIS = 50L;

    private final Essentials plugin;
    private final boolean regionizedScheduler;
    private final AtomicInteger nextTaskId = new AtomicInteger(1);
    private final Map<Integer, PaperBukkitTask> paperTasks = new ConcurrentHashMap<>();

    EssentialsScheduler(final Essentials plugin) {
        this.plugin = plugin;
        this.regionizedScheduler = hasRegionizedSchedulerApi();
    }

    boolean isRegionizedScheduler() {
        return regionizedScheduler;
    }

    CompletableFuture<Location> getBedSpawnLocationAsync(final Player player, final boolean load) {
        if (!regionizedScheduler) {
            return PaperLib.getBedSpawnLocationAsync(player, load);
        }

        final CompletableFuture<Location> future = new CompletableFuture<>();
        if (scheduleEntity(player, () -> {
            final Location potentialBed = player.getPotentialBedLocation();
            if (potentialBed == null || potentialBed.getWorld() == null) {
                future.complete(null);
                return;
            }

            PaperLib.getChunkAtAsync(potentialBed.getWorld(), potentialBed.getBlockX() >> 4, potentialBed.getBlockZ() >> 4, false, load).thenAccept(chunk -> {
                if (scheduleEntity(player, () -> future.complete(player.getBedSpawnLocation()), 0L) == -1) {
                    future.complete(null);
                }
            }).exceptionally(th -> {
                future.completeExceptionally(th);
                return null;
            });
        }, 0L) == -1) {
            future.complete(null);
        }
        return future;
    }

    BukkitTask runTaskAsynchronously(final Runnable run) {
        if (!regionizedScheduler) {
            return plugin.getScheduler().runTaskAsynchronously(plugin, run);
        }

        final PaperBukkitTask task = createPaperTask(false, false);
        try {
            task.attach(Bukkit.getAsyncScheduler().runNow(plugin, ignored -> task.run(run)));
        } catch (final RuntimeException ex) {
            task.cancel();
            throw ex;
        }
        return task;
    }

    BukkitTask runTaskLaterAsynchronously(final Runnable run, final long delay) {
        if (!regionizedScheduler) {
            return plugin.getScheduler().runTaskLaterAsynchronously(plugin, run, delay);
        }

        final PaperBukkitTask task = createPaperTask(false, false);
        try {
            task.attach(Bukkit.getAsyncScheduler().runDelayed(plugin, ignored -> task.run(run), ticksToMillis(delay), TimeUnit.MILLISECONDS));
        } catch (final RuntimeException ex) {
            task.cancel();
            throw ex;
        }
        return task;
    }

    BukkitTask runTaskTimerAsynchronously(final Runnable run, final long delay, final long period) {
        if (!regionizedScheduler) {
            return plugin.getScheduler().runTaskTimerAsynchronously(plugin, run, delay, period);
        }

        final PaperBukkitTask task = createPaperTask(false, true);
        try {
            task.attach(Bukkit.getAsyncScheduler().runAtFixedRate(plugin, ignored -> task.run(run), ticksToMillis(delay), ticksToPeriodMillis(period), TimeUnit.MILLISECONDS));
        } catch (final RuntimeException ex) {
            task.cancel();
            throw ex;
        }
        return task;
    }

    int scheduleGlobal(final Runnable run) {
        return scheduleGlobal(run, 1L);
    }

    int scheduleGlobal(final Runnable run, final long delay) {
        if (!regionizedScheduler) {
            return plugin.getScheduler().scheduleSyncDelayedTask(plugin, run, delay);
        }

        final PaperBukkitTask task = createPaperTask(true, false);
        try {
            if (delay <= 0L) {
                task.attach(Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> task.run(run)));
            } else {
                task.attach(Bukkit.getGlobalRegionScheduler().runDelayed(plugin, ignored -> task.run(run), delay));
            }
        } catch (final RuntimeException ex) {
            return reject(task, "global", ex);
        }
        return task.getTaskId();
    }

    int scheduleGlobalRepeating(final Runnable run, final long delay, final long period) {
        if (!regionizedScheduler) {
            return plugin.getScheduler().scheduleSyncRepeatingTask(plugin, run, delay, period);
        }

        final PaperBukkitTask task = createPaperTask(true, true);
        try {
            task.attach(Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, ignored -> task.run(run), normalizeTickDelay(delay), normalizeTickDelay(period)));
        } catch (final RuntimeException ex) {
            return reject(task, "global repeating", ex);
        }
        return task.getTaskId();
    }

    int scheduleEntity(final Entity entity, final Runnable run) {
        return scheduleEntity(entity, run, 1L);
    }

    int scheduleEntity(final Entity entity, final Runnable run, final long delay) {
        if (!regionizedScheduler) {
            return plugin.getScheduler().scheduleSyncDelayedTask(plugin, run, delay);
        }

        final PaperBukkitTask task = createPaperTask(true, false);
        try {
            final ScheduledTask scheduledTask = delay <= 0L
                    ? entity.getScheduler().run(plugin, ignored -> task.run(run), task::retire)
                    : entity.getScheduler().runDelayed(plugin, ignored -> task.run(run), task::retire, delay);
            if (scheduledTask == null) {
                task.retire();
                return -1;
            }
            task.attach(scheduledTask);
        } catch (final AbstractMethodError | NoSuchMethodError ex) {
            task.retire();
            return -1;
        } catch (final RuntimeException ex) {
            return reject(task, "entity", ex);
        }
        return task.getTaskId();
    }

    int scheduleEntityRepeating(final Entity entity, final Runnable run, final long delay, final long period) {
        if (!regionizedScheduler) {
            return plugin.getScheduler().scheduleSyncRepeatingTask(plugin, run, delay, period);
        }

        final PaperBukkitTask task = createPaperTask(true, true);
        try {
            final ScheduledTask scheduledTask = entity.getScheduler().runAtFixedRate(plugin, ignored -> task.run(run), task::retire, normalizeTickDelay(delay), normalizeTickDelay(period));
            if (scheduledTask == null) {
                task.retire();
                return -1;
            }
            task.attach(scheduledTask);
        } catch (final AbstractMethodError | NoSuchMethodError ex) {
            task.retire();
            return -1;
        } catch (final RuntimeException ex) {
            return reject(task, "entity repeating", ex);
        }
        return task.getTaskId();
    }

    int scheduleLocation(final Location location, final Runnable run) {
        return scheduleLocation(location, run, 1L);
    }

    int scheduleLocation(final Location location, final Runnable run, final long delay) {
        if (!regionizedScheduler) {
            return plugin.getScheduler().scheduleSyncDelayedTask(plugin, run, delay);
        }

        final PaperBukkitTask task = createPaperTask(true, false);
        try {
            task.attach(Bukkit.getRegionScheduler().runDelayed(plugin, location, ignored -> task.run(run), normalizeTickDelay(delay)));
        } catch (final RuntimeException ex) {
            return reject(task, "region", ex);
        }
        return task.getTaskId();
    }

    void cancelTask(final int taskId) {
        if (taskId <= 0) {
            return;
        }
        final PaperBukkitTask task = paperTasks.remove(taskId);
        if (task != null) {
            task.cancel();
            return;
        }
        if (regionizedScheduler) {
            return;
        }
        plugin.getScheduler().cancelTask(taskId);
    }

    private PaperBukkitTask createPaperTask(final boolean sync, final boolean repeating) {
        final int taskId = nextTaskId.getAndIncrement();
        final PaperBukkitTask task = new PaperBukkitTask(plugin, taskId, sync, repeating);
        paperTasks.put(taskId, task);
        return task;
    }

    private int reject(final PaperBukkitTask task, final String schedulerName, final RuntimeException ex) {
        task.cancel();
        if (ex instanceof RejectedExecutionException) {
            plugin.getLogger().log(Level.WARNING, "Astatine rejected an Essentials " + schedulerName + " task because the target scheduler queue is full.", ex);
            return -1;
        }
        throw ex;
    }

    private void cleanup(final PaperBukkitTask task) {
        paperTasks.remove(task.getTaskId(), task);
    }

    private static long ticksToMillis(final long ticks) {
        return Math.max(0L, ticks) * TICK_MILLIS;
    }

    private static long ticksToPeriodMillis(final long ticks) {
        return Math.max(1L, ticks) * TICK_MILLIS;
    }

    private static long normalizeTickDelay(final long delay) {
        return Math.max(1L, delay);
    }

    private static boolean hasRegionizedSchedulerApi() {
        try {
            Bukkit.class.getMethod("getRegionScheduler");
            Bukkit.class.getMethod("getGlobalRegionScheduler");
            Bukkit.class.getMethod("getAsyncScheduler");
            Entity.class.getMethod("getScheduler");
            return true;
        } catch (final NoSuchMethodException ignored) {
            return false;
        }
    }

    private final class PaperBukkitTask implements BukkitTask {
        private final Plugin owner;
        private final int taskId;
        private final boolean sync;
        private final boolean repeating;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile ScheduledTask scheduledTask;

        private PaperBukkitTask(final Plugin owner, final int taskId, final boolean sync, final boolean repeating) {
            this.owner = owner;
            this.taskId = taskId;
            this.sync = sync;
            this.repeating = repeating;
        }

        private void attach(final ScheduledTask scheduledTask) {
            this.scheduledTask = scheduledTask;
            if (cancelled.get()) {
                scheduledTask.cancel();
            }
        }

        private void run(final Runnable runnable) {
            if (cancelled.get()) {
                cleanup(this);
                return;
            }
            try {
                runnable.run();
            } finally {
                if (!repeating) {
                    cleanup(this);
                }
            }
        }

        private void retire() {
            cancelled.set(true);
            cleanup(this);
        }

        @Override
        public int getTaskId() {
            return taskId;
        }

        @Override
        public Plugin getOwner() {
            return owner;
        }

        @Override
        public boolean isSync() {
            return sync;
        }

        @Override
        public boolean isCancelled() {
            final ScheduledTask task = scheduledTask;
            return cancelled.get() || task != null && task.isCancelled();
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            final ScheduledTask task = scheduledTask;
            if (task != null) {
                task.cancel();
            }
            cleanup(this);
        }
    }
}
