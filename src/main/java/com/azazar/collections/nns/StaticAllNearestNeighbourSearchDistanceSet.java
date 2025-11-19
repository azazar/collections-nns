package com.azazar.collections.nns;

import com.azazar.collections.nns.DistanceBasedSet.SearchResult;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Mikhail Yevchenko <spam@azazar.com>
 */
public class StaticAllNearestNeighbourSearchDistanceSet<X> extends AbstractDistanceBasedSet<X> {

    private LinearSearchDistanceSet<X> wrapped = new LinearSearchDistanceSet<X>(distanceCalculator);

    public StaticAllNearestNeighbourSearchDistanceSet() {
    }

    public StaticAllNearestNeighbourSearchDistanceSet(DistanceCalculator<X> distanceCalculator) {
        super(distanceCalculator);
    }

    public int size() {
        return wrapped.size();
    }

    private boolean indexBuilt = false;
    private int searchesWithoutIndex = 0;

    public boolean put(X value) {
        if (wrapped.put(value)) {
            indexBuilt = false;
            searchesWithoutIndex = 0;
            return true;
        } else
            return false;
    }

    private static final int MAX_SEARCHES_WITHOUT_INDEX = 10000;
    // 0.5% miss
    private int numNearestToMap = 64;
    private int maxNearestOfToCopy = 64;
    private int maxSearchSteps = 32;
    // smaller miss
//    private int numNearestToMap = 192;
//    private int maxNearestOfToCopy = 192;
//    private int maxSearchSteps = 92;
    private boolean checkReachability = false;

    public int getMaxNearestOfToCopy() {
        return maxNearestOfToCopy;
    }

    public void setMaxNearestOfToCopy(int maxNearestOfToCopy) {
        indexBuilt=this.maxNearestOfToCopy != maxNearestOfToCopy;
        this.maxNearestOfToCopy = maxNearestOfToCopy;
    }

    public int getMaxSearchSteps() {
        return maxSearchSteps;
    }

    public void setMaxSearchSteps(int maxSearchSteps) {
        this.maxSearchSteps = maxSearchSteps;
    }

    public int getNumNearestToMap() {
        return numNearestToMap;
    }

    public void setNumNearestToMap(int numNearestToMap) {
        indexBuilt=this.numNearestToMap != numNearestToMap;
        this.numNearestToMap = numNearestToMap;
    }

    public boolean isReachabilityChecked() {
        return checkReachability;
    }

    public void setCheckReachability(boolean checkReachability) {
        indexBuilt=this.checkReachability != checkReachability;
        this.checkReachability = checkReachability;
    }

    private X[] values;
    private IndexRecord[] index;

    private class IndexRecord {
        public final int rindex;
        public final X value;

        public IndexRecord(int idx) {
            this.rindex = idx;
            value = values[idx];
        }

        private LinkedList<Integer> nearest = new LinkedList<Integer>();
        private LinkedList<Integer> nearestFor = new LinkedList<Integer>();

        public void findNearest() {
            double worstDistance = Double.MAX_VALUE, distance;
            LinkedList<Double> distances = new LinkedList<Double>();
            for(int i = 0; i < values.length; i++) {
                if (i != rindex && (distance = index[i].distanceTo(value)) < worstDistance) {
                    if (nearest.size() > numNearestToMap) {
                        distances.removeLast();
                        nearest.removeLast();
                        worstDistance = distances.getLast();
                    }
                    int j = 0;
                    while(j < distances.size() && distances.get(j).doubleValue() < distance)
                        j++;
                    nearest.add(j, i);
                    distances.add(j, distance);
                }
            }
            for(int i = 0; i < Math.min(maxNearestOfToCopy, nearest.size()); i++) {
                final IndexRecord o = index[nearest.get(i)];
                synchronized (o) {
                    o.nearestFor.addLast(rindex);
                }
            }
        }

        public void initialize() {
            HashSet<Integer> searchIndexSet = new LinkedHashSet<Integer>(nearest.size() + nearestFor.size());
            searchIndexSet.addAll(nearest);
            searchIndexSet.addAll(nearestFor);
            searchIndexes = new int[searchIndexSet.size()];
            int idx = 0;
            for (Integer i : searchIndexSet)
                searchIndexes[idx++] = i.intValue();
            nearest = null;
            nearestFor = null;
            if (searchIndexes.length > (values.length/2))
                new Exception("Too many links " + toString()).printStackTrace();
        }

        @Override
        public String toString() {
            return "#" + rindex + (searchIndexes != null ? "[indexeslength=" + searchIndexes.length + "]" : "[nearest=" + nearest.size() + ";nearestfor=" + nearestFor.size() + "]");
        }

        public int[] searchIndexes;

        public double distanceTo(X value) {
            return distanceCalculator.calcDistance(value, this.value);
        }

        public LinkedSpaceSearchResult walk(X value) {
            LinkedSpaceSearchResult bestResult = null;
            IndexRecord i = this, best;
            HashSet<Integer> walked = new HashSet<Integer>();
            double bestDistance, distance;
            int stepsLeft = maxSearchSteps;
            while (i != null && stepsLeft-- > 0) {
                bestDistance = Double.MAX_VALUE;
                best = null;
                for (int si : i.searchIndexes) {
                    if ((!walked.contains(si)) && ((distance = index[si].distanceTo(value)) < bestDistance)) {
                        if (distance == 0f)
                            return new LinkedSpaceSearchResult(si, distance);
                        bestDistance = distance;
                        best = index[si];
                        if (bestResult == null || distance < bestResult.distance)
                            bestResult = new LinkedSpaceSearchResult(si, distance);
                    }
                    walked.add(si);
                }
                i = best;
            }
            return bestResult;
        }
    }

    private boolean[] reachability;
    private int[] startIndexes;

    private void markReachable(int idx) {
        LinkedList<Integer> toMark = new LinkedList<Integer>();
        toMark.add(idx);
        Integer i;
        while((i = toMark.poll()) != null) {
            reachability[i] = true;
            for (int li : index[i].searchIndexes)
                if (!reachability[li])
                    toMark.add(li);
        }
    }

    public void buildIndex() {
        values = wrapped.toArray();
        index = (IndexRecord[]) Array.newInstance(IndexRecord.class, values.length);
        for (int i = 0; i < values.length; i++)
            index[i] = new IndexRecord(i);
//        for (int i = 0; i < values.length; i++)
//            index[i].findNearest();
//        for (int i = 0; i < values.length; i++)
//            index[i].initialize();
        final CountDownLatch l = new CountDownLatch(values.length);
        ThreadPoolExecutor e = new ScheduledThreadPoolExecutor(Runtime.getRuntime().availableProcessors());
        try{
            for (int i = 0; i < values.length; i++) {
                final int idx = i;
                e.execute(new Runnable() {

                    public void run() {
                        try {
                            index[idx].findNearest();
                        } finally {
                            l.countDown();
                        }
                    }
                });
            }
            try {
                //l.await();
                while (!l.await(1, TimeUnit.SECONDS)) {
                    System.out.println("Building index(phase 1/2): " + l.getCount() + " records left");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            final CountDownLatch li = new CountDownLatch(values.length);
            for (int i = 0; i < values.length; i++)
                index[i].initialize();
            for (int i = 0; i < values.length; i++) {
                final int idx = i;
                e.execute(new Runnable() {

                    public void run() {
                        try {
                            index[idx].initialize();
                        } finally {
                            li.countDown();
                        }
                    }
                });
            }
            try {
                while (!li.await(1, TimeUnit.SECONDS)) {
                    System.out.println("Building index(phase 2/2): " + li.getCount() + " records left");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        } finally {
            e.shutdown();
        }
        if (checkReachability) {
            reachability = new boolean[values.length];
            for (int i = 0; i < reachability.length; i++)
                reachability[i] = false;
            ArrayList<Integer> startIndexesLst = new ArrayList<Integer>(1);
            for (int i = 0; i < reachability.length; i++)
                if (!reachability[i]) {
                    startIndexesLst.add(i);
                    markReachable(i);
                }
            startIndexes = new int[startIndexesLst.size()];
            for (int i = 0; i < startIndexesLst.size(); i++)
                startIndexes[i] = startIndexesLst.get(i);
        } else {
            startIndexes = new int[] {0};
        }
        indexBuilt = true;
        System.out.println("Building index: complete");
    }

    private class LinkedSpaceSearchResult implements SearchResultWithDistance<X> {

        public final int valueIndex;
        public final double distance;

        public LinkedSpaceSearchResult(int valueIndex, double distance) {
            this.valueIndex = valueIndex;
            this.distance = distance;
        }

        @Override
        public double distance() {
            return distance;
        }

        @Override
        public X value() {
            return values[valueIndex];
        }
    }

    public SearchResult<X> findNearestIndexed(X value) {
        if (!indexBuilt)
            buildIndex();

        LinkedSpaceSearchResult bestResult = null, result;

        for (int startIndex : startIndexes) {
            result = index[startIndex].walk(value);
            if (bestResult == null || bestResult.distance > result.distance)
                bestResult = result;
        }

        return bestResult;
    }

    public SearchResult<X> findNearest(X value) {
        if (!indexBuilt) {
            if (searchesWithoutIndex >= MAX_SEARCHES_WITHOUT_INDEX) {
                return findNearestIndexed(value);
            } else {
                searchesWithoutIndex++;
                return wrapped.findNearest(value);
            }
        } else {
            return findNearestIndexed(value);
        }
    }

}
