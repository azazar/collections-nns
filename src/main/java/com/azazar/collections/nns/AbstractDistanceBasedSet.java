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

    private static final DistanceCalculator DEFAULT_CALCULATOR = new DistanceCalculator() {

        @Override
        public int hashCode() {
            return 35734;
        }

        public double calcDistance(Object o1, Object o2) {
            return ((DistanceCalculable)o1).calcDistanceTo(o2);
        }

        @Override
        public boolean equals(Object obj) {
            return obj == this;
        }
    };

    protected AbstractDistanceBasedSet() {
        this.distanceCalculator = DEFAULT_CALCULATOR;
    }

    public boolean remove(X value) {
        if (!contains(value))
            return false;
        throw new UnsupportedOperationException("Item removal is not implemented for " + getClass().toString());
    }

    public boolean contains(X value) {
        SearchResult<X> r = findNearest(value);
        if (r == null)
            return false;
        return r instanceof SearchResultWithDistance ? ((SearchResultWithDistance)r).distance() == 0 : value.equals(r.value());
    }

    protected static class BasicSearchResult<X> implements SearchResult<X> {
        
        public final X value;

        public BasicSearchResult(X value) {
            this.value = value;
        }
        
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

        public double distance() {
            return distance;
        }
    
        public Collection<X> similar() {
            return similar;
        }

    }

}
