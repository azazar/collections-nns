package com.azazar.collections.nns;

import java.util.Collection;

/**
 * Represents the result of a proximity/nearest-neighbor search.
 *
 * @author m
 * @param <X> the type of values in the result
 */
public interface ProximityResult<X> {

    /**
     * Returns the closest value from the search result.
     *
     * @return the closest value
     * @throws java.util.NoSuchElementException if no neighbors were found
     */
    default X closest() {
        return nearest().stream().findFirst().orElseThrow().value();
    }

    /**
     * Returns the distance to the closest value.
     *
     * @return the distance to the closest value, or {@link Double#NaN} if no neighbors were found
     */
    default double distance() {
        return nearest().stream().findFirst().map(DistancedValue::distance).orElse(Double.NaN);
    }

    /**
     * Returns the collection of nearest neighbors with their distances.
     *
     * @return a collection of {@link DistancedValue} entries, each containing a value and its distance
     */
    Collection<DistancedValue<X>> nearest();

}
