package com.conveyal.osmlib;

import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.DatatypeConverter;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Concurrency issues:
 * "MapDB should be thread safe within single JVM. So any number of parallel threads is allowed.
 * It supports parallel writes."
 * <p>
 * However, we may eventually want to apply updates in transactions.
 */
public class Updater {

    private static final Logger LOG = LoggerFactory.getLogger(Updater.class);

    public static final String FALLBACK_BASE_URL = "https://planet.openstreetmap.org/replication/";

    private static final Instant MIN_REPLICATION_INSTANT = Instant.parse("2015-05-01T00:00:00.00Z");

    private static final Instant MAX_REPLICATION_INSTANT = Instant.parse("2100-02-01T00:00:00.00Z");

    OSM osm;

    Diff lastApplied;

    public Updater(OSM osm) {
      this.osm = osm;
    }

    public static class Diff {
        URL url;
        int sequenceNumber;
        long timestamp;

        @Override
        public String toString() {
            return "DiffState " +
                    "sequenceNumber=" + sequenceNumber +
                    ", timestamp=" + timestamp +
                    ", url=" + url;
        }
    }

    private Diff fetchState(int sequenceNumber) {
        Diff diffState = new Diff();
        StringBuilder sb = new StringBuilder(osm.osmosisReplicationUrl().orElse(FALLBACK_BASE_URL));

        try {
            if(!sb.toString().endsWith("/")){
                sb.append("/");
            }
            if (sequenceNumber > 0) {
                int a = sequenceNumber / 1000000;
                int b = (sequenceNumber - (a * 1000000)) / 1000;
                int c = (sequenceNumber - (a * 1000000) - (b * 1000));
                sb.append(String.format(Locale.US, "%03d/%03d/%03d", a, b, c));
                // Record the URL of the changeset itself
                sb.append(".osc.gz");
                diffState.url = new URL(sb.toString());
                // Remove the changeset filename, leaving dot
                sb.delete(sb.length() - 6, sb.length());
            } else {
                LOG.debug("Checking replication state for sequence number {}", sequenceNumber);
            }
            sb.append("state.txt");
            String planetReplicationUrlString = sb.toString();
            URL planetReplicationUrl = new URL(planetReplicationUrlString);
            LOG.info("Requesting data from {}", planetReplicationUrlString);
            BufferedReader reader = new BufferedReader(new InputStreamReader(planetReplicationUrl.openStream()));
            String line;
            Map<String, String> kvs = new HashMap<>();
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) continue;
                String[] fields = line.split("=");
                if (fields.length != 2) continue;
                kvs.put(fields[0], fields[1]);
            }
            LOG.info("Received data from {}", planetReplicationUrlString);
            String timestamp = kvs.get("timestamp");
            if (timestamp == null) {
                LOG.warn("Timestamp field not found in {}", planetReplicationUrl);
                return null;
            }
            String dateTimeString = timestamp.replace("\\:", ":");
            diffState.timestamp = DatatypeConverter.parseDateTime(dateTimeString).getTimeInMillis() / 1000;
            diffState.sequenceNumber = Integer.parseInt(kvs.get("sequenceNumber"));
        } catch (Exception e) {
            LOG.warn("Could not process OSM state: {}", sb);
            e.printStackTrace();
            return null;
        }
        // LOG.info("state {}", diffState);
        return diffState;
    }

    public String getDateString(long secondsSinceEpoch) {
        return Instant.ofEpochSecond(secondsSinceEpoch).toString();
    }

    /**
     * @return a chronologically ordered list of all diffs at the given timescale with a timestamp after
     * the database timestamp.
     */
    public List<Diff> findDiffs () {
        List<Diff> workQueue = new ArrayList<Diff>();
        Diff latest = fetchState(0);
        if (latest == null) {
            LOG.error("Could not find updates from OSM!");
            return List.of();
        }
        // Only check specific updates if the overall state for this timescale implies there are new ones.
        if (latest.timestamp > osm.timestamp.get()) {
            // Working backward, find all updates that are dated after the current database timestamp.
            for (int seq = latest.sequenceNumber; seq > 0; seq--) {
                Diff diff = fetchState(seq);
                if (diff == null || diff.timestamp <= osm.timestamp.get()) break;
                workQueue.add(diff);
            }
        }
        LOG.info("Found {} updates.", workQueue.size());
        // Put the updates in chronological order before returning them
        return Lists.reverse(workQueue);
    }

    private void applyDiffs(List<Diff> workQueue) {

        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            SAXParser saxParser = factory.newSAXParser();
            OSMChangeParser handler = new OSMChangeParser(osm);
            for (Diff state : workQueue) {
                LOG.info("Applying update for {}", getDateString(state.timestamp));
                LOG.info("Requesting data from {}", state.url);
                InputStream inputStream = new GZIPInputStream(state.url.openStream());
                saxParser.parse(inputStream, handler);
                // Move the DB timestamp forward to that of the update that was applied
                osm.timestamp.set(state.timestamp);
                // Record the last update applied so we can jump straight to the next one
                lastApplied = state;
                LOG.info(
                    "Applied update for {}. {} total applied.",
                    getDateString(state.timestamp),
                    handler.nParsed
                );
            }
            LOG.info("Finished applying diffs. {} total applied.", handler.nParsed);
        } catch (Exception e) {
            LOG.error("Error when applying OSM updates", e);
        }
    }

    /** Run the updater, usually in another thread. */
    public void update() {
        Instant initialTimestamp = Instant.ofEpochSecond(osm.timestamp.get());
        if (initialTimestamp.isBefore(MIN_REPLICATION_INSTANT) || initialTimestamp.isAfter(MAX_REPLICATION_INSTANT)) {
            LOG.error("OSM database timestamp seems incorrect: {}", initialTimestamp);
            LOG.error("Not running the minutely updater thread.");
        }
        applyDiffs(findDiffs());
    }

}