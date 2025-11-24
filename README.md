# collections-nns

Lightweight Java collections for approximate nearest-neighbor search. `ANNSet` indexes arbitrary objects through a pluggable `DistanceCalculator`, keeps a bounded neighborhood graph, and returns the closest stored value (plus similar candidates) in sub-linear time for large datasets.

## Notes
- Built for personal use; unless you lack better alternatives, consider other solutions first.
- Results are not perfect and some known bugs remain unfixed (plus others I probably have not discovered yet).
- Code is old and rough around the edges—still fast and good enough for my use case, so expect no cleanup work.

## Highlights
- Works with any data type by supplying a `DistanceCalculator<T>`.
- Tunable search behaviour (`neighbourhoodSize`, `searchSetSize`, `searchMaxSteps`, `adaptiveStepFactor`).
- Simple `DistanceBasedSet` API: `put`, `size`, `findNeighbors`, and a convenience `contains`.
- Pure Java 11 + Maven; no JNI or native dependencies.

## Quick Start
```bash
mvn test   # builds the project and runs the JUnit 5 suite
```

```java
import com.azazar.collections.nns.*;
import java.util.BitSet;

DistanceCalculator<BitSet> hamming = (a, b) -> {
    long distance = Math.abs(a.size() - b.size());
    for (int i = Math.min(a.size(), b.size()); i >= 0; i--) {
        if (a.get(i) != b.get(i)) {
            distance++;
        }
    }
    return distance;
};

ANNSet<BitSet> set = new ANNSet<>(hamming);

BitSet sample = BitSet.valueOf(new long[]{0b1011});
BitSet variation = (BitSet) sample.clone();
variation.flip(0);                       // ensure values differ – duplicates aren't stored

set.add(sample);
set.add(variation);

Neighbors<BitSet> nearest = set.findNeighbors(sample);
System.out.println("Nearest distance: " + nearest.distance());
System.out.println("Nearest value   : " + nearest.value());
```

Run `mvn package` to produce the library JAR under `target/`.

## Use via JitPack
Add both snippets to your `pom.xml` to pull this library from JitPack:
```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<!-- inside <dependencies> -->
<dependency>
    <groupId>com.github.azazar</groupId>
    <artifactId>collections-nns</artifactId>
    <version>master-SNAPSHOT</version>
</dependency>
```
