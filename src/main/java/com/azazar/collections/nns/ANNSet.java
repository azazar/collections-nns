package com.azazar.collections.nns;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

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
    private static final int DEFAULT_SEARCH_MAX_STEPS = -1;
    private static final float DEFAULT_ADAPTIVE_STEP_FACTOR = 1.5f;
    private static final int DEFAULT_NUM_ENTRY_POINTS = -1;
    private static final float DEFAULT_CONSTRUCTION_FACTOR = 20.0f;

    private final DistanceCalculator<X> distanceCalculator;
    private final Map<X, Node<X>> nodes;
    private final List<X> nodeList;
    
    private int neighbourhoodSize = DEFAULT_NEIGHBOURHOOD_SIZE;
    private int searchSetSize = DEFAULT_SEARCH_SET_SIZE;
    private int searchMaxSteps = DEFAULT_SEARCH_MAX_STEPS;
    private float adaptiveStepFactor = DEFAULT_ADAPTIVE_STEP_FACTOR;
    private int numEntryPoints = DEFAULT_NUM_ENTRY_POINTS;
    private float constructionFactor = DEFAULT_CONSTRUCTION_FACTOR;

    private static class Node<X> implements Serializable {
        final X value;
        final Map<X, Double> neighbors;
        
        Node(X value) {
            this.value = value;
            this.neighbors = new HashMap<>();
        }
    }

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
        this.nodeList = new ArrayList<>();
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

    public void setNumEntryPoints(int numEntryPoints) {
        this.numEntryPoints = numEntryPoints;
    }

    public void setConstructionFactor(float constructionFactor) {
        this.constructionFactor = constructionFactor;
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

        if (nodes.isEmpty()) {
            nodes.put(value, newNode);
            nodeList.add(value);
            return true;
        }
        
        int constructionLimit = (int) (searchSetSize * adaptiveStepFactor * constructionFactor);
        List<Candidate<X>> neighbors = searchKNearest(value, neighbourhoodSize, constructionLimit);
        nodes.put(value, newNode);
        nodeList.add(value);
        
        for (Candidate<X> neighbor : neighbors) {
            newNode.neighbors.put(neighbor.value, neighbor.distance);
            Node<X> neighborNode = nodes.get(neighbor.value);
            if (neighborNode != null) {
                neighborNode.neighbors.put(value, neighbor.distance);
                pruneNeighbors(neighborNode);
            }
        }
        
        return true;
    }

    @Override
    public boolean remove(X value) {
        Node<X> node = nodes.remove(value);
        if (node == null) {
            return false;
        }
        nodeList.remove(value);
        
        for (X neighbor : node.neighbors.keySet()) {
            Node<X> neighborNode = nodes.get(neighbor);
            if (neighborNode != null) {
                neighborNode.neighbors.remove(value);
                for (X otherNeighbor : node.neighbors.keySet()) {
                    if (!otherNeighbor.equals(neighbor) && nodes.containsKey(otherNeighbor)) {
                        if (neighborNode.neighbors.size() < neighbourhoodSize) {
                            double dist = distanceCalculator.calcDistance(neighbor, otherNeighbor);
                            neighborNode.neighbors.put(otherNeighbor, dist);
                            nodes.get(otherNeighbor).neighbors.put(neighbor, dist);
                        }
                    }
                }
            }
        }
        return true;
    }

    @Override
    public ProximityResult<X> findNeighbors(X value) {
        return findNeighbors(value, 1);
    }

    @Override
    public ProximityResult<X> findNeighbors(X value, int count) {
        if (nodes.isEmpty()) {
            return null;
        }
        
        Node<X> existing = nodes.get(value);
        if (existing != null) {
            List<DistancedValue<X>> nearestWithDistances = new ArrayList<>();
            nearestWithDistances.add(new DistancedValueImpl<>(value, 0.0));
            for (Map.Entry<X, Double> entry : existing.neighbors.entrySet()) {
                nearestWithDistances.add(new DistancedValueImpl<>(entry.getKey(), entry.getValue()));
            }
            nearestWithDistances.sort(Comparator.comparingDouble(DistancedValue::distance));
            return new ProximityResultImpl<>(nearestWithDistances.subList(0, Math.min(count, nearestWithDistances.size())));
        }
        
        List<Candidate<X>> nearest = searchKNearest(value, count, (int) (searchSetSize * adaptiveStepFactor));
        if (nearest.isEmpty()) {
            return null;
        }
        
        List<DistancedValue<X>> nearestWithDistances = new ArrayList<>();
        for (Candidate<X> candidate : nearest) {
            nearestWithDistances.add(new DistancedValueImpl<>(candidate.value, candidate.distance));
        }
        return new ProximityResultImpl<>(nearestWithDistances);
    }

    @Override
    public boolean contains(X value) {
        return nodes.containsKey(value);
    }

    private List<X> selectEntryPoints(int count, int offset) {
        int n = Math.min(count, nodeList.size());
        List<X> entries = new ArrayList<>(n);
        if (n <= 0) return entries;
        int step = Math.max(1, nodeList.size() / n);
        for (int i = 0; i < n; i++) {
            entries.add(nodeList.get(((i * step) + offset) % nodeList.size()));
        }
        return entries;
    }

    private List<Candidate<X>> searchKNearest(X query, int k, int searchLimit) {
        if (nodes.isEmpty()) {
            return Collections.emptyList();
        }

        int n = nodeList.size();
        int epCount;
        if (numEntryPoints > 0) {
            epCount = numEntryPoints;
        } else {
            epCount = Math.max(3, (int) Math.sqrt(n));
        }
        epCount = Math.min(epCount, searchLimit / 4);
        epCount = Math.min(epCount, n);

        int ef = Math.max(k, searchSetSize);
        Set<X> visited = Collections.newSetFromMap(new IdentityHashMap<>(searchLimit * 2));
        PriorityQueue<Candidate<X>> candidates = new PriorityQueue<>(epCount + 1);
        PriorityQueue<Candidate<X>> results = new PriorityQueue<>(ef + 1, Collections.reverseOrder());

        // Evaluate entry points (evenly-spaced)
        List<X> entryPoints = selectEntryPoints(epCount, 0);
        for (X ep : entryPoints) {
            if (visited.add(ep)) {
                double dist = distanceCalculator.calcDistance(query, ep);
                Candidate<X> c = new Candidate<>(ep, dist);
                candidates.add(c);
                results.add(c);
                if (results.size() > ef) results.poll();
            }
        }

        int maxSteps = searchMaxSteps > 0 ? searchMaxSteps : Integer.MAX_VALUE;
        int steps = 0;

        // Best-first graph traversal (no early termination)
        while (!candidates.isEmpty() && visited.size() < searchLimit && steps < maxSteps) {
            Candidate<X> current = candidates.poll();
            Node<X> currentNode = nodes.get(current.value);
            if (currentNode == null) continue;

            for (X neighbor : currentNode.neighbors.keySet()) {
                if (visited.add(neighbor)) {
                    double dist = distanceCalculator.calcDistance(query, neighbor);
                    Candidate<X> nc = new Candidate<>(neighbor, dist);
                    candidates.add(nc);
                    results.add(nc);
                    if (results.size() > ef) results.poll();
                }
            }
            steps++;
        }

        List<Candidate<X>> resultList = new ArrayList<>(results);
        resultList.sort(Comparator.naturalOrder());
        return resultList.subList(0, Math.min(k, resultList.size()));
    }

    private void pruneNeighbors(Node<X> node) {
        if (node.neighbors.size() <= neighbourhoodSize) {
            return;
        }
        
        List<Candidate<X>> neighborDistances = new ArrayList<>();
        for (Map.Entry<X, Double> entry : node.neighbors.entrySet()) {
            neighborDistances.add(new Candidate<>(entry.getKey(), entry.getValue()));
        }
        neighborDistances.sort(Comparator.naturalOrder());
        
        Map<X, Double> newNeighbors = new HashMap<>();
        List<Candidate<X>> selected = new ArrayList<>();
        int uncachedCalcs = 0;
        int maxUncachedPerPruning = 30; // cap to control insertion cost
        
        for (Candidate<X> candidate : neighborDistances) {
            if (newNeighbors.size() >= neighbourhoodSize) break;
            
            boolean keep = true;
            int checkLimit = Math.min(selected.size(), 10);
            for (int i = 0; i < checkLimit; i++) {
                Candidate<X> existing = selected.get(i);
                Double cachedDist = null;
                Node<X> existingNode = nodes.get(existing.value);
                if (existingNode != null) {
                    cachedDist = existingNode.neighbors.get(candidate.value);
                }
                if (cachedDist == null) {
                    Node<X> candidateNode = nodes.get(candidate.value);
                    if (candidateNode != null) {
                        cachedDist = candidateNode.neighbors.get(existing.value);
                    }
                }
                
                double existingToCandidateDist;
                if (cachedDist != null) {
                    existingToCandidateDist = cachedDist;
                } else if (uncachedCalcs < maxUncachedPerPruning) {
                    existingToCandidateDist = distanceCalculator.calcDistance(existing.value, candidate.value);
                    uncachedCalcs++;
                } else {
                    continue; // skip this check
                }
                
                if (existingToCandidateDist < candidate.distance) {
                    keep = false;
                    break;
                }
            }
            
            if (keep) {
                newNeighbors.put(candidate.value, candidate.distance);
                selected.add(candidate);
            }
        }
        
        if (newNeighbors.size() < neighbourhoodSize) {
            for (Candidate<X> candidate : neighborDistances) {
                if (newNeighbors.size() >= neighbourhoodSize) break;
                if (!newNeighbors.containsKey(candidate.value)) {
                    newNeighbors.put(candidate.value, candidate.distance);
                }
            }
        }
        
        for (X neighbor : node.neighbors.keySet()) {
            if (!newNeighbors.containsKey(neighbor)) {
                Node<X> neighborNode = nodes.get(neighbor);
                if (neighborNode != null) {
                    neighborNode.neighbors.remove(node.value);
                }
            }
        }
        
        node.neighbors.clear();
        node.neighbors.putAll(newNeighbors);
    }

    private static class DistancedValueImpl<X> implements DistancedValue<X> {
        private final X value;
        private final double distance;
        
        DistancedValueImpl(X value, double distance) {
            this.value = value;
            this.distance = distance;
        }
        
        @Override
        public X value() { return value; }
        
        @Override
        public double distance() { return distance; }
    }

    private static class ProximityResultImpl<X> implements ProximityResult<X> {
        private final Collection<DistancedValue<X>> nearest;
        
        ProximityResultImpl(Collection<DistancedValue<X>> nearest) {
            this.nearest = nearest;
        }
        
        @Override
        public Collection<DistancedValue<X>> nearest() { return nearest; }
    }
}
