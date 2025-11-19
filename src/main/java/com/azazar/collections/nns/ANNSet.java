package com.azazar.collections.nns;

import com.azazar.util.ExponentialAverage;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.AbstractList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 *
 * @author Mikhail Yevchenko <spam@azazar.com>
 */
public class ANNSet<X> implements DistanceBasedSet<X>, Serializable {

    private static final long serialVersionUID = 28345792346L;
    
    private final DistanceCalculator<X> distanceCalculator;

    public ANNSet(DistanceCalculator<X> distanceCalculator) {
        this.distanceCalculator = distanceCalculator;
    }

    private int neighbourhoodSize = 90;
    private int searchSetSize = 50;
    private int searchMaxSteps = -1;
    private float adaptiveStepFactor = 3f;

    public int getNeighbourhoodSize() {
        return neighbourhoodSize;
    }

    public void setNeighbourhoodSize(int neighbourhoodSize) {
        this.neighbourhoodSize = neighbourhoodSize;
    }

    public int getSearchMaxSteps() {
        return searchMaxSteps;
    }

    public void setSearchMaxSteps(int searchMaxSteps) {
        this.searchMaxSteps = searchMaxSteps;
    }

    public int getSearchSetSize() {
        return searchSetSize;
    }

    public void setSearchSetSize(int searchSetSize) {
        this.searchSetSize = searchSetSize;
    }

    public float getAdaptiveStepFactor() {
        return adaptiveStepFactor;
    }

    public void setAdaptiveStepFactor(float maxResultStepsMultiplier) {
        this.adaptiveStepFactor = maxResultStepsMultiplier;
    }

    public class NeighborEntry implements Comparable<NeighborEntry>, Serializable {

        public final IndexNode element;
        public final double distance;

        public NeighborEntry(IndexNode element, double distance) {
            this.element = element;
            this.distance = distance;
        }

        @Override
        public int hashCode() {
            return Objects.hash(element);
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            NeighborEntry other = (NeighborEntry) obj;
            return Objects.equals(element, other.element);
        }

        @Override
        public int compareTo(NeighborEntry o) {
            return Double.compare(distance, o.distance);
        }

        @Override
        public String toString() {
            return "Neighbour [distance=" + distance + "]";
        }

    }

    public class IndexNode implements Serializable {

        @Override
        @SuppressWarnings("unchecked")
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final IndexNode other = (IndexNode) obj;
            return value == null ? other.value == null : value.equals(other.value);
        }

        @Override
        public int hashCode() {
            return value == null ? 0 : value.hashCode();
        }

        public final X value;

        private NeighborEntry[] neighbours = null;

        private double farthestNeighborDistance = Double.MAX_VALUE;

        IndexNode(X value) {
            this.value = value;
        }

        public boolean tryAttachNeighbor(IndexNode element, double distance) {
            if (neighbours != null && distance >= farthestNeighborDistance && neighbours.length >= neighbourhoodSize) {
                return false;
            }

            if (neighbours == null) {
                neighbours = newNeighbourArray(1);
                neighbours[0] = new NeighborEntry(element, distance);
                farthestNeighborDistance = distance;
                return true;
            }

            if (neighbours.length < neighbourhoodSize || distance < farthestNeighborDistance) {
                int insertIndex = 0;
                int ns = neighbours.length;
                while (insertIndex < ns && neighbours[insertIndex].distance < distance) insertIndex++;
                if (insertIndex >= neighbourhoodSize)
                    return false;
                NeighborEntry[] nn = newNeighbourArray(neighbours.length + 1);
                System.arraycopy(neighbours, 0, nn, 0, insertIndex);
                nn[insertIndex] = new NeighborEntry(element, distance);
                System.arraycopy(neighbours, insertIndex, nn, insertIndex + 1, neighbours.length - insertIndex);
                neighbours = nn;
                int nsz = neighbours.length;
                if (nsz > neighbourhoodSize)
                    nsz = neighbourhoodSize;
                farthestNeighborDistance = neighbours[nsz - 1].distance;
                return true;
            }
            return false;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(value);
            sb.append('[');
            boolean f = true;
            for (NeighborEntry n : neighbours) {
                if (f)
                    f = false;
                else
                    sb.append(',');
                sb.append(n.element.value);
            }
            sb.append(']');
            return sb.toString();
        }

    }

    private int elementCount = 0;

    @Override
    public int size() {
        return elementCount;
    }

    public IndexNode[] toArray() {
        if (entryPoint == null)
            return newIndexNodeArray(0);
        HashSet<IndexNode> seen = new HashSet<>();
        LinkedList<IndexNode> toVisit = new LinkedList<>();
        toVisit.add(entryPoint);
        seen.add(entryPoint);
        IndexNode e;
        while ((e = toVisit.poll()) != null) {
            for (NeighborEntry ne : e.neighbours)
                if (!seen.contains(ne.element)) {
                    toVisit.add(ne.element);
                    seen.add(ne.element);
                }
        }
        IndexNode[] a = seen.toArray(newIndexNodeArray(0));

        return a;
    }

    public void dump() {
        System.out.println("Dump");
        for (IndexNode e : toArray()) {
            System.out.println(e);
        }
    }

    private IndexNode entryPoint = null;

    @Override
    public boolean put(X value) {
        if (entryPoint == null) {
            entryPoint = new IndexNode(value);
            elementCount = 1;
            return true;
        } else {
            ANNSearchResult searchRes = findNearestInternal(value);
            if (searchRes == null) {
                return false;
            } else {
                if (searchRes.distance() == 0d)
                    return false;
                IndexNode newEl = new IndexNode(value);
                int nn = 0;
                boolean f = searchRes.element.tryAttachNeighbor(newEl, searchRes.distance);
                boolean b = newEl.tryAttachNeighbor(searchRes.element, searchRes.distance);
                if (f && b)
                    nn++;
                for (NeighborEntry neighbour : searchRes.neightbours) {
                    f = neighbour.element.tryAttachNeighbor(newEl, neighbour.distance);
                    b = newEl.tryAttachNeighbor(neighbour.element, neighbour.distance);
                    if (f && b)
                        nn++;
                    if (nn >= neighbourhoodSize)
                        break;
                }
                if (nn > 0) {
                    elementCount++;
                }
                return nn > 0;
            }
        }
    }

    private final ExponentialAverage visitedNodesEma = new ExponentialAverage(100);

    public ANNSearchResult findNearestInternal(X value) {
        return findNearestInternal(value, entryPoint);
    }

    public ANNSearchResult findNearestInternal(X value, IndexNode entry) {
        if (entry == null)
            return null;
        return findNearestInternal(value, new NeighborEntry(entry, distanceCalculator.calcDistance(entry.value, value)));
    }

    private int maxResultStep = 1;

    public ANNSearchResult findNearestInternal(X value, NeighborEntry entry) {
        if (elementCount == 0)
            return null;

        double worstDistance = Double.MAX_VALUE;

        HashMap<IndexNode, Integer> visited = new HashMap<>((int)Math.max(10, Math.round(visitedNodesEma.value * 2d)));

        LinkedList<NeighborEntry> nearest = null;
        TreeSet<NeighborEntry> toVisit = new TreeSet<>();
        toVisit.add(entry);
        NeighborEntry el;
        int step = 0;
        int maxSteps = searchMaxSteps > 0 ? searchMaxSteps : Math.round(adaptiveStepFactor * (float)maxResultStep) + 1;
        while (step++ < maxSteps && (el = toVisit.pollFirst()) != null) {
            if (visited.containsKey(el.element))
                continue;
            visited.put(el.element, step);

            if (nearest == null) {
                nearest = new LinkedList<>();
                nearest.add(el);
                worstDistance = el.distance;
            } else if (nearest.size() < searchSetSize || el.distance < worstDistance) {
                int insertIndex = 0;
                while (insertIndex < nearest.size() && nearest.get(insertIndex).distance < el.distance) insertIndex++;
                if (insertIndex >= searchSetSize)
                    continue;
                nearest.add(insertIndex, el);
                worstDistance = nearest.get(Math.min(nearest.size(), searchSetSize) - 1).distance;
            }

            if (el.element.neighbours != null && el.distance <= worstDistance) {
                for (NeighborEntry elN : el.element.neighbours) {
                    if (!visited.containsKey(elN.element)) {
                        NeighborEntry nn = new NeighborEntry(elN.element, distanceCalculator.calcDistance(value, elN.element.value));
                        if (nn.distance == 0d) {
                            synchronized (visitedNodesEma) {
                                visitedNodesEma.add(visited.size());
                            }
                            return new ANNSearchResult(elN.element, elN.distance, nearest);
                        }
                        toVisit.add(nn);
                    }
                }
            }
        }

        synchronized (visitedNodesEma) {
            visitedNodesEma.add(visited.size());
        }

        if (nearest == null)
            return null;
        while (nearest.size() > searchSetSize)
            nearest.removeLast();
        NeighborEntry nearestNeighbour = nearest.get(0);
        IndexNode nearestElement = nearestNeighbour.element;
        Integer steps = visited.get(nearestElement);
        if (steps != null && steps > maxResultStep) {
            maxResultStep = steps;
        }
        return new ANNSearchResult(nearestElement, nearestNeighbour.distance, nearest);
    }

    @Override
    public Neighbors<X> findNeighbors(X value) {
        return findNearestInternal(value);
    }

    public class ANNSearchResult implements Neighbors<X> {

        public final IndexNode element;
        public final double distance;
        public final List<NeighborEntry> neightbours;

        public ANNSearchResult(IndexNode element, double distance, List<NeighborEntry> neightbours) {
            this.element = element;
            this.distance = distance;
            this.neightbours = neightbours;
        }

        @Override
        public X value() {
            return element.value;
        }

        @Override
        public double distance() {
            return distance;
        }

        @Override
        public Collection<X> similar() {
            return new AbstractList<X>() {

                @Override
                public X get(int index) {
                    return neightbours.get(index).element.value;
                }

                @Override
                public int size() {
                    return neightbours.size();
                }
            };

        }

    }

    @SuppressWarnings("unchecked")
    private NeighborEntry[] newNeighbourArray(int size) {
        return (NeighborEntry[]) Array.newInstance(NeighborEntry.class, size);
    }

    @SuppressWarnings("unchecked")
    private IndexNode[] newIndexNodeArray(int size) {
        return (IndexNode[]) Array.newInstance(IndexNode.class, size);
    }

}
