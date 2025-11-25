package com.azazar.collections.nns;

/**
 *
 * @author Mikhail Yevchenko <spam@azazar.com>
 * @param <X>
 */
public interface DistanceBasedSet<X> {
    
    int size();
    boolean add(X value);
    boolean remove(X value);
    ProximityResult<X> findNeighbors(X value);
    ProximityResult<X> findNeighbors(X value, int count);
    
    default boolean contains(X value) {
        ProximityResult<X> r = findNeighbors(value);

        if (r == null)
            return false;

        return r.distance() == 0;
    }

}
