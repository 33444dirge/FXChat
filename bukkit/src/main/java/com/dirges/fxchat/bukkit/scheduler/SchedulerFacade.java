package com.dirges.fxchat.bukkit.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.atomic.AtomicBoolean;

/** The only scheduling entry point used by FXChat's Bukkit code. */
public final class SchedulerFacade implements AutoCloseable {
    private final Plugin plugin;
    private final boolean folia;
    private final AtomicBoolean closed = new AtomicBoolean();

    public SchedulerFacade(Plugin plugin) {
        this.plugin = plugin;
        this.folia = FoliaSupport.isFolia();
    }

    public void runAtEntity(Entity entity, Runnable task) {
        if (closed.get()) {
            return;
        }
        if (folia) {
            entity.getScheduler().run(plugin, ignored -> runGuarded(task), null);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> runGuarded(task));
        }
    }

    public void runAtEntityDelayed(Entity entity, Runnable task, long ticks) {
        long delay = Math.max(1L, ticks);
        if (closed.get()) {
            return;
        }
        if (folia) {
            entity.getScheduler().runDelayed(plugin, ignored -> runGuarded(task), null, delay);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, () -> runGuarded(task), delay);
        }
    }

    public void runAtRegion(Location location, Runnable task) {
        if (closed.get()) {
            return;
        }
        if (folia) {
            Bukkit.getRegionScheduler().run(plugin, location, ignored -> runGuarded(task));
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> runGuarded(task));
        }
    }

    public void runGlobal(Runnable task) {
        if (closed.get()) {
            return;
        }
        if (folia) {
            Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> runGuarded(task));
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> runGuarded(task));
        }
    }

    public CancellableTask runGlobalAtFixedRate(Runnable task, long initialDelayTicks, long periodTicks) {
        long initialDelay = Math.max(1L, initialDelayTicks);
        long period = Math.max(1L, periodTicks);
        if (closed.get()) {
            return CancellableTask.NOOP;
        }
        if (folia) {
            io.papermc.paper.threadedregions.scheduler.ScheduledTask scheduled = Bukkit.getGlobalRegionScheduler()
                    .runAtFixedRate(plugin, ignored -> runGuarded(task), initialDelay, period);
            return scheduled::cancel;
        }
        BukkitTask scheduled = Bukkit.getScheduler().runTaskTimer(plugin, () -> runGuarded(task), initialDelay, period);
        return scheduled::cancel;
    }

    public void runAsync(Runnable task) {
        if (closed.get()) {
            return;
        }
        if (folia) {
            Bukkit.getAsyncScheduler().runNow(plugin, ignored -> runGuarded(task));
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> runGuarded(task));
        }
    }

    private void runGuarded(Runnable task) {
        if (!closed.get()) {
            task.run();
        }
    }

    @Override
    public void close() {
        closed.set(true);
    }

    @FunctionalInterface
    public interface CancellableTask {
        CancellableTask NOOP = () -> { };

        void cancel();
    }
}
