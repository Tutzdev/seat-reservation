package dev.tutz.reservation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class Concurrently {

    private Concurrently() {
    }

    /** Every task waits on the same latch, so they all hit the database at the same moment. */
    public static <T> List<T> run(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();

            List<T> outcomes = new ArrayList<>();
            for (Future<T> future : futures) {
                outcomes.add(future.get());
            }
            return outcomes;
        }
    }

    public static <T> List<T> run(int times, Callable<T> task) throws Exception {
        List<Callable<T>> tasks = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            tasks.add(task);
        }
        return run(tasks);
    }
}
