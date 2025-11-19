package com.azazar.collections.nns;

import java.io.IOException;
import java.io.ObjectOutputStream;

/**
 *
 * @author Mikhail Yevchenko <spam@azazar.com>
 */
public interface ValueWriter {

    public void write(ObjectOutputStream out, Object value) throws IOException;
    
}
