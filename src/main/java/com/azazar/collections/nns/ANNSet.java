package com.azazar.collections.nns;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Approximate Nearest Neighbor Set implementation using a graph-based approach.
 * Uses a navigable small-world graph structure for efficient approximate search.
 *
 * @author Mikhail Yevchenko <spam@azazar.com>
 * @param <X> Type of elements in the set
 */
public class ANNSet<X> implements DistanceBasedSet<X>, Serializable {

    private static final int DEFAULT_NEIGHBOURHOOD_SIZE = 16;
    private static final int DEFAULT_SEARCH_SET_SIZE = 100;
    private static final int DEFAULT_SEARCH_MAX_STEPS = -1; // -1 means unlimited
    private static final float DEFAULT_ADAPTIVE_STEP_FACTOR = 1.5f;

    private final DistanceCalculator<X> distanceCalculator;
    private final Map<X, Node<X>> nodes;
    private final List<X> entryPoints;
    
    private int neighbourhoodSize = DEFAULT_NEIGHBOURHOOD_SIZE;
    private int searchSetSize = DEFAULT_SEARCH_SET_SIZE;
    private int searchMaxSteps = DEFAULT_SEARCH_MAX_STEPS;
    private float adaptiveStepFactor = DEFAULT_ADAPTIVE_STEP_FACTOR;

    /**
     * Node in the graph structure
     */
    private static class Node<X> implements Serializable {
        final X value;
        final Set<X> neighbors;
        
        Node(X value) {
            this.value = value;
            this.neighbors = new HashSet<>();
        }
    }

    /**
     * Helper class for tracking search candidates
     */
    private static class Candidate<X> implements Comparable<Candidate<X>> {
        final X value;
        final double distance;
        
        Candidate(X value, double distance) {
            this.value = value;
            this.distance = distance;
        }
        
        @Override
        public int compareTo(Candidate<X> o) {
            return Double.compare(this.distance, o.distance);
        }
    }

    public ANNSet(DistanceCalculator<X> distanceCalculator) {
        this.distanceCalculator = distanceCalculator;
        this.nodes = new HashMap<>();
        this.entryPoints = new ArrayList<>();
    }

    public void setNeighbourhoodSize(int neighbourhoodSize) {
        this.neighbourhoodSize = neighbourhoodSize;
    }

    public void setSearchSetSize(int searchSetSize) {
        this.searchSetSize = searchSetSize;
    }

    public void setSearchMaxSteps(int searchMaxSteps) {
        this.searchMaxSteps = searchMaxSteps;
    }

    public void setAdaptiveStepFactor(float adaptiveStepFactor) {
        this.adaptiveStepFactor = adaptiveStepFactor;
    }

    @Override
    public int size() {
        return nodes.size();
    }

    @Override
    public boolean add(X value) {
        if (value == null) {
            throw new NullPointerException("Null values are not supported");
        }
        
        if (nodes.containsKey(value)) {
            return false;
        }
        
        Node<X> newNode = new Node<>(value);
        nodes.put(value, newNode);
        
        if (nodes.size() == 1) {
            // First element becomes entry point
            entryPoints.add(value);
            return true;
        }
        
        // Find nearest neighbors to connect with
        List<Candidate<X>> neighbors = searchKNearest(value, neighbourhoodSize);
        
        // Connect new node to its neighbors
        for (Candidate<X> neighbor : neighbors) {
            newNode.neighbors.add(neighbor.value);
            Node<X> neighborNode = nodes.get(neighbor.value);
            if (neighborNode != null) {
                neighborNode.neighbors.add(value);
                
                // Prune neighbors if needed to maintain neighbourhood size
                pruneNeighbors(neighborNode);
            }
        }
        
        // Occasionally add as entry point for better coverage
        if (entryPoints.size() < 10 && ThreadLocalRandom.current().nextDouble() < 0.1) {
            entryPoints.add(value);
        }
        
        return true;
    }

    @Override
    public boolean remove(X value) {
        Node<X> node = nodes.remove(value);
        if (node == null) {
            return false;
        }
        
        // Remove from entry points
        entryPoints.remove(value);
        
        // Reconnect neighbors to maintain graph connectivity
        for (X neighbor : node.neighbors) {
            Node<X> neighborNode = nodes.get(neighbor);
            if (neighborNode != null) {
                neighborNode.neighbors.remove(value);
                
                // Try to reconnect through other neighbors
                for (X otherNeighbor : node.neighbors) {
                    if (!otherNeighbor.equals(neighbor) && nodes.containsKey(otherNeighbor)) {
                        if (neighborNode.neighbors.size() < neighbourhoodSize) {
                            neighborNode.neighbors.add(otherNeighbor);
                            nodes.get(otherNeighbor).neighbors.add(neighbor);
                        }
                    }
                }
            }
        }
        
        return true;
    }

    @Override
    public Neighbors<X> findNeighbors(X value) {
        return findNeighbors(value, 1);
    }

    @Override
    public Neighbors<X> findNeighbors(X value, int count) {
        if (nodes.isEmpty()) {
            return null;
        }
        
        // Check if exact match exists
        Node<X> existing = nodes.get(value);
        if (existing != null) {
            return new NeighborsImpl<>(value, 0.0, new ArrayList<>(existing.neighbors));
        }
        
        List<Candidate<X>> nearest = searchKNearest(value, count);
        if (nearest.isEmpty()) {
            return null;
        }
        
        Candidate<X> best = nearest.get(0);
        
        // Collect similar neighbors
        List<X> similar = new ArrayList<>();
        for (int i = 1; i < nearest.size(); i++) {
            similar.add(nearest.get(i).value);
        }
        
        return new NeighborsImpl<>(best.value, best.distance, similar);
    }

    @Override
    public boolean contains(X value) {
        return nodes.containsKey(value);
    }

    /**
     * Search for k nearest neighbors using greedy graph traversal
     */
    private List<Candidate<X>> searchKNearest(X query, int k) {
        if (nodes.isEmpty()) {
            return Collections.emptyList();
        }
        
        PriorityQueue<Candidate<X>> candidates = new PriorityQueue<>();
        PriorityQueue<Candidate<X>> results = new PriorityQueue<>(Collections.reverseOrder());
        Set<X> visited = new HashSet<>();
        
        // Start from entry points
        for (X entry : entryPoints) {
            if (entry != null && nodes.containsKey(entry)) {
                double dist = distanceCalculator.calcDistance(query, entry);
                candidates.add(new Candidate<>(entry, dist));
                results.add(new Candidate<>(entry, dist));
                visited.add(entry);
            }
        }
        
        // If no valid entry points, pick random nodes
        if (candidates.isEmpty()) {
            int samplesToTry = Math.min(5, nodes.size());
            List<X> nodeList = new ArrayList<>(nodes.keySet());
            for (int i = 0; i < samplesToTry; i++) {
                X randomNode = nodeList.get(ThreadLocalRandom.current().nextInt(nodeList.size()));
                if (!visited.contains(randomNode)) {
                    double dist = distanceCalculator.calcDistance(query, randomNode);
                    candidates.add(new Candidate<>(randomNode, dist));
                    results.add(new Candidate<>(randomNode, dist));
                    visited.add(randomNode);
                }
            }
        }
        
        int steps = 0;
        int maxSteps = searchMaxSteps > 0 ? searchMaxSteps : nodes.size();
        int searchLimit = (int) (searchSetSize * adaptiveStepFactor);
        
        // Greedy search through the graph
        while (!candidates.isEmpty() && steps < maxSteps && visited.size() < searchLimit) {
            Candidate<X> current = candidates.poll();
            
            // Stop if we're moving away from the target
            if (!results.isEmpty() && current.distance > results.peek().distance) {
                break;
            }
            
            Node<X> currentNode = nodes.get(current.value);
            if (currentNode == null) {
                continue;
            }
            
            // Explore neighbors
            for (X neighbor : currentNode.neighbors) {
                if (!visited.contains(neighbor) && nodes.containsKey(neighbor)) {
                    visited.add(neighbor);
                    double dist = distanceCalculator.calcDistance(query, neighbor);
                    
                    Candidate<X> neighborCandidate = new Candidate<>(neighbor, dist);
                    candidates.add(neighborCandidate);
                    results.add(neighborCandidate);
                    
                    // Keep results bounded
                    if (results.size() > k) {
                        results.poll(); // Remove furthest
                    }
                }
            }
            
            steps++;
        }
        
        // Convert results to list sorted by distance
        List<Candidate<X>> resultList = new ArrayList<>(results);
        resultList.sort(Comparator.naturalOrder());
        
        return resultList.subList(0, Math.min(k, resultList.size()));
    }

    /**
     * Prune neighbors to maintain neighbourhood size constraint
     */
    private void pruneNeighbors(Node<X> node) {
        if (node.neighbors.size() <= neighbourhoodSize) {
            return;
        }
        
        // Keep the closest neighbors
        List<Candidate<X>> neighborDistances = new ArrayList<>();
        for (X neighbor : node.neighbors) {
            double dist = distanceCalculator.calcDistance(node.value, neighbor);
            neighborDistances.add(new Candidate<>(neighbor, dist));
        }
        
        neighborDistances.sort(Comparator.naturalOrder());
        
        Set<X> newNeighbors = new HashSet<>();
        for (int i = 0; i < neighbourhoodSize && i < neighborDistances.size(); i++) {
            newNeighbors.add(neighborDistances.get(i).value);
        }
        
        // Remove bidirectional links for pruned neighbors
        for (X neighbor : node.neighbors) {
            if (!newNeighbors.contains(neighbor)) {
                Node<X> neighborNode = nodes.get(neighbor);
                if (neighborNode != null) {
                    neighborNode.neighbors.remove(node.value);
                }
            }
        }
        
        node.neighbors.clear();
        node.neighbors.addAll(newNeighbors);
    }

    /**
     * Implementation of Neighbors interface
     */
    private static class NeighborsImpl<X> implements Neighbors<X> {
        private final X value;
        private final double distance;
        private final Collection<X> similar;
        
        NeighborsImpl(X value, double distance, Collection<X> similar) {
            this.value = value;
            this.distance = distance;
            this.similar = similar;
        }
        
        @Override
        public X value() {
            return value;
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
