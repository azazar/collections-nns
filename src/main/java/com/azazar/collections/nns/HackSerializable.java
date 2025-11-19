package com.azazar.collections.nns;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 *
 * @author Mikhail Yevchenko <spam@azazar.com>
 */
public interface HackSerializable {
    
    public void store(ObjectOutputStream out, ValueWriter vw) throws IOException;
    public void load(ObjectInputStream in, ValueReader vr) throws IOException;

}
