package com.azazar.collections.nns;

/**
 *
 * @author Mikhail Yevchenko <spam@azazar.com>
 */
public interface DistanceCalculable<X> {
    public double calcDistanceTo(X other);
}
