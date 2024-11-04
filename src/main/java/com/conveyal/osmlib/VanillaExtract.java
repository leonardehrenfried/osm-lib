package com.conveyal.osmlib;

import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;

/**
 * Load OSM data into MapDB and perform bounding box extracts.
 *
 * Some useful tools:
 * http://boundingbox.klokantech.com
 * http://www.maptiler.org/google-maps-coordinates-tile-bounds-projection/
 */
public class VanillaExtract {

    private static final Logger LOG = LoggerFactory.getLogger(VanillaExtract.class);

    private static final int PORT = 9002;

    private static final String BIND_ADDRESS = "0.0.0.0";

    public static void main(String[] args) {

        OSM osm = new OSM(args[0]);

        if (args.length > 1 && args[1].startsWith("--load")) {
            osm.intersectionDetection = true;
            osm.tileIndexing = true;
            if (args[1].equalsIgnoreCase("--loadurl")) {
                osm.readFromUrl(args[2]);
            } else {
                osm.readFromFile(args[2]);
            }
            // TODO catch writing exceptions here and shut down properly, closing OSM database.
            LOG.info("Done populating OSM database.");
            osm.close();
            return;
        }

        Thread updateThread = Updater.spawnUpdateThread(osm);

        LOG.info("Starting VEX HTTP server on port {} of interface {}", PORT, BIND_ADDRESS);
        HttpServer httpServer = new HttpServer();
        httpServer.addListener(new NetworkListener("vanilla_extract", BIND_ADDRESS, PORT));
        // Bypass Jersey etc. and add a low-level Grizzly handler.
        // As in servlets, * is needed in base path to identify the "rest" of the path.
        httpServer.getServerConfiguration().addHttpHandler(new VexHttpHandler(osm), "/*");
        try {
            httpServer.start();
            LOG.info("VEX server running.");
            Thread.currentThread().join();
            updateThread.interrupt();
        } catch (BindException be) {
            LOG.error("Cannot bind to port {}. Is it already in use?", PORT);
        } catch (IOException ioe) {
            LOG.error("IO exception while starting server.");
        } catch (InterruptedException ie) {
            LOG.info("Interrupted, shutting down.");
        }
        httpServer.shutdown();
    }

    private static class VexHttpHandler extends HttpHandler {

        private static OSM osm;

        public VexHttpHandler(OSM osm) {
            this.osm = osm;
        }

        @Override
        public void service(Request request, Response response) throws Exception {

            response.setContentType("application/osm");
            String uri = request.getDecodedRequestURI();
            LOG.info("VEX request: {}", uri);
            OutputStream outStream = response.getOutputStream();
            try {
                int suffixIndex = uri.lastIndexOf('.');
                String[] coords = uri.substring(1, suffixIndex).split("[,;]");
                if (coords.length < 4) {
                    throw new IllegalArgumentException("Must provide a bounding box with 4 coordinates");
                }
                double minLat = Double.parseDouble(coords[0]);
                double minLon = Double.parseDouble(coords[1]);
                double maxLat = Double.parseDouble(coords[2]);
                double maxLon = Double.parseDouble(coords[3]);
                if (minLat >= maxLat || minLon >= maxLon || minLat < -90 || maxLat > 90 || minLon < -180 || maxLon > 180) {
                    throw new IllegalArgumentException();
                }
                /* Respond to head requests to let the client know the server is alive and the request is valid. */
                if (request.getMethod() == Method.HEAD) {
                    response.setStatus(HttpStatus.OK_200);
                    return;
                }

                OSMEntitySink sink = OSMEntitySink.forStream(uri, outStream);
                TileOSMSource tileSource = new TileOSMSource(osm);
                tileSource.setBoundingBox(minLat, minLon, maxLat, maxLon);
                tileSource.copyTo(sink);
                response.setStatus(HttpStatus.OK_200);
            } catch (IllegalArgumentException ex) {
                LOG.error("Could not process request with bad URI format {}.", uri);
                response.setContentType("text/plain");
                response.setStatus(HttpStatus.BAD_REQUEST_400);
                outStream.write("URI format: /min_lat,min_lon,max_lat,max_lon[.pbf|.vex] (all coords in decimal degrees)\n".getBytes());
            } catch (Exception ex) {
                LOG.error("An internal error occurred while processing {}.", uri);
                response.setContentType("text/plain");
                response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
                outStream.write("An internal error occurred.".getBytes());
                ex.printStackTrace();
            } finally {
                outStream.close();
            }
        }

    }
}
