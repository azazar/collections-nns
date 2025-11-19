package com.azazar.collections.nns;

import java.io.IOException;
import java.io.ObjectInputStream;

/**
 *
 * @author Mikhail Yevchenko <spam@azazar.com>
 */
public interface ValueReader {

    public Object read(ObjectInputStream in) throws IOException;
    
}
