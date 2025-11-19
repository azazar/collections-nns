package com.azazar.collections.nns;

import java.util.Collection;

/**
 *
 * @author Mikhail Yevchenko <spam@azazar.com>
 * @param <X>
 */
public interface DistanceBasedSet<X> {
    public int size();
    public boolean put(X value);
    public SearchResult<X> findNearest(X value);
    public boolean contains(X value);
    public boolean remove(X value);

    public static interface SearchResult<X> {
        public X value();
    }

    public static interface SearchResultWithDistance<X> extends SearchResult<X> {
        public abstract double distance();
    }

    public static interface SearchResultWithSimilar<X> extends SearchResult<X> {
        public abstract Collection<X> similar();
    }
}
