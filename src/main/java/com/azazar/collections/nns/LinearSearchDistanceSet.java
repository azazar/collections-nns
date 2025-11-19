package com.azazar.collections.nns;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 *
 * @author Mikhail Yevchenko <spam@azazar.com>
 */
public class LinearSearchDistanceSet<X> extends AbstractDistanceBasedSet<X> {

    private static final boolean MULTITHREADED;
    private static final Executor EXECUTOR;
    static {
        if (Runtime.getRuntime().availableProcessors() >= 2) {
            MULTITHREADED = true;
            EXECUTOR = new ScheduledThreadPoolExecutor(Runtime.getRuntime().availableProcessors(), r -> {
                Thread rv = new Thread(r);
                rv.setDaemon(true);
                rv.setName("LinearSearchDistanceSet comparator thread");
                return rv;
            });
        } else {
            MULTITHREADED = false;
            EXECUTOR = null;
        }
    }

    private class SearchTask implements Runnable {

        final X query;
        final int start;
        final int end;
        final CountDownLatch countDownLatch;

        SearchTask(X query, int start, int end, CountDownLatch countDownLatch) {
            this.query = query;
            this.start = start;
            this.end = end;
            this.countDownLatch = countDownLatch;
        }

        public volatile LinearSearchResult<X> searchResult = null;

        @Override
        public void run() {
            try {
                synchronized (this) {
                    searchResult = findNearest(query, start, end, false);
                }
            } finally {
                countDownLatch.countDown();
            }
        }

    }

    public LinearSearchDistanceSet(DistanceCalculator<X> distanceCalculator) {
        super(distanceCalculator);
    }

    private List<X> values = null;

    @Override
    public int size() {
        return values == null ? 0 : values.size();
    }

    @Override
    public boolean put(X value) {
        if (values == null) {
            values = new ArrayList<>();
            return values.add(value);
        } else {
            if (!values.contains(value))
                return values.add(value);
            else
                return false;
        }
    }

    private static class LinearSearchResult<X> implements SearchResultWithDistance<X> {
        X value;
        double distance;

        public LinearSearchResult(X value, double distance) {
            this.value = value;
            this.distance = distance;
        }

        @Override
        public X value() {
            return value;
        }

        @Override
        public double distance() {
            return distance;
        }

    }

    @Override
    public SearchResult<X> findNearest(X value) {
        if (values == null) return null;
        int sz = values.size();

        if (sz == 0) return null;

        stopSearch = false;

        if (MULTITHREADED && (sz > 512)) {
            int nTasks = Runtime.getRuntime().availableProcessors();
            List<SearchTask> tasks = new ArrayList<>(nTasks);
            int start,end=-1;
            CountDownLatch countDownLatch = new CountDownLatch(nTasks);
            for (int i = 0; i < nTasks; i++) {
                if (i == 0) {
                    start = 0;
                    end = sz / nTasks;
                } else if (i == (nTasks - 1)) {
                    start = end;
                    end = sz;
                } else {
                    start = end;
                    end = i * ((sz / nTasks) + 1);
                }
                SearchTask task = new SearchTask(value, start, end, countDownLatch);
                tasks.add(task);
                EXECUTOR.execute(task);
            }
            LinearSearchResult<X> bestResult = null;
            try {
                countDownLatch.await();
            } catch (InterruptedException ex) {
                stopSearch = true;
                Thread.currentThread().interrupt();
            }
            for (SearchTask task : tasks) {
                if (bestResult == null)
                    bestResult = task.searchResult;
                else if (task.searchResult != null && task.searchResult.distance < bestResult.distance)
                    bestResult = task.searchResult;
            }
            return bestResult;
        } else {
            return findNearest(value, 0, values.size(), true);
        }
    }

    private volatile boolean stopSearch;

    private LinearSearchResult<X> findNearest(X value, int start, int end, boolean clearStopper) {
        if (values == null || values.isEmpty()) return null;
        if (clearStopper) stopSearch = false;
        X best = null;
        double bestDist = Integer.MAX_VALUE, dist;
        for (int i = start; i < end; i++) {
            X e = values.get(i);
            dist = distanceCalculator.calcDistance(e, value);
            if (dist == 0) {
                stopSearch = true;
                return new LinearSearchResult<>(e, dist);
            } else if (dist < bestDist) {
                best = e;
                bestDist = dist;
            }
            if (stopSearch)
                break;
        }
        if (best != null)
            return new LinearSearchResult<>(best, bestDist);
        else
            return null;
    }

    @Override
    public boolean remove(X value) {
        if (values == null || values.isEmpty()) return false;
        double dist;
        for (int i = 0; i < values.size(); i++) {
            X e = values.get(i);
            dist = distanceCalculator.calcDistance(e, value);
            if (dist == 0) {
                values.remove(i);
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public X[] toArray() {
        if (values == null || values.isEmpty())
            throw new IllegalStateException("Cannot create an array when the set is empty");
        Class<?> componentType = values.get(0).getClass();
        X[] target = (X[]) Array.newInstance(componentType, values.size());
        return values.toArray(target);
    }
}
