package com.azazar.collections.nns;

/**
 * Represents a value along with its distance from a query point.
 *
 * @author m
 * @param <X> the type of the value
 */
public interface DistancedValue<X> {

    /**
     * Returns the value.
     *
     * @return the value
     */
    X value();

    /**
     * Returns the distance from the query point to this value.
     *
     * @return the distance
     */
    double distance();

}
