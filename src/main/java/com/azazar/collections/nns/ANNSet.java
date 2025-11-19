package com.azazar.collections.nns;

import com.azazar.util.ExponentialAverage;
import java.io.PrintStream;
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
    
    private DistanceCalculator<X> distanceCalculator;

    public ANNSet(DistanceCalculator<X> distanceCalculator) {
        this.distanceCalculator = distanceCalculator;
    }

//    public int nextIndex = 0;

    // ~88% accuracy, 394msec on 7500 set for 1000 searches
//    private int neighbourhoodSize = 20;
//    private int searchSetSize = 50;
//    private int searchMaxSteps = 20;
    // ~97% accuracy, 574msec on 7500 set for 1000 searches
//    private int neighbourhoodSize = 30;
//    private int searchSetSize = 50;
//    private int searchMaxSteps = 20;
    // ~100% accuracy, 992msec on 7500 set for 1000 searches
    private int neighbourhoodSize = 90;
    private int searchSetSize = 50;
    private int searchMaxSteps = -1;
    //private float maxResultStepsMultiplier = 4f;
    private float maxResultStepsMultiplier = 3f;

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

    public float getMaxResultStepsMultiplier() {
        return maxResultStepsMultiplier;
    }

    public void setMaxResultStepsMultiplier(float maxResultStepsMultiplier) {
        this.maxResultStepsMultiplier = maxResultStepsMultiplier;
    }

    public class Neighbour implements Comparable<Neighbour>, Serializable {

        public final SetElement element;
        public final double distance;

        public Neighbour(SetElement element, double distance) {
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
            Neighbour other = (Neighbour) obj;
            return Objects.equals(element, other.element);
        }

        @Override
        public int compareTo(Neighbour o) {
            return Double.compare(distance, o.distance);
        }

        @Override
        public String toString() {
            return "Neighbour [distance=" + distance + "]";
        }

    }

    public class SetElement implements Serializable {

        @Override
        @SuppressWarnings("unchecked")
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final SetElement other = (SetElement) obj;
            return value == null ? other.value == null : value.equals(other.value);
        }

        @Override
        public int hashCode() {
            return value == null ? 0 : value.hashCode();
        }

        public final X value;

        private Neighbour[] neighbours = null;

        private double worstDistance = Double.MAX_VALUE;

        SetElement(X value) {
            this.value = value;
        }

        public boolean newNeightbour(SetElement element, double distance) {
            if (neighbours != null && distance >= worstDistance && neighbours.length >= neighbourhoodSize) {
                return false;
            }

            if (neighbours == null) {
                neighbours = newNeighbourArray(1);
                neighbours[0] = new Neighbour(element, distance);
                worstDistance = distance;
                return true;
            }

            if (neighbours.length < neighbourhoodSize || distance < worstDistance) {
                int insertIndex = 0;
                int ns = neighbours.length;
                while (insertIndex < ns && neighbours[insertIndex].distance < distance) insertIndex++;
                if (insertIndex >= neighbourhoodSize)
                    return false;
                Neighbour[] nn = newNeighbourArray(neighbours.length + 1);
                System.arraycopy(neighbours, 0, nn, 0, insertIndex);
                nn[insertIndex] = new Neighbour(element, distance);
                System.arraycopy(neighbours, insertIndex, nn, insertIndex + 1, neighbours.length - insertIndex);
                neighbours = nn;
                int nsz = neighbours.length;
                if (nsz > neighbourhoodSize)
                    nsz = neighbourhoodSize;
                worstDistance = neighbours[nsz - 1].distance;
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
            for (Neighbour n : neighbours) {
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

    private int size = 0;

    public int size() {
        return size;
    }

    public SetElement[] toArray() {
        if (root == null)
            return newSetElementArray(0);
        HashSet<SetElement> seen = new HashSet<>();
        LinkedList<SetElement> toVisit = new LinkedList<>();
        toVisit.add(root);
        seen.add(root);
        SetElement e;
        while ((e = toVisit.poll()) != null) {
            for (Neighbour ne : e.neighbours)
                if (!seen.contains(ne.element)) {
                    toVisit.add(ne.element);
                    seen.add(ne.element);
                }
        }
        SetElement[] a = seen.toArray(newSetElementArray(0));
//        Arrays.sort(a, new Comparator<SetElement>() {
//
//            public int compare(SetElement o1, SetElement o2) {
//                if ((o1.value instanceof Comparable) && (o2.value instanceof Comparable))
//                    return ((Comparable)o1.value).compareTo(o2);
//                return String.valueOf(o1.value).compareTo(String.valueOf(o2.value));
//            }
//
//        });
        return a;
    }

    public void dump() {
        System.out.println("Dump");
        for (SetElement e : toArray()) {
            System.out.println(e);
        }
    }

    private SetElement root = null;

    public boolean put(X value) {
        if (root == null) {
            root = new SetElement(value);
            size = 1;
            return true;
        } else {
            ANNSearchResult searchRes = findNearestInternal(value);
            if (searchRes == null) {
                return false;
            } else {
                if (searchRes.distance() == 0d)
                    return false;
                SetElement newEl = new SetElement(value);
                int nn = 0;
                boolean f = searchRes.element.newNeightbour(newEl, searchRes.distance);
                boolean b = newEl.newNeightbour(searchRes.element, searchRes.distance);
                if (f && b)
                    nn++;
                for (Neighbour neighbour : searchRes.neightbours) {
                    f = neighbour.element.newNeightbour(newEl, neighbour.distance);
                    b = newEl.newNeightbour(neighbour.element, neighbour.distance);
                    if (f && b)
                        nn++;
                    if (nn >= neighbourhoodSize)
                        break;
                }
                if (nn > 0) {
                    size++;
//                    root = newEl;
//                    dump();
                }
                return nn > 0;
            }
        }
    }

    private final ExponentialAverage averageVisited = new ExponentialAverage(100);

    public ANNSearchResult findNearestInternal(X value) {
        return findNearestInternal(value, root);
    }

    public ANNSearchResult findNearestInternal(X value, SetElement entry) {
        if (entry == null)
            return null;
        return findNearestInternal(value, new Neighbour(entry, distanceCalculator.calcDistance(entry.value, value)));
    }

    private int maxResultStep = 1;

    public ANNSearchResult findNearestInternal(X value, Neighbour entry) {
        if (size == 0)
            return null;

        double worstDistance = Double.MAX_VALUE;

//        HashSet<SetElement> visited = new HashSet(Math.max(size / 1000, 10));
        HashMap<SetElement, Integer> visited = new HashMap<>((int)Math.max(10, Math.round(averageVisited.value * 2d)));

        LinkedList<Neighbour> nearest = null;
        TreeSet<Neighbour> toVisit = new TreeSet<>();
        toVisit.add(entry);
        Neighbour el;
        int step = 0;
        int maxSteps = searchMaxSteps > 0 ? searchMaxSteps : Math.round(maxResultStepsMultiplier * (float)maxResultStep) + 1;
        while (step++ < maxSteps && (el = toVisit.pollFirst()) != null) {
//            if (visited.contains(el.element))
//                continue;
//            visited.add(el.element);
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
                for (Neighbour elN : el.element.neighbours) {
                    if (!visited.containsKey(elN.element)) {
                        Neighbour nn = new Neighbour(elN.element, distanceCalculator.calcDistance(value, elN.element.value));
                        if (nn.distance == 0d) {
                            synchronized (averageVisited) {
                                averageVisited.add(visited.size());
                            }
                            return new ANNSearchResult(elN.element, elN.distance, nearest);
                        }
                        toVisit.add(nn);
                    }
                }
            }
        }

        synchronized (averageVisited) {
            averageVisited.add(visited.size());
            //System.out.println("V:"+visited.size());
        }

        if (nearest == null)
            return null;
        while (nearest.size() > searchSetSize)
            nearest.removeLast();
        Neighbour nearestNeighbour = nearest.get(0);
        SetElement nearestElement = nearestNeighbour.element;
        Integer steps = visited.get(nearestElement);
//        if (steps == null)
//            throw new NullPointerException("steps==null!");
        if (steps != null && steps > maxResultStep) {
            //System.out.println("steps updated: " + maxResultStep + "=>" + steps + " (+"+((float)(steps.intValue() - maxResultStep) / (float)maxResultStep)+")");
            maxResultStep = steps;
        }
        return new ANNSearchResult(nearestElement, nearestNeighbour.distance, nearest);
    }

    public Neighbors<X> findNearest(X value) {
        return findNearestInternal(value);
    }

    public class ANNSearchResult implements Neighbors<X> {

        public final SetElement element;
        public final double distance;
        public final List<Neighbour> neightbours;

        public ANNSearchResult(SetElement element, double distance, List<Neighbour> neightbours) {
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
    private Neighbour[] newNeighbourArray(int size) {
        return (Neighbour[]) Array.newInstance(Neighbour.class, size);
    }

    @SuppressWarnings("unchecked")
    private SetElement[] newSetElementArray(int size) {
        return (SetElement[]) Array.newInstance(SetElement.class, size);
    }

//    public void writeObject(java.io.ObjectOutputStream out) throws IOException {
//        out.writeObject(distanceCalculator);
//        HashMap<Integer, SetElement> elementsByIndex = new HashMap<Integer, SetElement>();
//        HashSet<SetElement> remaining = new HashSet<SetElement>();
//        remaining.add(root);
//        while (!remaining.isEmpty()) {
//            Iterator<SetElement> i = remaining.iterator();
//            SetElement e = i.next();
//            i.remove();
//            
//            if (!elementsByIndex.containsKey(e.index)) {
//                elementsByIndex.put(e.index, e);
//                for (Neighbour n : e.neighbours) {
//                    if (!elementsByIndex.containsKey(n.element.index)) {
//                        remaining.add(n.element);
//                    }
//                }
//            }
//        }
//
//        out.writeInt(root.index.intValue());
//
//        out.writeInt(elementsByIndex.size());
//        for (SetElement setElement : elementsByIndex.values()) {
//            out.writeInt(setElement.index.intValue());
//            out.writeObject(setElement.value);
//            out.writeDouble(setElement.worstDistance);
//            out.writeInt(setElement.neighbours.length);
//            for (Neighbour n : setElement.neighbours) {
//                out.writeInt(n.element.index.intValue());
//                out.writeDouble(n.distance);
//            }
//        }
//    }
//
//    private static class NeighbourInfo {
//        int index;
//        double distance;
//
//        public NeighbourInfo(int index, double distance) {
//            this.index = index;
//            this.distance = distance;
//        }
//    }
//
//    public static <X> AllNearestNeighbourSearchDistanceSet<X> loadObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
//        AllNearestNeighbourSearchDistanceSet<X> set = new AllNearestNeighbourSearchDistanceSet<X>();
//        set.readObject(in);
//        return set;
//    }
//
//    private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
//        distanceCalculator = (DistanceCalculator<X>) in.readObject();
//
//        HashMap<Integer, SetElement> elementsByIndex = new HashMap();
//        HashMap<Integer, NeighbourInfo[]> neighboursByIndex = new HashMap();
//        
//        int rootIndex = in.readInt();
//        size = in.readInt();
//        for (int i = 0; i < size; i++) {
//            int index = in.readInt();
//            X value = (X) in.readObject();
//            
//            SetElement el = new SetElement(value, index);
//            el.worstDistance = in.readDouble();
//            
//            elementsByIndex.put(index, el);
//
//            NeighbourInfo[] neighbours = new NeighbourInfo[in.readInt()];
//            for (int j = 0; j < neighbours.length; j++) {
//                int idx = in.readInt();
//                double dist = in.readDouble();
//                neighbours[j] = new NeighbourInfo(idx, dist);
//            }
//            
//            neighboursByIndex.put(index, neighbours);
//        }
//        
//        root = elementsByIndex.get(rootIndex);
//
//        for (Map.Entry<Integer, NeighbourInfo[]> entry : neighboursByIndex.entrySet()) {
//            Integer index = entry.getKey();
//            SetElement elem = elementsByIndex.get(index);
//            NeighbourInfo[] neighbourInfos = entry.getValue();
////            elem.neighbours = new Neighbour[neighbourInfos.length];
//            elem.neighbours = (Neighbour[]) Array.newInstance(Neighbour.class, neighbourInfos.length);
//            for (int i = 0; i < neighbourInfos.length; i++)
//                elem.neighbours[i] = new Neighbour(elementsByIndex.get(neighbourInfos[i].index), neighbourInfos[i].distance);
//        }
//    }
//
////    private void readObjectNoData() throws ObjectStreamException {
////    }

    public void dump(PrintStream o) {
        o.println("DistanceBasedSet Dump:");
        for (SetElement e : toArray()) {
            o.println(e);
        }
    }

}
