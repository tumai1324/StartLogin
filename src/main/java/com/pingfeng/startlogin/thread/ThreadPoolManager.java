package com.pingfeng.startlogin.thread;

import com.pingfeng.startlogin.StartLogin;
import com.pingfeng.startlogin.config.ConfigManager;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadPoolManager {

    private final StartLogin plugin;
    private final ConfigManager configManager;
    private ThreadPoolExecutor workerPool;
    private ScheduledExecutorService scheduledPool;

    public ThreadPoolManager(StartLogin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    public void init() {
        int coreSize = configManager.getThreadPoolCoreSize();
        int maxSize = configManager.getThreadPoolMaxSize();
        int queueCapacity = configManager.getThreadPoolQueueCapacity();
        long keepAlive = configManager.getThreadPoolKeepAlive();

        workerPool = new ThreadPoolExecutor(
                coreSize,
                maxSize,
                keepAlive,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new NamedThreadFactory("StartLogin-Worker"),
                new DiscardPolicyWithLog()
        );

        scheduledPool = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("StartLogin-Scheduler")
        );

        plugin.getLogger().info("线程池初始化完成: 核心线程=" + coreSize + ", 最大线程=" + maxSize + ", 队列容量=" + queueCapacity);
    }

    public void execute(Runnable task) {
        if (workerPool == null || workerPool.isShutdown()) {
            return;
        }
        workerPool.execute(task);
    }

    public Future<?> submit(Runnable task) {
        if (workerPool == null || workerPool.isShutdown()) {
            return null;
        }
        return workerPool.submit(task);
    }

    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        if (scheduledPool == null || scheduledPool.isShutdown()) {
            return null;
        }
        return scheduledPool.schedule(task, delay, unit);
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
        if (scheduledPool == null || scheduledPool.isShutdown()) {
            return null;
        }
        return scheduledPool.scheduleAtFixedRate(task, initialDelay, period, unit);
    }

    public void shutdown() {
        if (workerPool != null && !workerPool.isShutdown()) {
            workerPool.shutdown();
            try {
                if (!workerPool.awaitTermination(30, TimeUnit.SECONDS)) {
                    workerPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (scheduledPool != null && !scheduledPool.isShutdown()) {
            scheduledPool.shutdown();
            try {
                if (!scheduledPool.awaitTermination(30, TimeUnit.SECONDS)) {
                    scheduledPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduledPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        plugin.getLogger().info("线程池已关闭");
    }

    private static class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(0);
        private final String prefix;

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    private class DiscardPolicyWithLog implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            plugin.getLogger().warning("线程池任务队列已满，丢弃任务。活跃线程: " + executor.getActiveCount() +
                    ", 队列大小: " + executor.getQueue().size());
        }
    }

    public boolean isShutdown() {
        return workerPool == null || workerPool.isShutdown();
    }
}
