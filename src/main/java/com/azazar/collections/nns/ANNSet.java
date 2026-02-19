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
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Approximate Nearest Neighbor Set implementation using a graph-based approach.
 * Uses a navigable small-world graph structure for efficient approximate search.
 *
 * <p><b>Thread safety:</b> This class is <em>not</em> thread-safe. All access to
 * {@code add}, {@code remove}, {@code findNeighbors}, and {@code contains} must be
 * externally synchronized if instances are shared across threads. Internal mutable
 * state (the node graph, index structures, and pooled scratch collections) will
 * produce data corruption or exceptions under concurrent mutation.</p>
 *
 * <p><b>Serialization:</b> Transient scratch collections used for search and pruning
 * are lazily re-initialized after deserialization; no special handling is needed by
 * callers.</p>
 *
 * <p><b>Tuning parameters:</b> Core parameters ({@code neighbourhoodSize},
 * {@code searchSetSize}, etc.) should be set <em>before</em> inserting data.
 * Changing them after the index is partially built will not retroactively adjust
 * existing neighborhoods, so the graph will reflect a mix of old and new settings.
 * All setters validate their inputs and throw {@link IllegalArgumentException} on
 * out-of-range values.</p>
 *
 * @author Mikhail Yevchenko <spam@azazar.com>
 * @param <X> Type of elements in the set
 */
public class ANNSet<X> implements DistanceBasedSet<X>, Serializable {

    private static final long serialVersionUID = 1L;

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
    private final Map<X, Integer> nodeIndex; // for O(1) removal from nodeNodes
    private final List<Node<X>> nodeNodes; // indexed node list for O(1) entry-point access
    
    private int neighbourhoodSize = DEFAULT_NEIGHBOURHOOD_SIZE;
    private int searchSetSize = DEFAULT_SEARCH_SET_SIZE;
    private int searchMaxSteps = DEFAULT_SEARCH_MAX_STEPS;
    private float adaptiveStepFactor = DEFAULT_ADAPTIVE_STEP_FACTOR;
    private int numEntryPoints = DEFAULT_NUM_ENTRY_POINTS;
    private float constructionFactor = DEFAULT_CONSTRUCTION_FACTOR;
    private float pruningAlpha = DEFAULT_PRUNING_ALPHA;

    private static class Node<X> implements Serializable {
        private static final long serialVersionUID = 1L;
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

    /**
     * Creates a new ANNSet with the given distance calculator.
     *
     * <p><b>Serialization note:</b> both the {@code distanceCalculator} and the
     * element type {@code X} must be {@link Serializable} for this index to
     * serialize successfully.</p>
     *
     * @param distanceCalculator the distance function; must not be null
     * @throws NullPointerException if {@code distanceCalculator} is null
     */
    public ANNSet(DistanceCalculator<X> distanceCalculator) {
        this.distanceCalculator = Objects.requireNonNull(distanceCalculator, "distanceCalculator must not be null");
        this.nodes = new HashMap<>();
        this.nodeIndex = new HashMap<>();
        this.nodeNodes = new ArrayList<>();
    }

    /**
     * Sets the maximum number of neighbors each node maintains.
     * Controls graph degree and directly affects build cost and search quality.
     *
     * <p>Should be set before inserting data; changing after build does not
     * retroactively adjust existing neighborhoods.</p>
     *
     * @param neighbourhoodSize must be &ge; 1
     * @throws IllegalArgumentException if {@code neighbourhoodSize < 1}
     */
    public void setNeighbourhoodSize(int neighbourhoodSize) {
        if (neighbourhoodSize < 1) {
            throw new IllegalArgumentException("neighbourhoodSize must be >= 1, got " + neighbourhoodSize);
        }
        this.neighbourhoodSize = neighbourhoodSize;
    }

    /**
     * Sets the beam width (ef) used during search. Larger values improve recall
     * at the cost of more distance calculations.
     *
     * @param searchSetSize must be &ge; 1
     * @throws IllegalArgumentException if {@code searchSetSize < 1}
     */
    public void setSearchSetSize(int searchSetSize) {
        if (searchSetSize < 1) {
            throw new IllegalArgumentException("searchSetSize must be >= 1, got " + searchSetSize);
        }
        this.searchSetSize = searchSetSize;
    }

    /**
     * Sets the maximum number of graph-walk steps per search.
     * A value of {@code -1} means unlimited (bounded only by the search budget).
     * A value of {@code 0} disables graph walking entirely: only entry points are
     * evaluated and the refinement phase is skipped, so no neighbor expansion
     * occurs at all.
     *
     * @param searchMaxSteps must be &ge; {@code -1}
     * @throws IllegalArgumentException if {@code searchMaxSteps < -1}
     */
    public void setSearchMaxSteps(int searchMaxSteps) {
        if (searchMaxSteps < -1) {
            throw new IllegalArgumentException("searchMaxSteps must be >= -1, got " + searchMaxSteps);
        }
        this.searchMaxSteps = searchMaxSteps;
    }

    /**
     * Sets the factor applied to {@code searchSetSize} to compute the actual
     * search budget.
     *
     * @param adaptiveStepFactor must be &gt; 0
     * @throws IllegalArgumentException if {@code adaptiveStepFactor <= 0}
     */
    public void setAdaptiveStepFactor(float adaptiveStepFactor) {
        if (adaptiveStepFactor <= 0 || Float.isNaN(adaptiveStepFactor)) {
            throw new IllegalArgumentException("adaptiveStepFactor must be > 0, got " + adaptiveStepFactor);
        }
        this.adaptiveStepFactor = adaptiveStepFactor;
    }

    /**
     * Sets the number of entry points used at the start of each search.
     * A value of {@code -1} uses an automatic heuristic (sqrt(n)).
     *
     * @param numEntryPoints must be &ge; {@code -1} and not {@code 0}
     * @throws IllegalArgumentException if out of range
     */
    public void setNumEntryPoints(int numEntryPoints) {
        if (numEntryPoints < -1 || numEntryPoints == 0) {
            throw new IllegalArgumentException("numEntryPoints must be -1 (auto) or >= 1, got " + numEntryPoints);
        }
        this.numEntryPoints = numEntryPoints;
    }

    /**
     * Sets the multiplier on the search budget used during construction.
     * Higher values explore more candidates when inserting, improving graph
     * quality at the cost of slower builds.
     *
     * @param constructionFactor must be &ge; 1.0
     * @throws IllegalArgumentException if {@code constructionFactor < 1.0}
     */
    public void setConstructionFactor(float constructionFactor) {
        if (constructionFactor < 1.0f || Float.isNaN(constructionFactor)) {
            throw new IllegalArgumentException("constructionFactor must be >= 1.0, got " + constructionFactor);
        }
        this.constructionFactor = constructionFactor;
    }

    /**
     * Sets the alpha parameter for RNG-style edge pruning.
     * Values &gt; 1.0 keep more diverse (angular-spread) edges.
     *
     * @param pruningAlpha must be &gt; 0
     * @throws IllegalArgumentException if {@code pruningAlpha <= 0}
     */
    public void setPruningAlpha(float pruningAlpha) {
        if (pruningAlpha <= 0 || Float.isNaN(pruningAlpha)) {
            throw new IllegalArgumentException("pruningAlpha must be > 0, got " + pruningAlpha);
        }
        this.pruningAlpha = pruningAlpha;
    }

    @Override
    public int size() {
        return nodes.size();
    }

    private void indexNode(X value, Node<X> node) {
        nodes.put(value, node);
        nodeIndex.put(value, nodeNodes.size());
        nodeNodes.add(node);
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
            indexNode(value, newNode);
            return true;
        }
        
        int constructionLimit = (int) (searchSetSize * adaptiveStepFactor * constructionFactor);
        // Search for a few extra candidates to give RNG pruning a wider pool for diverse edges.
        int constructionK = Math.min(neighbourhoodSize + 3, Math.max(1, nodes.size()));
        List<Candidate<X>> neighbors = searchKNearest(value, constructionK, constructionLimit);
        indexNode(value, newNode);
        
        for (int i = 0; i < neighbors.size(); i++) {
            Candidate<X> neighbor = neighbors.get(i);
            newNode.neighbors.put(neighbor.value, neighbor.distance);
            Node<X> neighborNode = neighbor.node;
            if (neighborNode == null) {
                neighborNode = nodes.get(neighbor.value);
            }
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
        // O(1) removal from nodeNodes via swap-with-last
        Integer idx = nodeIndex.remove(value);
        if (idx != null) {
            int lastIdx = nodeNodes.size() - 1;
            if (idx != lastIdx) {
                Node<X> lastNode = nodeNodes.get(lastIdx);
                nodeNodes.set(idx, lastNode);
                nodeIndex.put(lastNode.value, idx);
            }
            nodeNodes.remove(lastIdx);
        }
        
        // Collect surviving neighbor nodes first to avoid ConcurrentModificationException-style issues.
        List<Node<X>> survivingNeighborNodes = new ArrayList<>();
        for (X neighbor : node.neighbors.keySet()) {
            Node<X> neighborNode = nodes.get(neighbor);
            if (neighborNode != null) {
                neighborNode.neighbors.remove(value);
                survivingNeighborNodes.add(neighborNode);
            }
        }
        // Healing: reconnect surviving neighbors of the removed node.
        for (Node<X> neighborNode : survivingNeighborNodes) {
            for (X otherNeighbor : node.neighbors.keySet()) {
                if (!otherNeighbor.equals(neighborNode.value)) {
                    Node<X> otherNode = nodes.get(otherNeighbor);
                    if (otherNode != null && neighborNode.neighbors.size() < neighbourhoodSize) {
                        double dist = distanceCalculator.calcDistance(neighborNode.value, otherNeighbor);
                        neighborNode.neighbors.put(otherNeighbor, dist);
                        otherNode.neighbors.put(neighborNode.value, dist);
                    }
                }
            }
            // Prune after healing to maintain bounded, diverse neighborhoods.
            pruneNeighbors(neighborNode);
        }
        return true;
    }

    @Override
    public ProximityResult<X> findNeighbors(X value) {
        return findNeighbors(value, 1);
    }

    @Override
    public ProximityResult<X> findNeighbors(X value, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be >= 1, got " + count);
        }
        if (nodes.isEmpty()) {
            return new ProximityResultImpl<>(Collections.emptyList());
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
            return new ProximityResultImpl<>(nearestCandidates);
        }
        
        List<Candidate<X>> nearest = searchKNearest(value, count, (int) (searchSetSize * adaptiveStepFactor));
        return new ProximityResultImpl<>(nearest);
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
    private transient List<Node<X>> reusablePruneSelectedNodes;
    private transient Map<X, Double> reusableNewNeighbors;

    private List<Candidate<X>> searchKNearest(X query, int k, int searchLimit) {
        int n = nodeNodes.size();
        int epCount;
        if (numEntryPoints > 0) {
            epCount = numEntryPoints;
        } else {
            // Tuning: sqrt(n) is critical. Any reduction degrades quality severely.
            // sqrt(n)*3/4->distRatio 2.54, *2/3->1.85, /2->2.71, cbrt(n)->recall 91.5%.
            epCount = Math.max(3, (int) Math.sqrt(n));
        }
        // Ensure at least 1 entry point is evaluated when the graph is non-empty,
        // even if searchLimit is very small (e.g. < 6).
        epCount = Math.min(epCount, Math.max(1, searchLimit / 6));
        epCount = Math.min(epCount, n);

        int ef = Math.max(k, searchSetSize);
        // Tuning: IdentityHashMap is used for the visited set because each stored X
        // value has a unique object identity within the graph (stored once in `nodes`).
        // This avoids potentially expensive equals()/hashCode() calls on X during search.
        // If X instances are shared or de-duplicated externally, identity semantics remain
        // correct here because nodeNodes always references the canonical instances from `nodes`.
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

        // Tuning: cache field references in locals to eliminate this-pointer indirection in hot loops.
        final DistanceCalculator<X> dc = this.distanceCalculator;
        final Map<X, Node<X>> nodesMap = this.nodes;

        // Evaluate entry points (evenly-spaced, inline to avoid allocation)
        int step = Math.max(1, n / epCount);
        for (int i = 0; i < epCount; i++) {
            int epIdx = (i * step) % n;
            Node<X> epNode = nodeNodes.get(epIdx);
            X ep = epNode.value;
            if (visited.add(ep)) {
                double dist = dc.calcDistance(query, ep);
                Candidate<X> c = new Candidate<>(ep, epNode, dist);
                candidates.add(c);
                results.add(c);
                if (results.size() > ef) results.poll();
            }
        }

        // -1 = unlimited (bounded only by searchLimit budget).
        // 0  = no graph walking (only entry points are used).
        // >0 = explicit step limit.
        int maxSteps = searchMaxSteps < 0 ? Integer.MAX_VALUE : searchMaxSteps;
        int steps = 0;

        // Tuning: cache worst-result distance to avoid repeated PQ.peek() calls in inner loop.
        double worstDist = results.size() >= ef ? results.peek().distance : Double.MAX_VALUE;

        // Best-first graph traversal with early termination
        while (!candidates.isEmpty() && visited.size() < searchLimit && steps < maxSteps) {
            Candidate<X> current = candidates.poll();
            
            // Early termination: stop if best candidate is worse than worst result
            if (current.distance > worstDist) {
                break;
            }
            
            Node<X> currentNode = current.node;
            if (currentNode == null) continue;

            for (X neighbor : currentNode.neighbors.keySet()) {
                if (visited.add(neighbor)) {
                    double dist = dc.calcDistance(query, neighbor);
                    // Tuning: Skip strictly worse neighbors -- avoids Candidate allocation, nodes.get(),
                    // and PQ operations for neighbors that would be added and immediately polled.
                    if (dist > worstDist) {
                        continue;
                    }
                    Node<X> neighborNode = nodesMap.get(neighbor);
                    Candidate<X> nc = new Candidate<>(neighbor, neighborNode, dist);
                    // Only add to candidates if it could improve results
                    if (dist < worstDist) {
                        candidates.add(nc);
                    }
                    results.add(nc);
                    if (results.size() > ef) results.poll();
                    if (results.size() >= ef) worstDist = results.peek().distance;
                }
            }
            steps++;
        }

        // Refinement: expand unvisited neighbors of the top results to escape
        // local minima caused by early termination or budget exhaustion.
        // Skipped when searchMaxSteps == 0 to honor the "no graph walking" contract.
        List<Candidate<X>> resultList = reusableResultList;
        resultList.clear();
        resultList.addAll(results);
        resultList.sort(Comparator.naturalOrder());

        if (searchMaxSteps != 0) {
            int refineBudget = 10;
            boolean refined = false;
            for (int r = 0; r < Math.min(3, resultList.size()) && refineBudget > 0; r++) {
                Candidate<X> refCandidate = resultList.get(r);
                if (refCandidate.node == null) continue;
                for (X neighbor : refCandidate.node.neighbors.keySet()) {
                    if (refineBudget <= 0) break;
                    if (visited.add(neighbor)) {
                        double dist = dc.calcDistance(query, neighbor);
                        Candidate<X> nc = new Candidate<>(neighbor, nodesMap.get(neighbor), dist);
                        resultList.add(nc);
                        refineBudget--;
                        refined = true;
                    }
                }
            }
            if (refined) {
                resultList.sort(Comparator.naturalOrder());
            }
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
        
        final Map<X, Node<X>> nodesMap = this.nodes;
        
        // Tuning: Reuse containers and use Map.Entry (already exists in HashMap) instead of
        // allocating Candidate objects. Saves ~4.5M object allocations during a 5K-element build.
        if (reusablePruneEntries == null) {
            reusablePruneEntries = new ArrayList<>();
            reusablePruneSelected = new ArrayList<>();
            reusablePruneSelectedNodes = new ArrayList<>();
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
        List<Node<X>> selectedNodes = reusablePruneSelectedNodes;
        selectedNodes.clear();
        int uncachedCalcs = 0;
        int maxUncachedPerPruning = 30; // Tuning: Do NOT reduce to 20 -- causes distRatio 1.49->1.69
        
        for (Map.Entry<X, Double> candidate : sortedEntries) {
            if (newNeighbors.size() >= neighbourhoodSize) break;
            
            boolean keep = true;
            // Tuning: checkLimit=10 is minimum for RNG pruning diversity.
            // Reducing to 8->distRatio 2.22, to 7->2.03.
            int checkLimit = Math.min(selected.size(), 10);
            // Tuning: hoist candidateNode lookup outside inner loop; it's invariant per candidate.
            Node<X> candidateNode = nodesMap.get(candidate.getKey());
            for (int i = 0; i < checkLimit; i++) {
                Map.Entry<X, Double> existing = selected.get(i);
                Double cachedDist = null;
                Node<X> existingNode = selectedNodes.get(i);
                if (existingNode != null) {
                    cachedDist = existingNode.neighbors.get(candidate.getKey());
                }
                if (cachedDist == null && candidateNode != null) {
                    cachedDist = candidateNode.neighbors.get(existing.getKey());
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
                selectedNodes.add(candidateNode);
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
                Node<X> neighborNode = nodesMap.get(entry.getKey());
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
        
        ProximityResultImpl(Collection<? extends DistancedValue<X>> nearest) {
            this.nearest = Collections.unmodifiableCollection(nearest);
        }
        
        @Override
        public Collection<DistancedValue<X>> nearest() { return nearest; }
    }
}
