package com.azazar.collections.nns;

import com.azazar.collections.nns.DistanceBasedSet.SearchResult;
import java.io.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;

/**
 *
 * @author Mikhail Yevchenko <spam@azazar.com>
 */
public class NNSTester {

    private static Random r = new Random(0);

    private static class BooleanArrayWithDistance implements DistanceCalculable<BooleanArrayWithDistance> {
        private final boolean[] bits;

        public BooleanArrayWithDistance(boolean[] bytes) {
            this.bits = bytes;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final BooleanArrayWithDistance other = (BooleanArrayWithDistance) obj;
            if (!Arrays.equals(this.bits, other.bits)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 97 * hash + Arrays.hashCode(this.bits);
            return hash;
        }

        public double calcDistanceTo(BooleanArrayWithDistance other) {
            int d = 0;
            for(int i = 0; i < bits.length; i++)
                if (bits[i] != other.bits[i])
                    d++;
            return d;
        }
        
    }

    private static class TestValueWriterReader implements ValueWriter, ValueReader {

        public void write(ObjectOutputStream out, Object value) throws IOException {
            out.writeObject(((BooleanArrayWithDistance)value).bits);
        }

        public Object read(ObjectInputStream in) throws IOException {
            try {
                return new BooleanArrayWithDistance((boolean[])in.readObject());
            } catch (ClassNotFoundException ex) {
                throw new IOException(ex);
            }
        }
    }

    private static BooleanArrayWithDistance newItem() {
        boolean[] rv = new boolean[256];
        for(int i = 0; i < rv.length; i++)
            rv[i] = r.nextBoolean();
        return new BooleanArrayWithDistance(rv);
    }

    private static class SimpleCounterReporter {
        long start = System.currentTimeMillis();
        long lastEcho = System.currentTimeMillis();
        int counter;
        public void tick() {
            ++counter;
            if (System.currentTimeMillis() > (lastEcho + 1000)) {
                System.out.println(counter);
                lastEcho = System.currentTimeMillis();
            }

        }
        public void clear() {
            counter=0;
            start = System.currentTimeMillis();
        }
        public void report() {
            System.out.println(String.valueOf(System.currentTimeMillis() - start) + " milliseconds taken");
        }
    }

    public static void test(int clustering, int setSize, DistanceBasedSet nns) throws InstantiationException, IllegalAccessException, IOException {
        System.out.println("Generating set");
        BooleanArrayWithDistance[] set = new BooleanArrayWithDistance[setSize];
        for(int i = 0; i < setSize; i++)
            set[i] = newItem();
        // create clusters
        
        if (clustering > 0) {
            System.out.println("Generating clusters");
            for(int i = clustering; i < setSize; i++) {
                BooleanArrayWithDistance c = set[r.nextInt(clustering)];
                for(int j = 0; j < c.bits.length; j++)
                    if (r.nextBoolean())
                        set[i].bits[j] = c.bits[j];
            }
        }
        
        if (nns instanceof AllNearestNeighbourSearchDistanceSet) {
//            ((AllNearestNeighbourSearchDistanceSet)nns).setNeighbourhoodSize(1000);
//            ((AllNearestNeighbourSearchDistanceSet)nns).setSearchMaxSteps(0);
//            ((AllNearestNeighbourSearchDistanceSet)nns).setSearchSetSize(1000);
//            ((AllNearestNeighbourSearchDistanceSet)nns).setMaxResultStepsMultiplier(3f);
            ((AllNearestNeighbourSearchDistanceSet)nns).setNeighbourhoodSize(7);
            ((AllNearestNeighbourSearchDistanceSet)nns).setSearchMaxSteps(-1);
            ((AllNearestNeighbourSearchDistanceSet)nns).setSearchSetSize(1000);
            ((AllNearestNeighbourSearchDistanceSet)nns).setMaxResultStepsMultiplier(3f);
        }
        
        System.out.println("Adding items");
        SimpleCounterReporter r = new SimpleCounterReporter();
        for (BooleanArrayWithDistance item : set) {
            nns.put(item);
            r.tick();
        }
        r.report();

//        if (nns instanceof NewNNS) {
//            System.out.println("Optimizing");
//            r.clear();
//            ((NewNNS)nns).optimizeForRead();
//            r.report();
//        }
        
        System.out.println("Checking items");
        int f = 0;
        r.clear();
        for (BooleanArrayWithDistance item : set) {
            SearchResult<BooleanArrayWithDistance> sr = nns.findNearest(item);
            if (sr != null && sr.value() == item)
                f++;
            r.tick();
        }
        System.out.println("Found: " + f + "/" + setSize);
        r.report();

        if (f < setSize)
            throw new RuntimeException("Bad result");
        
        if (nns instanceof HackSerializable) {
            System.out.println("Checking serializer");

            ByteArrayOutputStream serialized = new ByteArrayOutputStream();
            System.out.println("Serializing...");
            r.clear();
            ObjectOutputStream oo = new ObjectOutputStream(serialized);
            ((HackSerializable)nns).store(oo, new TestValueWriterReader());
            r.report();
            oo.close();
            nns = nns.getClass().newInstance();
            System.out.println("Deserializing...");
            r.clear();
            ((HackSerializable)nns).load(new ObjectInputStream(new ByteArrayInputStream(serialized.toByteArray())), new TestValueWriterReader());
            r.report();

            System.out.println("Checking items");
            f = 0;
            r.clear();
            for (BooleanArrayWithDistance item : set) {
                SearchResult<BooleanArrayWithDistance> sr = nns.findNearest(item);
                if (sr != null && sr.value().equals(item))
                    f++;
                r.tick();
            }
            System.out.println("Found: " + f + "/" + setSize);
            r.report();

            if (f < setSize)
                throw new RuntimeException("Bad result");
        }

    }

    private static class Point implements DistanceCalculable<Point> {
        int x, y;

        public Point(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final Point other = (Point) obj;
            if (this.x != other.x) {
                return false;
            }
            if (this.y != other.y) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int hash = 5;
            hash = 41 * hash + this.x;
            hash = 41 * hash + this.y;
            return hash;
        }

        @Override
        public String toString() {
            return String.valueOf(x) + ":" + y;
        }

        public double calcDistanceTo(Point other) {
//            return Math.sqrt(Math.abs(x - other.x) * Math.abs(y - other.y));
            return Math.abs(x - other.x) + Math.abs(y - other.y);
        }

    }

    private static class DistanceObject implements DistanceCalculable<DistanceObject> {
        Point point;
        int id;

        public DistanceObject(Point point, int id) {
            this.point = point;
            this.id = id;
        }

        @Override
        public String toString() {
            return String.valueOf(id)+"("+point+")";
        }

        public double calcDistanceTo(DistanceObject other) {
            return point.calcDistanceTo(other.point);
        }
    }

    public static void test2(int w, int h, int count) throws Exception {
        Point[] points = new Point[count];
        HashSet<Point> hs = new HashSet<Point>();
        DistanceObject[] distanceObjects = new DistanceObject[count];
        HashMap<Point, DistanceObject> hm = new HashMap<Point, DistanceObject>();
        DistanceBasedSet<DistanceObject> nns = new AllNearestNeighbourSearchDistanceSet<DistanceObject>();
        if (nns instanceof AllNearestNeighbourSearchDistanceSet) {
            ((AllNearestNeighbourSearchDistanceSet)nns).setNeighbourhoodSize(5);
            ((AllNearestNeighbourSearchDistanceSet)nns).setSearchMaxSteps(0);
            ((AllNearestNeighbourSearchDistanceSet)nns).setSearchSetSize(10);
        }
        for(int i = 0; i < count; i++) {
            do {
                points[i] = new Point(r.nextInt(w), r.nextInt(h));
            } while (hs.contains(points[i]));
            hs.add(points[i]);
            distanceObjects[i] = new DistanceObject(points[i], i + 1001);
            nns.put(distanceObjects[i]);
            hm.put(distanceObjects[i].point, distanceObjects[i]);
            System.out.println("\nADDED: " + distanceObjects[i]);
            ((AllNearestNeighbourSearchDistanceSet)nns).dump(System.out);
        }
        PrintStream o = new PrintStream("MAP.txt");
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                DistanceObject dbo = hm.get(new Point(x, y));
                o.print(' ');
                o.print(dbo == null ? "    " : Integer.toString(dbo.id));
            }
            o.println('|');
        }
        ((AllNearestNeighbourSearchDistanceSet)nns).dump(o);
        o.close();
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws Exception {
        // TODO code application logic here
        test(10, 50000, new AllNearestNeighbourSearchDistanceSet());
//        test(10, 100);
//        test(10, 1000, new NewNNS());
//        test(10, 2000);
//        test(10, 5000);
//        test(10, 20000, new NewNNS());
//        test(10, 50000, new NewNNS());
//        test(10, 2000000, new NewNNS());
//        test2(40, 30, 300);
    }
}
