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
    // Tuning: Was 20.0 -> 5.0 (3.4x build cost reduction) -> 4.0. Factor 4.0 equals 5.0 in quality
    // because early termination fires before budget is exhausted; smaller value reduces memory pressure.
    // Factor 3.5 degraded distRatio past threshold (1.68 vs limit 1.63). Factor 3.0 was both slower AND worse.
    private static final float DEFAULT_CONSTRUCTION_FACTOR = 4.0f;

    private final DistanceCalculator<X> distanceCalculator;
    private final Map<X, Node<X>> nodes;
    private final List<X> nodeList;
    private final Map<X, Integer> nodeIndex; // for O(1) removal from nodeList
    
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
            // Tuning: Do NOT pre-size or replace with LinkedHashMap -- changing iteration order
            // alters graph construction and produces measurably worse quality.
            this.neighbors = new HashMap<>();
        }
    }

    private static class Candidate<X> implements Comparable<Candidate<X>> {
        final X value;
        // Tuning: cached node ref avoids HashMap lookup during search. However, using
        // neighbor.node in add() caused a 2x wall-clock regression (likely JIT/cache-line effect),
        // so the cached ref is only used in searchKNearest, not in add().
        final Node<X> node; // may be null for query-only candidates
        final double distance;
        
        Candidate(X value, Node<X> node, double distance) {
            this.value = value;
            this.node = node;
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
        this.nodeIndex = new HashMap<>();
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
            nodeIndex.put(value, nodeList.size());
            nodeList.add(value);
            return true;
        }
        
        int constructionLimit = (int) (searchSetSize * adaptiveStepFactor * constructionFactor);
        List<Candidate<X>> neighbors = searchKNearest(value, neighbourhoodSize, constructionLimit);
        nodes.put(value, newNode);
        nodeIndex.put(value, nodeList.size());
        nodeList.add(value);
        
        for (Candidate<X> neighbor : neighbors) {
            newNode.neighbors.put(neighbor.value, neighbor.distance);
            Node<X> neighborNode = nodes.get(neighbor.value);
            if (neighborNode != null) {
                neighborNode.neighbors.put(value, neighbor.distance);
                // Tuning: Lazy pruning (skip prune if size <= neighbourhoodSize + N) was tested
                // but REJECTED: even +5 degrades distRatio from 1.50 to 2.67.
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
        // O(1) removal from nodeList via swap-with-last
        Integer idx = nodeIndex.remove(value);
        if (idx != null) {
            int lastIdx = nodeList.size() - 1;
            if (idx != lastIdx) {
                X lastValue = nodeList.get(lastIdx);
                nodeList.set(idx, lastValue);
                nodeIndex.put(lastValue, idx);
            }
            nodeList.remove(lastIdx);
        }
        
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

    // Tuning: Reuse visited set to reduce GC pressure from IdentityHashMap allocation.
    // Marginal improvement (~1%) but safe -- no quality impact.
    private transient Set<X> reusableVisited;

    private List<Candidate<X>> searchKNearest(X query, int k, int searchLimit) {
        if (nodes.isEmpty()) {
            return Collections.emptyList();
        }

        int n = nodeList.size();
        int epCount;
        if (numEntryPoints > 0) {
            epCount = numEntryPoints;
        } else {
            // Tuning: sqrt(n) is critical. Any reduction degrades quality severely.
            // sqrt(n)*3/4->distRatio 2.54, *2/3->1.85, /2->2.71, cbrt(n)->recall 91.5%.
            epCount = Math.max(3, (int) Math.sqrt(n));
        }
        epCount = Math.min(epCount, searchLimit / 4);
        epCount = Math.min(epCount, n);

        int ef = Math.max(k, searchSetSize);
        if (reusableVisited == null) {
            reusableVisited = Collections.newSetFromMap(new IdentityHashMap<>(searchLimit * 2));
        } else {
            reusableVisited.clear();
        }
        Set<X> visited = reusableVisited;
        PriorityQueue<Candidate<X>> candidates = new PriorityQueue<>(epCount + 1);
        PriorityQueue<Candidate<X>> results = new PriorityQueue<>(ef + 1, Collections.reverseOrder());

        // Evaluate entry points (evenly-spaced, inline to avoid allocation)
        if (epCount > 0) {
            int step = Math.max(1, n / epCount);
            for (int i = 0; i < epCount; i++) {
                X ep = nodeList.get((i * step) % n);
                if (visited.add(ep)) {
                    double dist = distanceCalculator.calcDistance(query, ep);
                    Node<X> epNode = nodes.get(ep);
                    Candidate<X> c = new Candidate<>(ep, epNode, dist);
                    candidates.add(c);
                    results.add(c);
                    if (results.size() > ef) results.poll();
                }
            }
        }

        int maxSteps = searchMaxSteps > 0 ? searchMaxSteps : Integer.MAX_VALUE;
        int steps = 0;

        // Best-first graph traversal with early termination
        while (!candidates.isEmpty() && visited.size() < searchLimit && steps < maxSteps) {
            Candidate<X> current = candidates.poll();
            
            // Early termination: stop if best candidate is worse than worst result
            if (results.size() >= ef && current.distance > results.peek().distance) {
                break;
            }
            
            Node<X> currentNode = current.node;
            if (currentNode == null) continue;

            for (Map.Entry<X, Double> entry : currentNode.neighbors.entrySet()) {
                X neighbor = entry.getKey();
                if (visited.add(neighbor)) {
                    double dist = distanceCalculator.calcDistance(query, neighbor);
                    // Tuning: Skip strictly worse neighbors -- avoids Candidate allocation, nodes.get(),
                    // and PQ operations for neighbors that would be added and immediately polled.
                    if (results.size() >= ef && dist > results.peek().distance) {
                        continue;
                    }
                    Node<X> neighborNode = nodes.get(neighbor);
                    Candidate<X> nc = new Candidate<>(neighbor, neighborNode, dist);
                    // Only add to candidates if it could improve results
                    if (results.size() < ef || dist < results.peek().distance) {
                        candidates.add(nc);
                    }
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
        
        List<Candidate<X>> neighborDistances = new ArrayList<>(node.neighbors.size());
        for (Map.Entry<X, Double> entry : node.neighbors.entrySet()) {
            neighborDistances.add(new Candidate<>(entry.getKey(), null, entry.getValue()));
        }
        neighborDistances.sort(Comparator.naturalOrder());
        
        Map<X, Double> newNeighbors = new HashMap<>();
        List<Candidate<X>> selected = new ArrayList<>();
        int uncachedCalcs = 0;
        int maxUncachedPerPruning = 30; // Tuning: Do NOT reduce to 20 -- causes distRatio 1.49->1.69
        
        for (Candidate<X> candidate : neighborDistances) {
            if (newNeighbors.size() >= neighbourhoodSize) break;
            
            boolean keep = true;
            // Tuning: checkLimit=10 is minimum for RNG pruning diversity.
            // Reducing to 8->distRatio 2.22, to 7->2.03.
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
