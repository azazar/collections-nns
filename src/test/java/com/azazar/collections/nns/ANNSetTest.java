package com.azazar.collections.nns;


import java.util.BitSet;
import java.util.Random;

import org.junit.jupiter.api.Assertions;
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

        Neighbors<BitSet> neighbors = set.findNeighbors(query);
        Assertions.assertNotNull(neighbors, "Expected neighbors result for existing value");
        Assertions.assertTrue(set.contains(neighbors.value()), "Nearest neighbor should come from the set");
        Assertions.assertFalse(neighbors.similar().isEmpty(), "Expected at least one similar neighbor to be returned");
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
            Neighbors<BitSet> result = set.findNeighbors(entry);
            Assertions.assertNotNull(result, "Expected a nearest result for every inserted value");
            if (result.value().equals(entry)) {
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
    void findNeighborsWithCountReturnsRequestedNumberOfNeighbors() {
        DistanceBasedSet<BitSet> set = createConfiguredSet();
        BitSet[] dataset = createDataset(10, 100);

        for (BitSet entry : dataset) {
            set.add(entry);
        }

        BitSet query = mutatedCopy(dataset[0]);
        
        // Test with count = 5
        Neighbors<BitSet> neighbors5 = set.findNeighbors(query, 5);
        Assertions.assertNotNull(neighbors5, "Expected neighbors result for count=5");
        Assertions.assertNotNull(neighbors5.value(), "Expected a nearest value");
        // similar() should contain count-1 elements (the rest after the nearest)
        Assertions.assertEquals(4, neighbors5.similar().size(), "Expected 4 similar neighbors for count=5");

        // Test with count = 10
        Neighbors<BitSet> neighbors10 = set.findNeighbors(query, 10);
        Assertions.assertNotNull(neighbors10, "Expected neighbors result for count=10");
        Assertions.assertEquals(9, neighbors10.similar().size(), "Expected 9 similar neighbors for count=10");

        // Test with count = 1 (should behave like findNeighbors without count)
        Neighbors<BitSet> neighbors1 = set.findNeighbors(query, 1);
        Assertions.assertNotNull(neighbors1, "Expected neighbors result for count=1");
        Assertions.assertTrue(neighbors1.similar().isEmpty(), "Expected no similar neighbors for count=1");
    }

    @Test
    void findNeighborsWithCountReturnsNeighborsInDistanceOrder() {
        DistanceBasedSet<BitSet> set = createConfiguredSet();
        BitSet[] dataset = createDataset(10, 100);

        for (BitSet entry : dataset) {
            set.add(entry);
        }

        BitSet query = mutatedCopy(dataset[0]);
        Neighbors<BitSet> neighbors = set.findNeighbors(query, 5);
        
        Assertions.assertNotNull(neighbors, "Expected neighbors result");
        
        // The nearest neighbor should be closer or equal to all similar neighbors
        double nearestDistance = BITSET_DISTANCE_CALC.calcDistance(query, neighbors.value());
        for (BitSet similar : neighbors.similar()) {
            double similarDistance = BITSET_DISTANCE_CALC.calcDistance(query, similar);
            Assertions.assertTrue(nearestDistance <= similarDistance,
                    "Nearest neighbor should be closer than or equal to similar neighbors");
        }
    }

    @Test
    void findNeighborsWithCountOnEmptySetReturnsNull() {
        DistanceBasedSet<BitSet> set = createConfiguredSet();
        
        BitSet query = newItem();
        Neighbors<BitSet> neighbors = set.findNeighbors(query, 5);
        
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
        Neighbors<BitSet> neighbors = set.findNeighbors(exactMatch, 5);
        
        Assertions.assertNotNull(neighbors, "Expected neighbors result for exact match");
        Assertions.assertEquals(0.0, neighbors.distance(), "Expected zero distance for exact match");
        Assertions.assertEquals(exactMatch, neighbors.value(), "Expected the exact element to be returned");
    }

}
