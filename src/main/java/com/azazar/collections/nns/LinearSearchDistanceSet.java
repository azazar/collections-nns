package com.azazar.collections.nns;

import com.azazar.collections.nns.DistanceBasedSet.SearchResult;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;

/**
 *
 * @author Mikhail Yevchenko <spam@azazar.com>
 */
public class LinearSearchDistanceSet<X> extends AbstractDistanceBasedSet<X> {

    private static final boolean multithreaded;
    private static final Executor executor;
    static {
        if (Runtime.getRuntime().availableProcessors() >= 2) {
            multithreaded = true;
            executor = new ScheduledThreadPoolExecutor(Runtime.getRuntime().availableProcessors(), new ThreadFactory() {

                public Thread newThread(Runnable r) {
                    Thread rv = new Thread(r);
                    rv.setDaemon(true);
                    rv.setName("LinearSearchDistanceSet comparator thread");
                    return rv;
                }
            });
        } else {
            multithreaded = false;
            executor = null;
        }
    }

    final Object tasksMon = new Object();
    int searchTasksPending = 0;

    private class SearchTask implements Runnable {

        X query;
        int start, end;
        CountDownLatch countDownLatch;

        public SearchTask(X query, int start, int end, CountDownLatch countDownLatch) {
            this.query = query;
            this.start = start;
            this.end = end;
            this.countDownLatch = countDownLatch;
        }

        public volatile LinearSearchResult<X> searchResult = null;

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

    List<X> values = null;

    public int size() {
        return values == null ? 0 : values.size();
    }

    public boolean put(X value) {
        if (values == null) {
            values = new ArrayList<X>();
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
        int index;

        public LinearSearchResult(X value, double distance, int index) {
            this.value = value;
            this.distance = distance;
            this.index = index;
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

    public SearchResult<X> findNearest(X value) {
        if (values == null) return null;
        int sz = values.size();

        if (sz == 0) return null;

        stopSearch = false;

        if (multithreaded && (sz > 512)) {
            int nTasks = Runtime.getRuntime().availableProcessors();
            SearchTask[] tasks = (SearchTask[]) Array.newInstance(SearchTask.class, nTasks);
            int start,end=-1;
            CountDownLatch countDownLatch = new CountDownLatch(tasks.length);
            for (int i = 0; i < tasks.length; i++) {
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
                tasks[i] = new SearchTask(value, start, end, countDownLatch);
                executor.execute(tasks[i]);
            }
            LinearSearchResult bestResult = null;
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
        int bestIdx = -1;
        for (int i = start; i < end; i++) {
            X e = values.get(i);
            dist = distanceCalculator.calcDistance(e, value);
            if (dist == 0) {
                stopSearch = true;
                return new LinearSearchResult<X>(e, dist, i);
            } else if (dist < bestDist) {
                best = e;
                bestDist = dist;
                bestIdx = i;
            }
            if (stopSearch)
                break;
        }
        if (best != null)
            return new LinearSearchResult<X>(best, bestDist, bestIdx);
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

    public X[] toArray() {
        return (X[]) values.toArray();
    }
}
