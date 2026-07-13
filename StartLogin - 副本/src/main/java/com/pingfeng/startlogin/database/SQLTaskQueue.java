package com.pingfeng.startlogin.database;

import com.pingfeng.startlogin.StartLogin;
import com.pingfeng.startlogin.config.ConfigManager;

import java.io.File;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class SQLTaskQueue {

    private final StartLogin plugin;
    private final DatabaseManager databaseManager;
    private final BlockingQueue<SQLTask<?>> taskQueue;
    private Thread workerThread;
    private final AtomicBoolean running;
    private volatile long lastActivityTime;
    private ScheduledFuture<?> idleCheckFuture;

    public SQLTaskQueue(StartLogin plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.taskQueue = new LinkedBlockingQueue<>();
        this.running = new AtomicBoolean(false);
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            workerThread = new Thread(this::runTaskLoop, "StartLogin-SQL-Worker");
            workerThread.setDaemon(true);
            workerThread.start();
            plugin.getLogger().info("SQL串行任务队列已启动");
        }
    }

    private void runTaskLoop() {
        lastActivityTime = System.currentTimeMillis();
        while (running.get()) {
            try {
                SQLTask<?> task = taskQueue.poll(5, TimeUnit.SECONDS);
                if (task != null) {
                    lastActivityTime = System.currentTimeMillis();
                    executeTask(task);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
            plugin.getLogger().severe("SQL任务执行异常: " + e);
            e.printStackTrace();
        }
        }
    }

    private <T> void executeTask(SQLTask<T> task) {
        Connection connection = null;
        try {
            connection = databaseManager.getConnection();
            T result = task.execute(connection);
            if (task.callback != null) {
                task.callback.onSuccess(result);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("SQL执行错误: " + e.getMessage());
            if (task.callback != null) {
                task.callback.onFailure(e);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("SQL任务未知错误: " + e);
            e.printStackTrace();
            if (task.callback != null) {
                task.callback.onFailure(e);
            }
        } finally {
            if (connection != null) {
                databaseManager.releaseConnection(connection);
            }
            lastActivityTime = System.currentTimeMillis();
        }
    }

    public <T> void submit(SQLTask<T> task) {
        if (!running.get()) {
            plugin.getLogger().warning("SQL队列已停止，任务被丢弃");
            return;
        }
        boolean offered = taskQueue.offer(task);
        if (!offered) {
            plugin.getLogger().warning("SQL任务队列已满，丢弃任务");
        }
    }

    public <T> Future<T> submitWithFuture(SQLTask<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        task.callback = new SQLCallback<T>() {
            @Override
            public void onSuccess(T result) {
                future.complete(result);
            }

            @Override
            public void onFailure(Exception e) {
                future.completeExceptionally(e);
            }
        };
        submit(task);
        return future;
    }

    public void stop() {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join(10000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        taskQueue.clear();
        plugin.getLogger().info("SQL串行任务队列已停止，剩余任务: " + taskQueue.size());
    }

    public long getLastActivityTime() {
        return lastActivityTime;
    }

    public int getQueueSize() {
        return taskQueue.size();
    }

    public static abstract class SQLTask<T> {
        SQLCallback<T> callback;

        public abstract T execute(Connection conn) throws SQLException;

        public SQLTask<T> callback(SQLCallback<T> callback) {
            this.callback = callback;
            return this;
        }
    }

    public interface SQLCallback<T> {
        void onSuccess(T result);

        default void onFailure(Exception e) {
        }
    }
}
