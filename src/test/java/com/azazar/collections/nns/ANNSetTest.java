package com.azazar.collections.nns;


import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ANNSetTest {

    private static final int BIT_LENGTH = 256;
    private static final Random RANDOM = new Random(0);
    
    private static final DistanceCalculator<BitSet> BITSET_DISTANCE_CALC = (BitSet o1, BitSet o2) -> {
        long[] a = o1.toLongArray();
        long[] b = o2.toLongArray();
        int len = Math.max(a.length, b.length);
        int dist = 0;
        for (int i = 0; i < len; i++) {
            long ai = i < a.length ? a[i] : 0;
            long bi = i < b.length ? b[i] : 0;
            dist += Long.bitCount(ai ^ bi);
        }
        return dist;
    };

    @Test
    void findNeighborsReturnsExistingNeighbors() {
        DistanceBasedSet<BitSet> set = createConfiguredSet();
        BitSet value = newItem();
        BitSet closeNeighbor = mutatedCopy(value);
        BitSet query = mutatedCopy(closeNeighbor);

        Assertions.assertTrue(set.add(value), "Expected primary value to be inserted");
        Assertions.assertTrue(set.add(closeNeighbor), "Expected close neighbor value to be inserted");

        ProximityResult<BitSet> neighbors = set.findNeighbors(query);
        Assertions.assertNotNull(neighbors, "Expected neighbors result for existing value");
        Assertions.assertTrue(set.contains(neighbors.closest()), "Nearest neighbor should come from the set");
        Assertions.assertFalse(neighbors.nearest().isEmpty(), "Expected at least one similar neighbor to be returned");
    }

    @Test
    void nearestNeighbourSearchReturnsExactMatches() {
        DistanceBasedSet<BitSet> set = createConfiguredSet();
        BitSet[] dataset = createDataset(10, 1000);

        for (BitSet entry : dataset) {
            set.add(entry);
        }
        
        int exactMatches = 0;

        for (BitSet entry : dataset) {
            ProximityResult<BitSet> result = set.findNeighbors(entry);
            Assertions.assertNotNull(result, "Expected a nearest result for every inserted value");
            if (result.closest().equals(entry)) {
                exactMatches++;
            }
        }

        Assertions.assertTrue((float)exactMatches / (float)dataset.length > 0.99f, "Nearest neighbour search should return the inserted element most of the time");
    }

    @Test
    void insertionCostPersistenceTest() {
        int[] sizes = {1_000, 10_000, 50_000};
        for (int size : sizes) {
            BitSet[] dataset = createDataset(10, size);
            CountingDistanceCalculator<BitSet> calculator = new CountingDistanceCalculator<>(BITSET_DISTANCE_CALC);
            ANNSet<BitSet> set = createConfiguredSet(calculator);
            System.out.println("Seeding set with " + size + " elements");
            int inserted = 0;
            for (BitSet value : dataset) {
                set.add(value);
                //Assertions.assertTrue(set.put(value), "Failed to insert seed value #" + inserted); // TODO : Investigate issue
                inserted++;
                if (inserted % 10_000 == 0 || inserted == dataset.length) {
                    System.out.println("Inserted " + inserted + " / " + dataset.length);
                }
            }
            calculator.reset();
            System.out.println("Performing probe insertion for set size " + size);
            BitSet probe = mutatedCopy(dataset[size / 2]);
            Assertions.assertTrue(set.add(probe), "Expected probe insertion for set size " + size);
            Assertions.assertTrue(calculator.getCallCount() <= 5_000,
                    () -> "Insertion exceeded distance budget for size " + size + ": " + calculator.getCallCount());
            System.out.println("Distance calculations for set size " + size + ": " + calculator.getCallCount());
        }
    }

    private static ANNSet<BitSet> createConfiguredSet() {
        return createConfiguredSet(BITSET_DISTANCE_CALC);
    }

    private static <T> ANNSet<T> createConfiguredSet(DistanceCalculator<T> calculator) {
        ANNSet<T> set = new ANNSet<>(calculator);
        set.setNeighbourhoodSize(30);
        set.setSearchSetSize(50);
        set.setSearchMaxSteps(-1);
        set.setAdaptiveStepFactor(3f);
        return set;
    }

    private static BitSet[] createDataset(int clustering, int setSize) {
        BitSet[] dataset = new BitSet[setSize];

        for (int i = 0; i < setSize; i++) {
            dataset[i] = newItem();
        }

        if (clustering > 0) {
            for (int i = clustering; i < setSize; i++) {
                BitSet cluster = dataset[RANDOM.nextInt(clustering)];
                for (int bit = 0; bit < BIT_LENGTH; bit++) {
                    if (RANDOM.nextBoolean()) {
                        dataset[i].set(bit, cluster.get(bit));
                    }
                }
            }
        }
        return dataset;
    }

    private static BitSet newItem() {
        BitSet bits = new BitSet(BIT_LENGTH);
        for (int i = 0; i < BIT_LENGTH; i++) {
            bits.set(i, RANDOM.nextBoolean());
        }
        return bits;
    }

    private static BitSet mutatedCopy(BitSet source) {
        BitSet copy = (BitSet) source.clone();
        copy.flip(0);
        return copy;
    }

    private static final class CountingDistanceCalculator<T> implements DistanceCalculator<T> {

        private final DistanceCalculator<T> delegate;

        private int callCount = 0;

        private CountingDistanceCalculator(DistanceCalculator<T> delegate) {
            this.delegate = delegate;
        }

        @Override
        public double calcDistance(T o1, T o2) {
            callCount++;
            return delegate.calcDistance(o1, o2);
        }

        void reset() {
            callCount = 0;
        }

        int getCallCount() {
            return callCount;
        }
    }

    @Test
    void qualityRegressionTest() {
        final int SET_SIZE = 5_000;
        final int QUERY_COUNT = 200;
        final int K = 10;

        Random rng = new Random(99);

        // Build dataset with fixed seed
        BitSet[] dataset = new BitSet[SET_SIZE];
        int clustering = 10;
        for (int i = 0; i < SET_SIZE; i++) {
            dataset[i] = new BitSet(BIT_LENGTH);
            for (int bit = 0; bit < BIT_LENGTH; bit++) {
                dataset[i].set(bit, rng.nextBoolean());
            }
        }
        for (int i = clustering; i < SET_SIZE; i++) {
            BitSet cluster = dataset[rng.nextInt(clustering)];
            for (int bit = 0; bit < BIT_LENGTH; bit++) {
                if (rng.nextBoolean()) {
                    dataset[i].set(bit, cluster.get(bit));
                }
            }
        }

        ANNSet<BitSet> set = createConfiguredSet();
        for (BitSet value : dataset) {
            set.add(value);
        }

        // Generate queries (slight mutations of dataset elements)
        BitSet[] queries = new BitSet[QUERY_COUNT];
        for (int i = 0; i < QUERY_COUNT; i++) {
            queries[i] = (BitSet) dataset[rng.nextInt(SET_SIZE)].clone();
            queries[i].flip(rng.nextInt(BIT_LENGTH));
            queries[i].flip(rng.nextInt(BIT_LENGTH));
        }

        // Measure recall@1: how often ANN returns the true nearest neighbor
        int recall1Hits = 0;
        // Measure recall@K: what fraction of true top-K are in ANN top-K
        double recallKSum = 0;
        // Measure distance ratio: ANN distance / true distance (1.0 = perfect)
        double distRatioSum = 0;

        for (BitSet query : queries) {
            // ANN result
            ProximityResult<BitSet> annResult = set.findNeighbors(query, K);
            BitSet annClosest = annResult.closest();
            Set<BitSet> annTopK = new HashSet<>();
            for (DistancedValue<BitSet> dv : annResult.nearest()) {
                annTopK.add(dv.value());
            }

            // Brute-force true nearest
            double bestDist = Double.MAX_VALUE;
            BitSet trueClosest = null;
            List<double[]> allDists = new ArrayList<>();
            for (int j = 0; j < SET_SIZE; j++) {
                double d = BITSET_DISTANCE_CALC.calcDistance(query, dataset[j]);
                if (d < bestDist) {
                    bestDist = d;
                    trueClosest = dataset[j];
                }
                allDists.add(new double[]{j, d});
            }
            allDists.sort((a, b) -> Double.compare(a[1], b[1]));

            // True top-K
            Set<BitSet> trueTopK = new HashSet<>();
            for (int j = 0; j < K && j < allDists.size(); j++) {
                trueTopK.add(dataset[(int) allDists.get(j)[0]]);
            }

            // Recall@1
            if (annClosest.equals(trueClosest)) {
                recall1Hits++;
            }

            // Recall@K
            int overlap = 0;
            for (BitSet v : annTopK) {
                if (trueTopK.contains(v)) overlap++;
            }
            recallKSum += (double) overlap / K;

            // Distance ratio
            if (bestDist > 0) {
                distRatioSum += annResult.distance() / bestDist;
            } else {
                distRatioSum += (annResult.distance() == 0) ? 1.0 : 2.0;
            }
        }

        double recall1 = (double) recall1Hits / QUERY_COUNT;
        double recallK = recallKSum / QUERY_COUNT;
        double avgDistRatio = distRatioSum / QUERY_COUNT;

        System.out.println("qualityRegressionTest: recall@1 = " + recall1 + " (" + recall1Hits + "/" + QUERY_COUNT + ")");
        System.out.println("qualityRegressionTest: recall@" + K + " = " + recallK);
        System.out.println("qualityRegressionTest: avg distance ratio = " + avgDistRatio);

        // Quality baselines — recall must not drop, distance ratio must not increase
        double recall1Baseline = 0.985;
        double recallKBaseline = 0.83;
        double distRatioBaseline = 1.55;

        // Regression guards (fail if quality drops)
        Assertions.assertTrue(recall1 >= recall1Baseline * 0.95,
                "recall@1 regression: " + recall1 + " < " + (recall1Baseline * 0.95) + " — update baseline if algorithm changed");
        Assertions.assertTrue(recallK >= recallKBaseline * 0.95,
                "recall@" + K + " regression: " + recallK + " < " + (recallKBaseline * 0.95) + " — update baseline if algorithm changed");
        Assertions.assertTrue(avgDistRatio <= distRatioBaseline * 1.05,
                "distance ratio regression: " + avgDistRatio + " > " + (distRatioBaseline * 1.05) + " — update baseline if algorithm changed");

        // Improvement detectors (fail if quality improved significantly — update baselines!)
        if (recall1 > recall1Baseline * 1.02) {
            Assertions.fail("recall@1 IMPROVED: " + recall1 + " > " + recall1Baseline + " — UPDATE recall1Baseline to " + recall1);
        }
        if (recallK > recallKBaseline * 1.05) {
            Assertions.fail("recall@" + K + " IMPROVED: " + recallK + " > " + recallKBaseline + " — UPDATE recallKBaseline to " + String.format("%.4f", recallK));
        }
        if (avgDistRatio < distRatioBaseline * 0.90) {
            Assertions.fail("distance ratio IMPROVED: " + avgDistRatio + " < " + distRatioBaseline + " — UPDATE distRatioBaseline to " + String.format("%.4f", avgDistRatio));
        }
    }

    @Test
    void performanceRegressionTest() {
        final int SET_SIZE = 5_000;
        final int QUERY_COUNT = 100;

        // Use a separate Random with fixed seed for reproducibility
        Random rng = new Random(42);

        // Build dataset
        BitSet[] dataset = new BitSet[SET_SIZE];
        int clustering = 10;
        for (int i = 0; i < SET_SIZE; i++) {
            dataset[i] = new BitSet(BIT_LENGTH);
            for (int bit = 0; bit < BIT_LENGTH; bit++) {
                dataset[i].set(bit, rng.nextBoolean());
            }
        }
        for (int i = clustering; i < SET_SIZE; i++) {
            BitSet cluster = dataset[rng.nextInt(clustering)];
            for (int bit = 0; bit < BIT_LENGTH; bit++) {
                if (rng.nextBoolean()) {
                    dataset[i].set(bit, cluster.get(bit));
                }
            }
        }

        CountingDistanceCalculator<BitSet> calculator = new CountingDistanceCalculator<>(BITSET_DISTANCE_CALC);
        ANNSet<BitSet> set = createConfiguredSet(calculator);

        // Measure build cost
        for (BitSet value : dataset) {
            set.add(value);
        }
        long buildCost = calculator.getCallCount();
        System.out.println("performanceRegressionTest: build cost (distance calcs) = " + buildCost);

        // Measure search cost: findNeighbors with count=1
        calculator.reset();
        for (int i = 0; i < QUERY_COUNT; i++) {
            BitSet query = (BitSet) dataset[rng.nextInt(SET_SIZE)].clone();
            query.flip(rng.nextInt(BIT_LENGTH)); // slight mutation
            set.findNeighbors(query, 1);
        }
        long searchCost1 = calculator.getCallCount();
        System.out.println("performanceRegressionTest: search cost (k=1, " + QUERY_COUNT + " queries) = " + searchCost1);

        // Measure search cost: findNeighbors with count=10
        calculator.reset();
        for (int i = 0; i < QUERY_COUNT; i++) {
            BitSet query = (BitSet) dataset[rng.nextInt(SET_SIZE)].clone();
            query.flip(rng.nextInt(BIT_LENGTH));
            set.findNeighbors(query, 10);
        }
        long searchCost10 = calculator.getCallCount();
        System.out.println("performanceRegressionTest: search cost (k=10, " + QUERY_COUNT + " queries) = " + searchCost10);

        // Search must be sublinear: per-query cost must be well below SET_SIZE
        long perQueryK1 = searchCost1 / QUERY_COUNT;
        long perQueryK10 = searchCost10 / QUERY_COUNT;
        long maxPerQuery = SET_SIZE / 5; // at most 20% of linear scan
        Assertions.assertTrue(perQueryK1 < maxPerQuery,
                "Search k=1 is not sublinear: " + perQueryK1 + " calcs/query >= " + maxPerQuery + " (20% of " + SET_SIZE + ")");
        Assertions.assertTrue(perQueryK10 < maxPerQuery,
                "Search k=10 is not sublinear: " + perQueryK10 + " calcs/query >= " + maxPerQuery + " (20% of " + SET_SIZE + ")");

        // Baselines
        long buildBaseline = 3_685_000;
        long searchK1Baseline = 16_000;
        long searchK10Baseline = 15_900;

        // Regression guards (fail if cost increases >5%)
        Assertions.assertTrue(buildCost <= buildBaseline * 105 / 100,
                "Build cost regression: " + buildCost + " exceeds baseline " + buildBaseline + " by more than 5%");
        Assertions.assertTrue(searchCost1 <= searchK1Baseline * 105 / 100,
                "Search k=1 cost regression: " + searchCost1 + " exceeds baseline " + searchK1Baseline + " by more than 5%");
        Assertions.assertTrue(searchCost10 <= searchK10Baseline * 105 / 100,
                "Search k=10 cost regression: " + searchCost10 + " exceeds baseline " + searchK10Baseline + " by more than 5%");

        // Improvement detectors (fail if cost drops >15% — update baselines!)
        if (buildCost < buildBaseline * 85 / 100) {
            Assertions.fail("Build cost IMPROVED: " + buildCost + " < 85% of baseline " + buildBaseline + " — UPDATE buildBaseline to " + buildCost);
        }
        if (searchCost1 < searchK1Baseline * 85 / 100) {
            Assertions.fail("Search k=1 IMPROVED: " + searchCost1 + " < 85% of baseline " + searchK1Baseline + " — UPDATE searchK1Baseline to " + searchCost1);
        }
        if (searchCost10 < searchK10Baseline * 85 / 100) {
            Assertions.fail("Search k=10 IMPROVED: " + searchCost10 + " < 85% of baseline " + searchK10Baseline + " — UPDATE searchK10Baseline to " + searchCost10);
        }
    }

    @Test
    void findNeighborsWithCountReturnsRequestedNumberOfNeighbors() {
        DistanceBasedSet<BitSet> set = createConfiguredSet();
        BitSet[] dataset = createDataset(10, 100);

        for (BitSet entry : dataset) {
            set.add(entry);
        }

        BitSet query = mutatedCopy(dataset[0]);
        
        // Test with count = 5
        ProximityResult<BitSet> neighbors5 = set.findNeighbors(query, 5);
        Assertions.assertNotNull(neighbors5, "Expected neighbors result for count=5");
        Assertions.assertNotNull(neighbors5.closest(), "Expected a nearest value");
        // nearest() now contains all results including the closest
        Assertions.assertEquals(5, neighbors5.nearest().size(), "Expected 5 neighbors for count=5");

        // Test with count = 10
        ProximityResult<BitSet> neighbors10 = set.findNeighbors(query, 10);
        Assertions.assertNotNull(neighbors10, "Expected neighbors result for count=10");
        Assertions.assertEquals(10, neighbors10.nearest().size(), "Expected 10 neighbors for count=10");

        // Test with count = 1 (should return only the closest)
        ProximityResult<BitSet> neighbors1 = set.findNeighbors(query, 1);
        Assertions.assertNotNull(neighbors1, "Expected neighbors result for count=1");
        Assertions.assertEquals(1, neighbors1.nearest().size(), "Expected 1 neighbor for count=1");
    }

    @Test
    void findNeighborsWithCountReturnsNeighborsInDistanceOrder() {
        DistanceBasedSet<BitSet> set = createConfiguredSet();
        BitSet[] dataset = createDataset(10, 100);

        for (BitSet entry : dataset) {
            set.add(entry);
        }

        BitSet query = mutatedCopy(dataset[0]);
        ProximityResult<BitSet> neighbors = set.findNeighbors(query, 5);
        
        Assertions.assertNotNull(neighbors, "Expected neighbors result");
        
        // The nearest neighbor should be closer or equal to all similar neighbors
        double nearestDistance = neighbors.distance();
        for (DistancedValue<BitSet> similar : neighbors.nearest()) {
            Assertions.assertTrue(nearestDistance <= similar.distance(),
                    "Nearest neighbor should be closer than or equal to similar neighbors");
        }
    }

    @Test
    void findNeighborsWithCountOnEmptySetReturnsNull() {
        DistanceBasedSet<BitSet> set = createConfiguredSet();
        
        BitSet query = newItem();
        ProximityResult<BitSet> neighbors = set.findNeighbors(query, 5);
        
        Assertions.assertNull(neighbors, "Expected null for empty set");
    }

    @Test
    void findNeighborsWithCountForExactMatchReturnsZeroDistance() {
        DistanceBasedSet<BitSet> set = createConfiguredSet();
        BitSet[] dataset = createDataset(10, 50);

        for (BitSet entry : dataset) {
            set.add(entry);
        }

        // Query with an exact element from the set
        BitSet exactMatch = dataset[25];
        ProximityResult<BitSet> neighbors = set.findNeighbors(exactMatch, 5);
        
        Assertions.assertNotNull(neighbors, "Expected neighbors result for exact match");
        Assertions.assertEquals(0.0, neighbors.distance(), "Expected zero distance for exact match");
        Assertions.assertEquals(exactMatch, neighbors.closest(), "Expected the exact element to be returned");
    }

    @Test
    void wallClockPerformanceTest() {
        final int SET_SIZE = 10_000;
        final int QUERY_COUNT = 1_000;

        Random rng = new Random(77);
        BitSet[] dataset = new BitSet[SET_SIZE];
        for (int i = 0; i < SET_SIZE; i++) {
            dataset[i] = new BitSet(BIT_LENGTH);
            for (int bit = 0; bit < BIT_LENGTH; bit++) {
                dataset[i].set(bit, rng.nextBoolean());
            }
        }
        int clustering = 10;
        for (int i = clustering; i < SET_SIZE; i++) {
            BitSet cluster = dataset[rng.nextInt(clustering)];
            for (int bit = 0; bit < BIT_LENGTH; bit++) {
                if (rng.nextBoolean()) {
                    dataset[i].set(bit, cluster.get(bit));
                }
            }
        }

        // Measure build time
        ANNSet<BitSet> set = createConfiguredSet();
        long buildStart = System.nanoTime();
        for (BitSet value : dataset) {
            set.add(value);
        }
        long buildMs = (System.nanoTime() - buildStart) / 1_000_000;
        System.out.println("wallClockPerformanceTest: build " + SET_SIZE + " elements = " + buildMs + " ms");

        // Measure query time
        long queryStart = System.nanoTime();
        for (int i = 0; i < QUERY_COUNT; i++) {
            BitSet query = (BitSet) dataset[rng.nextInt(SET_SIZE)].clone();
            query.flip(rng.nextInt(BIT_LENGTH));
            set.findNeighbors(query, 10);
        }
        long queryMs = (System.nanoTime() - queryStart) / 1_000_000;
        double queryUsEach = (double)(System.nanoTime() - queryStart) / 1000.0 / QUERY_COUNT;
        System.out.println("wallClockPerformanceTest: " + QUERY_COUNT + " queries = " + queryMs + " ms (" + String.format("%.0f", queryUsEach) + " us/query)");

        // Generous limits to avoid flaky failures on slow CI, but catch gross regressions
        long maxBuildMs = 30_000; // 30 seconds for 10K build
        long maxQueryMs = 5_000;  // 5 seconds for 1K queries
        Assertions.assertTrue(buildMs < maxBuildMs,
                "Build took " + buildMs + " ms, exceeds " + maxBuildMs + " ms limit");
        Assertions.assertTrue(queryMs < maxQueryMs,
                "Queries took " + queryMs + " ms, exceeds " + maxQueryMs + " ms limit");
    }

}
