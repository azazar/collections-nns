package com.azazar.collections.nns;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Array;
import java.util.*;

/**
 *
 * @author Mikhail Yevchenko <spam@azazar.com>
 */
public class NewNNS<X> extends AbstractDistanceBasedSet<X> implements HackSerializable {

    public NewNNS() {
    }

    public NewNNS(DistanceCalculator<X> distanceCalculator) {
        super(distanceCalculator);
    }

    private int linearLimit = 500;
    private int linearAddLimit = Integer.MAX_VALUE;
//    private int linearAddLimit = 1000;
    private int neighbourhoodSize = 1000;
    private int maxSteps = 3;
    private int addStepsBonus = 3;
    private int maxStepsUpdateBonus = 1;

    private int nextId = 0;

    public class Element {
        private int id = nextId++;

        private X value;

        public Element(X value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return String.valueOf(value);
        }

        private double worstDistance = Double.MAX_VALUE;
        private Collection<Neighbour> neighbours = null;
        private TreeSet<Neighbour> sortedNeighbours = null;

        public boolean addIfCloseNeighbour(Element element, double distance) {
            boolean added = addIfCloseNeighbourInternal(element, distance);
            return element.addIfCloseNeighbourInternal(this, distance) || added;
        }

        private boolean addIfCloseNeighbourInternal(Element element, double distance) {
            if (sortedNeighbours == null) {
                sortedNeighbours = new TreeSet<Neighbour>();
                worstDistance = distance;
                sortedNeighbours.add(new Neighbour(element, distance));
                neighbours = sortedNeighbours;
                return true;
            } else {
                if (sortedNeighbours.size() >= neighbourhoodSize && distance >= worstDistance)
                    return false;
                if (!sortedNeighbours.add(new Neighbour(element, distance)))
                    return false;
                if (sortedNeighbours.size() > neighbourhoodSize)
                    sortedNeighbours.pollLast();
                worstDistance = sortedNeighbours.last().distance;
                neighbours = sortedNeighbours;
                return true;
            }
        }

        private void optimizeForRead() {
//            if (neighbours == sortedNeighbours)
//                neighbours = new ArrayList<Neighbour>(sortedNeighbours);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final Element other = (Element) obj;
            if (this.id != other.id) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 83 * hash + this.id;
            return hash;
        }

    }
    
    public class Neighbour implements Comparable<Neighbour> {
        public final Element element;
        public final double distance;

        public Neighbour(Element element, double distance) {
            this.element = element;
            this.distance = distance;
        }

        @Override
        public String toString() {
            return String.valueOf(element) + "(dist:"+distance+")";
        }

        public int compareTo(Neighbour o) {
            int r = Double.compare(distance, o.distance);
            if (r != 0)
                return r;
            return element.id - o.element.id;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final Neighbour other = (Neighbour) obj;
            if (this.element != other.element && (this.element == null || !this.element.equals(other.element))) {
                return false;
            }
            if (Double.doubleToLongBits(this.distance) != Double.doubleToLongBits(other.distance)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 23 * hash + (this.element != null ? this.element.hashCode() : 0);
            hash = 23 * hash + (int) (Double.doubleToLongBits(this.distance) ^ (Double.doubleToLongBits(this.distance) >>> 32));
            return hash;
        }

    }

    private final ArrayList<Element> elements = new ArrayList<Element>();

    public int size() {
        return elements.size();
    }

    public synchronized boolean put(X value) {
        Element newElement = new Element(value);
        int linearMax = Math.max(Math.max(linearAddLimit, linearLimit), neighbourhoodSize);
        if (elements.size() < linearMax) {
            for (Element element : elements) {
                double d = distanceCalculator.calcDistance(value, element.value);
                element.addIfCloseNeighbour(newElement, d);
            }
        } else {
            NNSSearchResultWithSimilar searchResult = (NNSSearchResultWithSimilar) findNearest(value, true, maxSteps + addStepsBonus);

            searchResult.element.addIfCloseNeighbour(newElement, searchResult.distance);

            for (Neighbour neighbour : searchResult.similar) {
                neighbour.element.addIfCloseNeighbour(newElement, neighbour.distance);
                if (newElement.neighbours.size() >= neighbourhoodSize)
                    break;
            }
        }
        elements.add(newElement);
        return true;
    }

    public class NNSSearchResult extends BasicSearchResultWithDistance<X> {

        public final Element element;

        public NNSSearchResult(Element element, double distance) {
            super(element.value, distance);
            this.element = element;
        }

    }

    public class NNSSearchResultWithSimilar extends NNSSearchResult implements SearchResultWithSimilar<X> {

        public final TreeSet<Neighbour> similar;

        public NNSSearchResultWithSimilar(Element element, double distance, TreeSet<Neighbour> similar) {
            super(element, distance);
            this.similar = similar;
        }

        public Collection<X> similar() {
            return new AbstractCollection<X>() {

                @Override
                public Iterator<X> iterator() {
                    return new Iterator<X>() {

                        private final Iterator<Neighbour> wrapped = similar.iterator();

                        public boolean hasNext() {
                            return wrapped.hasNext();
                        }

                        public X next() {
                            return wrapped.next().element.value;
                        }

                        public void remove() {
                            throw new UnsupportedOperationException("Not supported.");
                        }
                    };
                }

                @Override
                public int size() {
                    return similar.size();
                }
            };
        }
        
    }

    public NNSSearchResult findNearestElementLinear(X value, int searchLimit) {
        if (elements.isEmpty())
            return null;
        Element best = elements.get(0);
        double bestDistance = distanceCalculator.calcDistance(value, best.value);
        for(int i = 1; i < Math.min(elements.size(), searchLimit); i++) {
            Element e = elements.get(i);
            double d = distanceCalculator.calcDistance(value, e.value);
            if (d < bestDistance) {
                bestDistance = d;
                best = e;
                if (bestDistance <= 0d)
                    break;
            }
        }
        return new NNSSearchResult(best, bestDistance);
    }

    public SearchResult<X> findNearest(X value) {
        return findNearest(value, false, maxSteps);
    }

    public NNSSearchResult findNearest(X value, boolean findSimilar, int maxSteps) {
        if (elements.isEmpty())
            return null;
        NNSSearchResult lsr = findNearestElementLinear(value, linearLimit);

        if (linearLimit >= elements.size())
            return lsr;
        
        Element best = lsr.element;
        double bestDistance = lsr.distance;

        HashSet<Element> visited = new HashSet<Element>();

        Neighbour n;
        Element e;
        int step = 0;
        
        TreeSet<Neighbour> toVisit = new TreeSet<Neighbour>();
        toVisit.add(new Neighbour(best, bestDistance));
        
        TreeSet<Neighbour> similar = findSimilar ? new TreeSet<Neighbour>() : null;

        HashMap<Element, Integer> stepsLog = maxStepsUpdateBonus > 0 ? new HashMap<Element, Integer>() : null;

        mainLoop:
        while ((step < maxSteps) && ((n = toVisit.pollFirst()) != null)) {
            e = n.element;

            if (similar != null)
                similar.add(n);
            if (stepsLog != null && !stepsLog.containsKey(e))
                stepsLog.put(e, step);

            if (!visited.add(e))
                continue;
            
            step++;

            if (e.neighbours != null) {
                for (Neighbour neighbour : e.neighbours) {
                    if (visited.contains(neighbour.element))
                        continue;

                    Neighbour nn = new Neighbour(neighbour.element, distanceCalculator.calcDistance(value, neighbour.element.value));

                    if (similar != null)
                        similar.add(nn);
                    if (stepsLog != null && !stepsLog.containsKey(nn.element))
                        stepsLog.put(nn.element, step);

                    if (nn.distance < bestDistance) {
                        bestDistance = nn.distance;
                        best = nn.element;
                        if (bestDistance == 0d && similar == null)
                            break mainLoop;
                    }
                    
                    toVisit.add(nn);
                }
            }
        }

        if (stepsLog != null) {
            if (stepsLog.get(best).intValue() >= this.maxSteps) {
                this.maxSteps = stepsLog.get(best).intValue() + maxStepsUpdateBonus;
                System.out.println("maxSteps raised to " + this.maxSteps);
            }
        }

        return similar == null ? new NNSSearchResult(best, bestDistance) : new NNSSearchResultWithSimilar(best, bestDistance, similar);
    }
    
    public void optimizeForRead() {
        for (Element element : elements)
            element.optimizeForRead();
    }

    public void store(ObjectOutputStream out, ValueWriter vw) throws IOException {
        out.writeInt(1); // version
        out.writeInt(distanceCalculator.hashCode());
        out.writeInt(nextId);
        out.writeInt(linearLimit);
        out.writeInt(linearAddLimit);
        out.writeInt(maxSteps);
        out.writeInt(addStepsBonus);
        out.writeInt(maxStepsUpdateBonus);
        out.writeInt(neighbourhoodSize);
        out.writeInt(elements.size());
        for (Element element : elements) {
            out.writeInt(element.id);
            vw.write(out, element.value);
            out.writeDouble(element.worstDistance);
            out.writeInt(element.neighbours.size());
            for (Neighbour neighbour : element.neighbours) {
                out.writeInt(neighbour.element.id);
                out.writeDouble(neighbour.distance);
            }
        }
    }

    public void load(ObjectInputStream in, ValueReader vr) throws IOException {
        if (in.readInt() != 1)
            throw new IOException("Invalid version");
        if (in.readInt() != distanceCalculator.hashCode())
            throw new IOException("Invalid distance calculator");
        nextId = in.readInt();
        linearLimit = in.readInt();
        linearAddLimit = in.readInt();
        maxSteps = in.readInt();
        addStepsBonus = in.readInt();
        maxStepsUpdateBonus = in.readInt();
        neighbourhoodSize = in.readInt();
        int nElem = in.readInt();
        elements.clear();
        while (elements.size() < nElem)
            elements.add(new Element(null));
        for(int id = 0; id < nElem; id++) {
            if (in.readInt() != id)
                throw new IOException("Wrong ID");
            Element element = elements.get(id);
            element.id = id;
            element.value = (X) vr.read(in);
            element.worstDistance = in.readDouble();

//            Neighbour[] neighbours = (Neighbour[]) Array.newInstance(Neighbour.class, in.readInt());
//            for(int n = 0; n < neighbours.length; n++) {
//                int nId = in.readInt();
//                double nDist = in.readDouble();
//                neighbours[n] = new Neighbour(elements.get(nId), nDist);
//            }
//            element.neighbours = Arrays.asList(neighbours);
            final int[] neighbourIDs = new int[in.readInt()];
            final double[] neighbourDists = new double[neighbourIDs.length];
            element.neighbours = new AbstractList<Neighbour>() {

                @Override
                public Neighbour get(int index) {
                    return new Neighbour(elements.get(neighbourIDs[index]), neighbourDists[index]);
                }

                @Override
                public int size() {
                    return neighbourIDs.length;
                }
            };
        }
    }

}
