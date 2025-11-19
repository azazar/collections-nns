package com.azazar.collections.nns;

import java.util.Collection;

/**
 *
 * @author Mikhail Yevchenko <spam@azazar.com>
 */
public abstract class AbstractDistanceBasedSet<X> implements DistanceBasedSet<X> {

    protected DistanceCalculator<X> distanceCalculator;

    protected AbstractDistanceBasedSet(DistanceCalculator<X> distanceCalculator) {
        this.distanceCalculator = distanceCalculator;
    }

    @Override
    public boolean remove(X value) {
        if (!contains(value))
            return false;
        throw new UnsupportedOperationException("Item removal is not implemented for " + getClass().toString());
    }

    @Override
    public boolean contains(X value) {
        SearchResult<X> r = findNearest(value);
        if (r == null)
            return false;
        return r instanceof SearchResultWithDistance ? ((SearchResultWithDistance<X>)r).distance() == 0 : value.equals(r.value());
    }

    protected static class BasicSearchResult<X> implements SearchResult<X> {
        
        public final X value;

        public BasicSearchResult(X value) {
            this.value = value;
        }
        
        @Override
        public X value() {
            return value;
        }
    }

    protected static class BasicSearchResultWithDistance<X> extends BasicSearchResult<X> implements SearchResultWithDistance<X> {
        
        public final double distance;

        public BasicSearchResultWithDistance(X value, double distance) {
            super(value);
            this.distance = distance;
        }

        public double distance() {
            return distance;
        }

    }

    protected static class BasicSearchResultSimilar<X> extends BasicSearchResult<X> implements SearchResultWithSimilar<X> {
        
        public final Collection<X> similar;

        public BasicSearchResultSimilar(X value, Collection<X> similar) {
            super(value);
            this.similar = similar;
        }

        public Collection<X> similar() {
            return similar;
        }

    }

    protected static class BasicSearchResultWithDistanceAndSimilar<X> extends BasicSearchResult<X> implements SearchResultWithDistance<X>, SearchResultWithSimilar<X> {
        
        public final double distance;
        public final Collection<X> similar;

        public BasicSearchResultWithDistanceAndSimilar(double distance, Collection<X> similar, X value) {
            super(value);
            this.distance = distance;
            this.similar = similar;
        }

        @Override
        public double distance() {
            return distance;
        }
    
        @Override
        public Collection<X> similar() {
            return similar;
        }

    }

}
