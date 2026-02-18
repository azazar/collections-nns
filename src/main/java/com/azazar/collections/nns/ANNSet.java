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
    // Tuning: Alpha > 1.0 relaxes the RNG covering test in pruning, requiring a selected neighbor
    // to be significantly closer to the candidate before rejecting it. This keeps more diverse
    // (angular-spread) edges, improving graph navigability at no extra distance-calc cost.
    private static final float DEFAULT_PRUNING_ALPHA = 1.0f;

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
    private float pruningAlpha = DEFAULT_PRUNING_ALPHA;

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

    private static class Candidate<X> implements Comparable<Candidate<X>>, DistancedValue<X> {
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

        @Override
        public X value() { return value; }

        @Override
        public double distance() { return distance; }
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

    public void setPruningAlpha(float pruningAlpha) {
        this.pruningAlpha = pruningAlpha;
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
        // Search for a few extra candidates to give RNG pruning a wider pool for diverse edges.
        int constructionK = Math.min(neighbourhoodSize + 3, Math.max(1, nodes.size()));
        List<Candidate<X>> neighbors = searchKNearest(value, constructionK, constructionLimit);
        nodes.put(value, newNode);
        nodeIndex.put(value, nodeList.size());
        nodeList.add(value);
        
        for (int i = 0; i < neighbors.size(); i++) {
            Candidate<X> neighbor = neighbors.get(i);
            newNode.neighbors.put(neighbor.value, neighbor.distance);
            Node<X> neighborNode = nodes.get(neighbor.value);
            if (neighborNode != null) {
                neighborNode.neighbors.put(value, neighbor.distance);
                // Only prune the core neighbors; extras are pruned naturally later.
                if (i < neighbourhoodSize) {
                    pruneNeighbors(neighborNode);
                }
            }
        }
        // Prune new node to select diverse edges from the extended candidate set.
        pruneNeighbors(newNode);
        
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
            List<Candidate<X>> nearestCandidates = new ArrayList<>();
            nearestCandidates.add(new Candidate<>(value, null, 0.0));
            for (Map.Entry<X, Double> entry : existing.neighbors.entrySet()) {
                nearestCandidates.add(new Candidate<>(entry.getKey(), null, entry.getValue()));
            }
            Collections.sort(nearestCandidates);
            if (nearestCandidates.size() > count) {
                nearestCandidates.subList(count, nearestCandidates.size()).clear();
            }
            @SuppressWarnings("unchecked")
            List<DistancedValue<X>> result = (List<DistancedValue<X>>)(List<?>) nearestCandidates;
            return new ProximityResultImpl<>(result);
        }
        
        List<Candidate<X>> nearest = searchKNearest(value, count, (int) (searchSetSize * adaptiveStepFactor));
        if (nearest.isEmpty()) {
            return null;
        }
        
        @SuppressWarnings("unchecked")
        List<DistancedValue<X>> nearestWithDistances = (List<DistancedValue<X>>)(List<?>) new ArrayList<>(nearest);
        return new ProximityResultImpl<>(nearestWithDistances);
    }

    @Override
    public boolean contains(X value) {
        return nodes.containsKey(value);
    }

    // Tuning: Reuse collections across method calls to reduce GC pressure.
    private transient Set<X> reusableVisited;
    private transient PriorityQueue<Candidate<X>> reusableCandidatesPQ;
    private transient PriorityQueue<Candidate<X>> reusableResultsPQ;
    private transient List<Candidate<X>> reusableResultList;
    // Tuning: pruneNeighbors is called ~N*neighbourhoodSize times; reusing containers
    // and using Map.Entry (already exists in HashMap) avoids millions of Candidate allocations.
    private transient List<Map.Entry<X, Double>> reusablePruneEntries;
    private transient List<Map.Entry<X, Double>> reusablePruneSelected;
    private transient Map<X, Double> reusableNewNeighbors;

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
        epCount = Math.min(epCount, searchLimit / 6);
        epCount = Math.min(epCount, n);

        int ef = Math.max(k, searchSetSize);
        if (reusableVisited == null) {
            reusableVisited = Collections.newSetFromMap(new IdentityHashMap<>(searchLimit * 2));
            reusableCandidatesPQ = new PriorityQueue<>();
            reusableResultsPQ = new PriorityQueue<>(Collections.reverseOrder());
            reusableResultList = new ArrayList<>();
        } else {
            reusableVisited.clear();
            reusableCandidatesPQ.clear();
            reusableResultsPQ.clear();
        }
        Set<X> visited = reusableVisited;
        PriorityQueue<Candidate<X>> candidates = reusableCandidatesPQ;
        PriorityQueue<Candidate<X>> results = reusableResultsPQ;

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

        // Refinement: expand unvisited neighbors of the top results to escape
        // local minima caused by early termination or budget exhaustion.
        List<Candidate<X>> resultList = reusableResultList;
        resultList.clear();
        resultList.addAll(results);
        resultList.sort(Comparator.naturalOrder());

        int refineBudget = 10;
        boolean refined = false;
        for (int r = 0; r < Math.min(3, resultList.size()) && refineBudget > 0; r++) {
            Candidate<X> refCandidate = resultList.get(r);
            if (refCandidate.node == null) continue;
            for (Map.Entry<X, Double> entry : refCandidate.node.neighbors.entrySet()) {
                if (refineBudget <= 0) break;
                X neighbor = entry.getKey();
                if (visited.add(neighbor)) {
                    double dist = distanceCalculator.calcDistance(query, neighbor);
                    Candidate<X> nc = new Candidate<>(neighbor, nodes.get(neighbor), dist);
                    resultList.add(nc);
                    refineBudget--;
                    refined = true;
                }
            }
        }
        if (refined) {
            resultList.sort(Comparator.naturalOrder());
        }

        if (resultList.size() > k) {
            resultList.subList(k, resultList.size()).clear();
        }
        return resultList;
    }

    private void pruneNeighbors(Node<X> node) {
        if (node.neighbors.size() <= neighbourhoodSize) {
            return;
        }
        
        // Tuning: Reuse containers and use Map.Entry (already exists in HashMap) instead of
        // allocating Candidate objects. Saves ~4.5M object allocations during a 5K-element build.
        if (reusablePruneEntries == null) {
            reusablePruneEntries = new ArrayList<>();
            reusablePruneSelected = new ArrayList<>();
            reusableNewNeighbors = new HashMap<>();
        }
        List<Map.Entry<X, Double>> sortedEntries = reusablePruneEntries;
        sortedEntries.clear();
        sortedEntries.addAll(node.neighbors.entrySet());
        sortedEntries.sort((a, b) -> Double.compare(a.getValue(), b.getValue()));
        
        Map<X, Double> newNeighbors = reusableNewNeighbors;
        newNeighbors.clear();
        List<Map.Entry<X, Double>> selected = reusablePruneSelected;
        selected.clear();
        int uncachedCalcs = 0;
        int maxUncachedPerPruning = 30; // Tuning: Do NOT reduce to 20 -- causes distRatio 1.49->1.69
        
        for (Map.Entry<X, Double> candidate : sortedEntries) {
            if (newNeighbors.size() >= neighbourhoodSize) break;
            
            boolean keep = true;
            // Tuning: checkLimit=10 is minimum for RNG pruning diversity.
            // Reducing to 8->distRatio 2.22, to 7->2.03.
            int checkLimit = Math.min(selected.size(), 10);
            for (int i = 0; i < checkLimit; i++) {
                Map.Entry<X, Double> existing = selected.get(i);
                Double cachedDist = null;
                Node<X> existingNode = nodes.get(existing.getKey());
                if (existingNode != null) {
                    cachedDist = existingNode.neighbors.get(candidate.getKey());
                }
                if (cachedDist == null) {
                    Node<X> candidateNode = nodes.get(candidate.getKey());
                    if (candidateNode != null) {
                        cachedDist = candidateNode.neighbors.get(existing.getKey());
                    }
                }
                
                double existingToCandidateDist;
                if (cachedDist != null) {
                    existingToCandidateDist = cachedDist;
                } else if (uncachedCalcs < maxUncachedPerPruning) {
                    existingToCandidateDist = distanceCalculator.calcDistance(existing.getKey(), candidate.getKey());
                    uncachedCalcs++;
                } else {
                    continue; // skip this check
                }
                
                if (existingToCandidateDist * pruningAlpha < candidate.getValue()) {
                    keep = false;
                    break;
                }
            }
            
            if (keep) {
                newNeighbors.put(candidate.getKey(), candidate.getValue());
                selected.add(candidate);
            }
        }
        
        if (newNeighbors.size() < neighbourhoodSize) {
            for (Map.Entry<X, Double> candidate : sortedEntries) {
                if (newNeighbors.size() >= neighbourhoodSize) break;
                if (!newNeighbors.containsKey(candidate.getKey())) {
                    newNeighbors.put(candidate.getKey(), candidate.getValue());
                }
            }
        }
        
        for (Map.Entry<X, Double> entry : sortedEntries) {
            if (!newNeighbors.containsKey(entry.getKey())) {
                Node<X> neighborNode = nodes.get(entry.getKey());
                if (neighborNode != null) {
                    neighborNode.neighbors.remove(node.value);
                }
            }
        }
        
        node.neighbors.clear();
        node.neighbors.putAll(newNeighbors);
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
