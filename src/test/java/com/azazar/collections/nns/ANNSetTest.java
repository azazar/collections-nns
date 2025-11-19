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
    void nearestNeighbourSearchReturnsExactMatches() {
        DistanceBasedSet<BitSet> set = createConfiguredSet();
        BitSet[] dataset = createDataset(10, 1000);

        for (BitSet entry : dataset) {
            set.put(entry);
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
        int[] sizes = {1_000, 20_000, 200_000};
        for (int size : sizes) {
            BitSet[] dataset = createDataset(10, size);
            CountingDistanceCalculator<BitSet> calculator = new CountingDistanceCalculator<>(BITSET_DISTANCE_CALC);
            ANNSet<BitSet> set = createConfiguredSet(calculator);
            System.out.println("Seeding set with " + size + " elements");
            int inserted = 0;
            for (BitSet value : dataset) {
                Assertions.assertTrue(set.put(value), "Failed to insert seed value");
                inserted++;
                if (inserted % 10_000 == 0 || inserted == dataset.length) {
                    System.out.println("Inserted " + inserted + " / " + dataset.length);
                }
            }
            calculator.reset();
            System.out.println("Performing probe insertion for set size " + size);
            BitSet probe = mutatedCopy(dataset[size / 2]);
            Assertions.assertTrue(set.put(probe), "Expected probe insertion for set size " + size);
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

}
