package com.conveyal.osmlib;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Optional;

/**
 * An interface for classes that read in OSM entities from somewhere and pipe them into an OSMEntitySink.
 * The flow of entities is push-oriented (the source calls write on the sink for every entity it can produce).
 * Entities should always be produced in the order nodes, ways, relations.
 */
public interface OSMEntitySource {


    Optional<String> osmosisReplicationUrl();
    /** Read the OSM entities from this source and pump them through to the sink. */
    void copyTo(OSMEntitySink sink) throws IOException;

    static OSMEntitySource forUrl(String urlString) {
        try {
            URL url = new URL(urlString);
            InputStream inputStream = url.openStream();
            return forStream(urlString, inputStream);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static OSMEntitySource forFile(String path) {
        try {
            InputStream inputStream = new FileInputStream(path);
            return forStream(path, inputStream);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    static OSMEntitySource forStream(String name, InputStream inputStream) {
        if (name.endsWith(".pbf")) {
            return new PBFInput(inputStream);
        } else if (name.endsWith(".vex")) {
            return new VexInput(inputStream);
        } else {
            return null;
        }
    }

}
