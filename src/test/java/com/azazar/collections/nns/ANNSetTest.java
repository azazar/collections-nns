package com.azazar.collections.nns;


import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class ANNSetTest {

    private static final int BIT_LENGTH = 256;
    private static final Random RANDOM = new Random(0);
    
    private static final DistanceCalculator<BitSet> BITSET_DISTANCE_CALC = (BitSet o1, BitSet o2) -> {
        long distance = Math.abs(o1.size() - o2.size());
        
        for(int i = Math.min(o1.size(), o2.size()); i >= 0; i--) {
            if (o1.get(i) != o2.get(i)) {
                distance++;
            }
        }

        return distance;
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
                Assertions.assertTrue(set.add(value), "Failed to insert seed value #" + inserted);
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

    /**
     * Combined regression test for quality, performance (distance calc count),
     * CPU time, and memory allocation.
     * CPU time and allocation baselines are calibrated for: OpenJDK 21, AMD Ryzen 5 9600X.
     * Those checks are skipped automatically if the JVM or CPU differs.
     */
    @Test
    void regressionTest() {
        final int SET_SIZE = 5_000;
        final int QUALITY_QUERY_COUNT = 200;
        final int PERF_QUERY_COUNT = 100;
        final int K = 10;

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

        // Environment detection for CPU time / allocation gating
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        boolean measureCpuTime = tmx.isCurrentThreadCpuTimeSupported()
                && Runtime.version().feature() == 21
                && "AMD Ryzen 5 9600X 6-Core Processor".equals(getCpuModelOrNull());

        com.sun.management.ThreadMXBean allocTmx = null;
        boolean measureAlloc = false;
        try {
            allocTmx = (com.sun.management.ThreadMXBean) tmx;
            measureAlloc = allocTmx.isThreadAllocatedMemorySupported();
        } catch (ClassCastException ignored) {
        }
        long tid = Thread.currentThread().getId();

        // === BUILD PHASE ===
        CountingDistanceCalculator<BitSet> calculator = new CountingDistanceCalculator<>(BITSET_DISTANCE_CALC);
        ANNSet<BitSet> set = createConfiguredSet(calculator);

        long buildCpuStart = measureCpuTime ? tmx.getCurrentThreadCpuTime() : 0;
        long buildAllocStart = measureAlloc ? allocTmx.getThreadAllocatedBytes(tid) : 0;

        for (BitSet value : dataset) {
            set.add(value);
        }

        long buildCost = calculator.getCallCount();
        long buildCpuNs = measureCpuTime ? tmx.getCurrentThreadCpuTime() - buildCpuStart : 0;
        long buildAlloc = measureAlloc ? allocTmx.getThreadAllocatedBytes(tid) - buildAllocStart : 0;

        System.out.println("regressionTest: build cost (distance calcs) = " + buildCost);
        if (measureCpuTime) {
            System.out.println("regressionTest: build CPU time = " + String.format("%.3f", buildCpuNs / 1e9) + "s");
        }
        if (measureAlloc) {
            System.out.println("regressionTest: build alloc = " + buildAlloc + " bytes ("
                    + (buildAlloc / (1024 * 1024)) + " MB)");
        }

        // === QUALITY PHASE ===
        BitSet[] queries = new BitSet[QUALITY_QUERY_COUNT];
        for (int i = 0; i < QUALITY_QUERY_COUNT; i++) {
            queries[i] = (BitSet) dataset[rng.nextInt(SET_SIZE)].clone();
            queries[i].flip(rng.nextInt(BIT_LENGTH));
            queries[i].flip(rng.nextInt(BIT_LENGTH));
        }

        int recall1Hits = 0;
        double recallKSum = 0;
        double distRatioSum = 0;

        for (BitSet query : queries) {
            ProximityResult<BitSet> annResult = set.findNeighbors(query, K);
            BitSet annClosest = annResult.closest();
            Set<BitSet> annTopK = new HashSet<>();
            for (DistancedValue<BitSet> dv : annResult.nearest()) {
                annTopK.add(dv.value());
            }

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

            Set<BitSet> trueTopK = new HashSet<>();
            for (int j = 0; j < K && j < allDists.size(); j++) {
                trueTopK.add(dataset[(int) allDists.get(j)[0]]);
            }

            if (annClosest.equals(trueClosest)) {
                recall1Hits++;
            }

            int overlap = 0;
            for (BitSet v : annTopK) {
                if (trueTopK.contains(v)) overlap++;
            }
            recallKSum += (double) overlap / K;

            if (bestDist > 0) {
                distRatioSum += annResult.distance() / bestDist;
            } else {
                distRatioSum += (annResult.distance() == 0) ? 1.0 : 2.0;
            }
        }

        double recall1 = (double) recall1Hits / QUALITY_QUERY_COUNT;
        double recallK = recallKSum / QUALITY_QUERY_COUNT;
        double avgDistRatio = distRatioSum / QUALITY_QUERY_COUNT;

        System.out.println("regressionTest: recall@1 = " + recall1 + " (" + recall1Hits + "/" + QUALITY_QUERY_COUNT + ")");
        System.out.println("regressionTest: recall@" + K + " = " + recallK);
        System.out.println("regressionTest: avg distance ratio = " + avgDistRatio);

        // === PERFORMANCE SEARCH PHASE (k=1) ===
        calculator.reset();
        for (int i = 0; i < PERF_QUERY_COUNT; i++) {
            BitSet query = (BitSet) dataset[rng.nextInt(SET_SIZE)].clone();
            query.flip(rng.nextInt(BIT_LENGTH));
            set.findNeighbors(query, 1);
        }
        long searchCost1 = calculator.getCallCount();
        System.out.println("regressionTest: search cost (k=1, " + PERF_QUERY_COUNT + " queries) = " + searchCost1);

        // === PERFORMANCE SEARCH PHASE (k=10) with CPU time + allocation ===
        calculator.reset();
        long searchCpuStart = measureCpuTime ? tmx.getCurrentThreadCpuTime() : 0;
        long searchAllocStart = measureAlloc ? allocTmx.getThreadAllocatedBytes(tid) : 0;

        for (int i = 0; i < PERF_QUERY_COUNT; i++) {
            BitSet query = (BitSet) dataset[rng.nextInt(SET_SIZE)].clone();
            query.flip(rng.nextInt(BIT_LENGTH));
            set.findNeighbors(query, 10);
        }

        long searchCost10 = calculator.getCallCount();
        long searchCpuNs = measureCpuTime ? tmx.getCurrentThreadCpuTime() - searchCpuStart : 0;
        long searchAlloc = measureAlloc ? allocTmx.getThreadAllocatedBytes(tid) - searchAllocStart : 0;

        System.out.println("regressionTest: search cost (k=10, " + PERF_QUERY_COUNT + " queries) = " + searchCost10);
        if (measureCpuTime) {
            double searchCpuSec = searchCpuNs / 1e9;
            System.out.println("regressionTest: search CPU time (k=10) = " + String.format("%.3f", searchCpuSec) + "s"
                    + " (" + String.format("%.0f", (searchCpuNs / 1000.0) / PERF_QUERY_COUNT) + " us/query)");
        }
        if (measureAlloc) {
            System.out.println("regressionTest: search alloc (k=10) = " + searchAlloc + " bytes ("
                    + (searchAlloc / 1024) + " KB)");
        }

        // === ASSERTIONS ===
        // Collect all failures before reporting
        List<String> failures = new ArrayList<>();

        // -- Quality baselines --
        double recall1Baseline = 0.96;
        double recallKBaseline = 0.855;
        double distRatioBaseline = 2.41;

        if (recall1 < recall1Baseline * 0.95)
            failures.add("recall@1 regression: " + recall1 + " < " + (recall1Baseline * 0.95));
        if (recallK < recallKBaseline * 0.95)
            failures.add("recall@" + K + " regression: " + recallK + " < " + (recallKBaseline * 0.95));
        if (avgDistRatio > distRatioBaseline * 1.05)
            failures.add("distance ratio regression: " + avgDistRatio + " > " + (distRatioBaseline * 1.05));
        if (recall1 > recall1Baseline * 1.02)
            failures.add("recall@1 IMPROVED: " + recall1 + " — UPDATE recall1Baseline to " + recall1);
        if (recallK > recallKBaseline * 1.05)
            failures.add("recall@" + K + " IMPROVED: " + recallK + " — UPDATE recallKBaseline to " + String.format("%.4f", recallK));
        if (avgDistRatio < distRatioBaseline * 0.90)
            failures.add("distance ratio IMPROVED: " + avgDistRatio + " — UPDATE distRatioBaseline to " + String.format("%.4f", avgDistRatio));

        // -- Distance calc baselines --
        long buildBaseline = 3_860_000;
        long searchK1Baseline = 16_200;
        long searchK10Baseline = 16_200;

        if (buildCost > buildBaseline * 105 / 100)
            failures.add("Build cost regression: " + buildCost + " exceeds baseline " + buildBaseline + " by >5%");
        if (searchCost1 > searchK1Baseline * 105 / 100)
            failures.add("Search k=1 cost regression: " + searchCost1 + " exceeds baseline " + searchK1Baseline + " by >5%");
        if (searchCost10 > searchK10Baseline * 105 / 100)
            failures.add("Search k=10 cost regression: " + searchCost10 + " exceeds baseline " + searchK10Baseline + " by >5%");
        if (buildCost < buildBaseline * 85 / 100)
            failures.add("Build cost IMPROVED: " + buildCost + " — UPDATE buildBaseline to " + buildCost);
        if (searchCost1 < searchK1Baseline * 85 / 100)
            failures.add("Search k=1 IMPROVED: " + searchCost1 + " — UPDATE searchK1Baseline to " + searchCost1);
        if (searchCost10 < searchK10Baseline * 85 / 100)
            failures.add("Search k=10 IMPROVED: " + searchCost10 + " — UPDATE searchK10Baseline to " + searchCost10);

        // -- CPU time baselines (only when environment matches) --
        if (measureCpuTime) {
            double buildCpuSec = buildCpuNs / 1e9;
            double searchCpuSec = searchCpuNs / 1e9;
            double buildCpuBaseline = 6.5;
            double searchCpuBaseline = 0.03;

            if (buildCpuSec > buildCpuBaseline)
                failures.add("Build CPU time regression: " + String.format("%.3f", buildCpuSec) + "s exceeds " + buildCpuBaseline + "s");
            if (searchCpuSec > searchCpuBaseline)
                failures.add("Search CPU time regression: " + String.format("%.3f", searchCpuSec) + "s exceeds " + searchCpuBaseline + "s");
        }

        // -- Allocation baselines --
        if (measureAlloc) {
            long buildAllocBaseline = 245_000_000L;
            long searchAllocBaseline = 490_000L;

            if (buildAlloc > buildAllocBaseline * 120 / 100)
                failures.add("Build alloc regression: " + buildAlloc + " exceeds baseline " + buildAllocBaseline + " by >20%");
            if (searchAlloc > searchAllocBaseline * 120 / 100)
                failures.add("Search alloc regression: " + searchAlloc + " exceeds baseline " + searchAllocBaseline + " by >20%");
            if (buildAlloc < buildAllocBaseline * 70 / 100)
                failures.add("Build alloc IMPROVED: " + buildAlloc + " — UPDATE buildAllocBaseline to " + buildAlloc);
            if (searchAlloc < searchAllocBaseline * 70 / 100)
                failures.add("Search alloc IMPROVED: " + searchAlloc + " — UPDATE searchAllocBaseline to " + searchAlloc);
        }

        if (!failures.isEmpty()) {
            Assertions.fail("Regression test failures:\n  - " + String.join("\n  - ", failures));
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
    void findNeighborsWithCountOnEmptySetReturnsEmptyResult() {
        DistanceBasedSet<BitSet> set = createConfiguredSet();
        
        BitSet query = newItem();
        ProximityResult<BitSet> neighbors = set.findNeighbors(query, 5);
        
        Assertions.assertNotNull(neighbors, "Expected non-null result even for empty set");
        Assertions.assertTrue(neighbors.nearest().isEmpty(), "Expected empty nearest collection for empty set");
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

    /**
     * Verifies that findNeighbors(indexedNode, k) returns accurate k-nearest results,
     * and measures the concrete improvement over the old graph-neighbor-only code path.
     *
     * <p>Before the fix, querying an indexed node with k>1 returned only its stored graph
     * neighbours (self at distance 0, plus direct graph edges sorted by stored distance).
     * Graph neighbours are selected by RNG pruning for navigability (directional diversity),
     * not proximity. This test replicates the old logic using {@code getStoredNeighbors},
     * measures recall@K for both paths, and prints both numbers.</p>
     */
    @Test
    void indexedNodeKnnAccuracyTest() {
        final int SET_SIZE = 1_000;
        final int K = 10;
        final int QUERY_COUNT = 100;

        Random rng = new Random(7);
        BitSet[] dataset = new BitSet[SET_SIZE];
        for (int i = 0; i < SET_SIZE; i++) {
            dataset[i] = new BitSet(BIT_LENGTH);
            for (int bit = 0; bit < BIT_LENGTH; bit++) dataset[i].set(bit, rng.nextBoolean());
        }
        // Cluster to make the k-NN problem non-trivial
        for (int i = 10; i < SET_SIZE; i++) {
            BitSet cluster = dataset[rng.nextInt(10)];
            for (int bit = 0; bit < BIT_LENGTH; bit++) {
                if (rng.nextBoolean()) dataset[i].set(bit, cluster.get(bit));
            }
        }

        ANNSet<BitSet> set = createConfiguredSet();
        for (BitSet value : dataset) set.add(value);

        double recallNewSum = 0;
        double recallOldSum = 0;

        for (int q = 0; q < QUERY_COUNT; q++) {
            BitSet query = dataset[rng.nextInt(SET_SIZE)]; // query IS in the index

            // Brute-force true top-K
            List<double[]> allDists = new ArrayList<>();
            for (int j = 0; j < SET_SIZE; j++) {
                allDists.add(new double[]{j, BITSET_DISTANCE_CALC.calcDistance(query, dataset[j])});
            }
            allDists.sort((a, b) -> Double.compare(a[1], b[1]));
            Set<BitSet> trueTopK = new HashSet<>();
            for (int j = 0; j < K && j < allDists.size(); j++) {
                trueTopK.add(dataset[(int) allDists.get(j)[0]]);
            }

            // New code: proper ANN search
            ProximityResult<BitSet> result = set.findNeighbors(query, K);
            Set<BitSet> annTopK = new HashSet<>();
            for (DistancedValue<BitSet> dv : result.nearest()) annTopK.add(dv.value());
            int overlapNew = 0;
            for (BitSet v : annTopK) if (trueTopK.contains(v)) overlapNew++;
            recallNewSum += (double) overlapNew / K;

            // Old code (replicated exactly): self at dist 0, then direct graph neighbors
            // sorted by stored distance, truncated to K.
            List<Map.Entry<BitSet, Double>> neighborEntries = new ArrayList<>(set.getStoredNeighbors(query).entrySet());
            neighborEntries.sort((a, b) -> Double.compare(a.getValue(), b.getValue()));
            Set<BitSet> oldTopK = new HashSet<>();
            oldTopK.add(query); // self is always first at distance 0
            for (int i = 0; i < neighborEntries.size() && oldTopK.size() < K; i++) {
                oldTopK.add(neighborEntries.get(i).getKey());
            }
            int overlapOld = 0;
            for (BitSet v : oldTopK) if (trueTopK.contains(v)) overlapOld++;
            recallOldSum += (double) overlapOld / K;
        }

        double recallNew = recallNewSum / QUERY_COUNT;
        double recallOld = recallOldSum / QUERY_COUNT;
        System.out.printf("indexedNodeKnnAccuracyTest: recall@%d (old graph-neighbor code) = %.4f%n", K, recallOld);
        System.out.printf("indexedNodeKnnAccuracyTest: recall@%d (new ANN search code)      = %.4f%n", K, recallNew);

        Assertions.assertTrue(recallNew > recallOld,
                "New ANN search should outperform old graph-neighbor approach: new=" + recallNew + " old=" + recallOld);
        Assertions.assertTrue(recallNew >= 0.70,
                "recall@" + K + " for indexed-node queries should be >= 0.70, got " + recallNew);
    }

    /**
     * Verifies that findNeighbors remains accurate after bulk removals.
     *
     * <p>Before the fix, the healing loop in remove() was gated on
     * {@code neighborNode.neighbors.size() < neighbourhoodSize}, so nodes already at
     * capacity never received better reconnection edges after their shared neighbour was
     * removed. This left holes in the graph and degraded search recall. This test confirms
     * that the new code heals properly and recall stays high post-removal.</p>
     */
    @Test
    void removeHealingAccuracyTest() {
        final int SET_SIZE = 1_000;
        final int REMOVE_COUNT = 300; // remove 30%
        final int QUERY_COUNT = 100;

        Random rng = new Random(13);
        BitSet[] dataset = new BitSet[SET_SIZE];
        for (int i = 0; i < SET_SIZE; i++) {
            dataset[i] = new BitSet(BIT_LENGTH);
            for (int bit = 0; bit < BIT_LENGTH; bit++) dataset[i].set(bit, rng.nextBoolean());
        }
        for (int i = 10; i < SET_SIZE; i++) {
            BitSet cluster = dataset[rng.nextInt(10)];
            for (int bit = 0; bit < BIT_LENGTH; bit++) {
                if (rng.nextBoolean()) dataset[i].set(bit, cluster.get(bit));
            }
        }

        ANNSet<BitSet> set = createConfiguredSet();
        for (BitSet value : dataset) set.add(value);

        // Remove items from the middle of the dataset
        boolean[] removed = new boolean[SET_SIZE];
        for (int i = 0; i < REMOVE_COUNT; i++) {
            removed[i + 100] = true;
            set.remove(dataset[i + 100]);
        }

        // Build the remaining dataset list for brute-force comparison
        List<BitSet> remaining = new ArrayList<>();
        for (int i = 0; i < SET_SIZE; i++) {
            if (!removed[i]) remaining.add(dataset[i]);
        }

        int recall1Hits = 0;
        for (int q = 0; q < QUERY_COUNT; q++) {
            BitSet query = (BitSet) remaining.get(rng.nextInt(remaining.size())).clone();
            query.flip(rng.nextInt(BIT_LENGTH));

            // Brute-force true nearest among remaining elements
            double bestDist = Double.MAX_VALUE;
            BitSet trueNearest = null;
            for (BitSet item : remaining) {
                double d = BITSET_DISTANCE_CALC.calcDistance(query, item);
                if (d < bestDist) { bestDist = d; trueNearest = item; }
            }

            BitSet annNearest = set.findNeighbors(query, 1).closest();
            if (annNearest.equals(trueNearest)) recall1Hits++;
        }

        double recall1 = (double) recall1Hits / QUERY_COUNT;
        System.out.println("removeHealingAccuracyTest: recall@1 after " + REMOVE_COUNT
                + " removals = " + recall1 + " (" + recall1Hits + "/" + QUERY_COUNT + ")");

        // The old code skipped healing full neighborhoods, leaving graph holes after removal.
        Assertions.assertTrue(recall1 >= 0.85,
                "recall@1 after bulk removes should be >= 0.85, got " + recall1);
    }


    private static String getCpuModelOrNull() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("model name")) {
                    int colon = line.indexOf(':');
                    if (colon >= 0) {
                        return line.substring(colon + 1).trim();
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

}
