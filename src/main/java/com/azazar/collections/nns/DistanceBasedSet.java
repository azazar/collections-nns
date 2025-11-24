package com.azazar.collections.nns;

/**
 *
 * @author Mikhail Yevchenko <spam@azazar.com>
 * @param <X>
 */
public interface DistanceBasedSet<X> {
    
    int size();
    boolean add(X value);
    Neighbors<X> findNeighbors(X value);
    
    default boolean contains(X value) {
        Neighbors<X> r = findNeighbors(value);

        if (r == null)
            return false;

        return r.distance() == 0;
    }

}
