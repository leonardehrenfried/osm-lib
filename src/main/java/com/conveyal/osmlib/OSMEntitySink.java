package com.conveyal.osmlib;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;

/**
 * Created by abyrd on 2015-05-04
 *
 * TODO intersection finder, indexing sinks (or include those optionally in the OSM storage class itself.
 * TODO tag filter sink
 */
public interface OSMEntitySink {

    void writeBegin() throws IOException;

    void setReplicationTimestamp(long secondsSinceEpoch); // Needs to be called before any entities are written

    default void setReplicationUrl(String url){}

    void writeNode(long id, Node node) throws IOException; // TODO rename id parameters to nodeId, wayId, relationId throughout

    void writeWay(long id, Way way) throws IOException;

    void writeRelation(long id, Relation relation) throws IOException;

    void writeEnd() throws IOException;

    static OSMEntitySink forFile (String path) {
        try {
            OutputStream outputStream = new FileOutputStream(path);
            return forStream(path, outputStream);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    static OSMEntitySink forStream (String name, OutputStream outputStream) {
        if (name.endsWith(".pbf")) {
            return new PBFOutput(outputStream);
        } else if (name.endsWith(".vex")) {
            return new VexOutput(outputStream);
        } else if (name.endsWith(".txt")) {
            return new TextOutput(outputStream);
        } else {
            return null;
        }
    }

}
