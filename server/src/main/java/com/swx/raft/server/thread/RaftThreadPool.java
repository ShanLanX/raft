package com.swx.raft.server.thread;

import java.util.concurrent.*;

public class RaftThreadPool {
    private static final int CPU = Runtime.getRuntime().availableProcessors();
    private static final int MAX_POOL_SIZE = CPU * 2;
    private static final int QUEUE_SIZE = 1024;
    private static final long KEEP_TIME = 1000 * 60;
    private static final TimeUnit KEEP_TIME_UNIT = TimeUnit.MILLISECONDS;

    private static ScheduledExecutorService ss = getScheduled();
    private static ThreadPoolExecutor te = getThreadPool();


    private static ThreadPoolExecutor getThreadPool() {
        return new RaftThreadPoolExecutor(
                CPU,
                MAX_POOL_SIZE,
                KEEP_TIME,
                KEEP_TIME_UNIT,
                new LinkedBlockingQueue<>(QUEUE_SIZE),
                new NameThreadFactory());
    }

    private static ScheduledExecutorService getScheduled() {
        return new ScheduledThreadPoolExecutor(CPU, new NameThreadFactory());
    }

    public static void scheduleAtFixedRate(Runnable r, long initDelay, long delay) {
        ss.scheduleAtFixedRate(r, initDelay, delay, TimeUnit.MILLISECONDS);
    }

    public static void scheduleWithFixedDelay(Runnable r, long delay) {
        ss.scheduleWithFixedDelay(r, 0, delay, TimeUnit.MILLISECONDS);

    }

    public static <T> Future<T> submit(Callable r) {
        return te.submit(r);
    }

    public static void execute(Runnable r) {
        te.execute(r);
    }
    public static void execute(Runnable r, boolean sync) {
        if (sync) {
            r.run();
        } else {
            te.execute(r);
        }
    }
// 利用线程工厂产生线程
    static class NameThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new RaftThread("Raft thread", r);
            t.setDaemon(true);
            t.setPriority(5);
            return t;
        }
    }






}
