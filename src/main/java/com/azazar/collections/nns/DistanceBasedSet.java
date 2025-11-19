package com.azazar.collections.nns;

/**
 *
 * @author Mikhail Yevchenko <spam@azazar.com>
 * @param <X>
 */
public interface DistanceBasedSet<X> {
    
    int size();
    boolean put(X value);
    Neighbors<X> findNearest(X value);
    
    default boolean contains(X value) {
        Neighbors<X> r = findNearest(value);

        if (r == null)
            return false;

        return r.distance() == 0;
    }

}
