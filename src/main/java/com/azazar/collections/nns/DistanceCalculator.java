package com.azazar.collections.nns;

/**
 *
 * @author Mikhail Yevchenko <spam@azazar.com>
 */
public interface DistanceCalculator<X> {
    public double calcDistance(X o1, X o2);
}
