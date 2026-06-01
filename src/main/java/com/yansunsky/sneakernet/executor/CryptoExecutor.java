package com.yansunsky.sneakernet.executor;

import com.yansunsky.sneakernet.SneakerNet;

import java.util.concurrent.*;

/**
 * 密码学操作多线程执行器
 * <p>
 * 提供 daemon 线程池，用于在非主线程执行耗时的密码学操作
 * （如 ECC 密钥生成、ECDSA 签名/验签、AES-GCM 加密/解密），
 * 避免阻塞 Minecraft 服务器主线程。
 * </p>
 *
 * <pre>
 * 线程池配置:
 *   - 核心线程数: 2
 *   - 线程类型: daemon（JVM 退出时自动终止）
 *   - 线程优先级: NORM_PRIORITY - 1 (4)
 *   - 队列: LinkedBlockingQueue（无界）
 *   - 关闭超时: 10 秒
 * </pre>
 */
public class CryptoExecutor {

    /** 关闭超时时间（秒） */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

    /** 内部线程池 */
    private final ExecutorService executor;

    public CryptoExecutor() {
        this.executor = Executors.newFixedThreadPool(2, new CryptoThreadFactory());
    }

    /**
     * 异步执行任务，返回 CompletableFuture
     *
     * @param task 待执行的任务
     * @param <T>  返回值类型
     * @return CompletableFuture，可链式处理结果或异常
     */
    public <T> CompletableFuture<T> supplyAsync(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new CompletionException(e);
            }
        }, executor);
    }

    /**
     * 异步执行无返回值任务
     *
     * @param task 待执行的任务
     * @return CompletableFuture<Void>
     */
    public CompletableFuture<Void> runAsync(Runnable task) {
        return CompletableFuture.runAsync(task, executor);
    }

    /**
     * 优雅关闭线程池
     * <p>
     * 先停止接受新任务，等待已提交任务完成（最多 10 秒），
     * 超时后强制中断。
     * </p>
     */
    public void shutdown() {
        SneakerNet.LOGGER.info("[SneakerNet] 正在关闭 CryptoExecutor...");

        executor.shutdown(); // 停止接受新任务

        try {
            // 等待已提交任务完成
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                SneakerNet.LOGGER.warn("[SneakerNet] CryptoExecutor 未在 {} 秒内完成，执行强制关闭", SHUTDOWN_TIMEOUT_SECONDS);
                executor.shutdownNow(); // 强制中断

                // 再次等待被中断的任务终止
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    SneakerNet.LOGGER.error("[SneakerNet] CryptoExecutor 强制关闭后仍有任务未终止");
                }
            } else {
                SneakerNet.LOGGER.info("[SneakerNet] CryptoExecutor 已优雅关闭");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            SneakerNet.LOGGER.warn("[SneakerNet] CryptoExecutor 关闭被中断");
        }
    }

    /**
     * 检查执行器是否已关闭
     */
    public boolean isShutdown() {
        return executor.isShutdown();
    }

    /**
     * 检查执行器是否已终止（所有任务完成）
     */
    public boolean isTerminated() {
        return executor.isTerminated();
    }

    // ======================== 线程工厂 ========================

    /**
     * 自定义线程工厂：创建 daemon 线程，优先级为 NORM - 1
     */
    private static class CryptoThreadFactory implements ThreadFactory {
        private static final java.util.concurrent.atomic.AtomicInteger COUNTER =
                new java.util.concurrent.atomic.AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "SneakerNet-Crypto-" + COUNTER.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            t.setUncaughtExceptionHandler((thread, throwable) ->
                    SneakerNet.LOGGER.error("[SneakerNet] 线程 {} 未捕获异常", thread.getName(), throwable)
            );
            return t;
        }
    }
}
