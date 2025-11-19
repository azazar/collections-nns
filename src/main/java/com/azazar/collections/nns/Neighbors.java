package com.azazar.collections.nns;

import java.util.Collection;
import java.util.List;

/**
 *
 * @author m
 * @param <X>
 */
interface Neighbors<X> {

    X value();
    default double distance() {
        return Double.NaN;
    }

    default Collection<X> similar() {
        return List.of();
    }
    
}
