package com.azazar.collections.nns;

import com.azazar.collections.nns.DistanceBasedSet.SearchResult;

import java.util.BitSet;
import java.util.Random;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class NNSTest {

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
            SearchResult<BitSet> result = set.findNearest(entry);
            Assertions.assertNotNull(result, "Expected a nearest result for every inserted value");
            if (result.value().equals(entry)) {
                exactMatches++;
            }
        }

        Assertions.assertTrue((float)exactMatches / (float)dataset.length > 0.99f, "Nearest neighbour search should return the inserted element most of the time");
    }

    private static AllNearestNeighbourSearchDistanceSet<BitSet> createConfiguredSet() {
        AllNearestNeighbourSearchDistanceSet<BitSet> set = new AllNearestNeighbourSearchDistanceSet<>(BITSET_DISTANCE_CALC);
        set.setNeighbourhoodSize(30);
        set.setSearchSetSize(50);
        set.setSearchMaxSteps(-1);
        set.setMaxResultStepsMultiplier(3f);
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

}
