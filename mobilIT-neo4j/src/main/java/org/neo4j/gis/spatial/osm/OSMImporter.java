package org.neo4j.gis.spatial.osm;

/**
 * Copyright (c) 2010-2012 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.geotools.referencing.datum.DefaultEllipsoid;
import org.neo4j.collections.rtree.Listener;
import org.neo4j.collections.rtree.NullListener;
import org.neo4j.gis.spatial.SpatialDatabaseService;
import org.neo4j.gis.spatial.osm.utils.CountedFileReader;
import org.neo4j.gis.spatial.osm.utils.StatsManager;
import org.neo4j.gis.spatial.osm.writer.OSMWriter;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.ReturnableEvaluator;
import org.neo4j.graphdb.StopEvaluator;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Traverser;
import org.neo4j.graphdb.Traverser.Order;
import org.neo4j.kernel.EmbeddedGraphDatabase;

public class OSMImporter {

    public static DefaultEllipsoid WGS84                   = DefaultEllipsoid.WGS84;
    public static String           INDEX_NAME_CHANGESET    = "changeset";
    public static String           INDEX_NAME_USER         = "user";
    public static String           INDEX_NAME_NODE         = "node";
    public static String           INDEX_NAME_WAY          = "way";

    protected boolean              nodesProcessingFinished = false;
    private String                 layerName;
    private StatsManager           stats                   = new StatsManager();
    private long                   osm_dataset             = -1;
    private Listener               monitor;

    private Charset                charset                 = Charset.defaultCharset();

    public OSMImporter(String layerName) {
        this(layerName, null);
    }

    public OSMImporter(String layerName, Listener monitor) {
        this.layerName = layerName;
        if (monitor == null)
            monitor = new NullListener();
        this.monitor = monitor;
    }

    public void reIndex(GraphDatabaseService database) {
        reIndex(database, 10000, true, false);
    }

    public void reIndex(GraphDatabaseService database, int commitInterval) {
        reIndex(database, commitInterval, true, false);
    }

    public void reIndex(GraphDatabaseService database, int commitInterval, boolean includePoints,
            boolean includeRelations) {
        if (commitInterval < 1)
            throw new IllegalArgumentException("commitInterval must be >= 1");
        System.out.println("Re-indexing with GraphDatabaseService: " + database + " (class: " + database.getClass()
                + ")");

        setLogContext("Index");
        SpatialDatabaseService spatialDatabase = new SpatialDatabaseService(database);
        OSMLayer layer = (OSMLayer) spatialDatabase.getOrCreateLayer(layerName, OSMGeometryEncoder.class,
                OSMLayer.class);
        // TODO: The next line creates the relationship between the dataset and
        // layer, but this seems more like a side-effect and should be done
        // explicitly
        OSMDataset dataset = layer.getDataset(osm_dataset);
        layer.clear(); // clear the index without destroying underlying data

        long startTime = System.currentTimeMillis();
        Traverser traverser = database.getNodeById(osm_dataset).traverse(Order.DEPTH_FIRST, StopEvaluator.END_OF_GRAPH,
                ReturnableEvaluator.ALL_BUT_START_NODE, OSMRelation.WAYS, Direction.OUTGOING, OSMRelation.NEXT,
                Direction.OUTGOING);
        Transaction tx = database.beginTx();
        boolean useWays = false;
        int count = 0;
        try {
            layer.setExtraPropertyNames(stats.getTagStats("all").getTags());
            if (useWays) {
                beginProgressMonitor(dataset.getWayCount());
                for (Node way : traverser) {
                    updateProgressMonitor(count);
                    incrLogContext();
                    stats.addGeomStats(layer.addWay(way, true));
                    if (includePoints) {
                        Node first = way.getSingleRelationship(OSMRelation.FIRST_NODE, Direction.OUTGOING).getEndNode();
                        for (Node proxy : first.traverse(Order.DEPTH_FIRST, StopEvaluator.END_OF_GRAPH,
                                ReturnableEvaluator.ALL, OSMRelation.NEXT, Direction.OUTGOING)) {
                            Node node = proxy.getSingleRelationship(OSMRelation.NODE, Direction.OUTGOING).getEndNode();
                            stats.addGeomStats(layer.addWay(node, true));
                        }
                    }
                    if (++count % commitInterval == 0) {
                        tx.success();
                        tx.finish();
                        tx = database.beginTx();
                    }
                } // TODO ask charset to user?
            }
            else {
                beginProgressMonitor(dataset.getChangesetCount());
                for (Node changeset : dataset.getAllChangesetNodes()) {
                    updateProgressMonitor(count);
                    incrLogContext();
                    for (Relationship rel : changeset.getRelationships(OSMRelation.CHANGESET, Direction.INCOMING)) {
                        stats.addGeomStats(layer.addWay(rel.getStartNode(), true));
                    }
                    if (++count % commitInterval == 0) {
                        tx.success();
                        tx.finish();
                        tx = database.beginTx();
                    }
                } // TODO ask charset to user?
            }
            tx.success();
        } finally {
            endProgressMonitor();
            tx.finish();
        }

        long stopTime = System.currentTimeMillis();
        log("info | Re-indexing elapsed time in seconds: " + (1.0 * (stopTime - startTime) / 1000.0));
        stats.dumpGeomStats();
    }

    public void importFile(GraphDatabaseService database, String dataset) throws IOException, XMLStreamException {
        importFile(database, dataset, false, 5000);
    }

    public void importFile(GraphDatabaseService database, String dataset, int txInterval) throws IOException,
            XMLStreamException {
        importFile(database, dataset, false, txInterval);
    }

    public void importFile(GraphDatabaseService database, String dataset, boolean allPoints, int txInterval)
            throws IOException, XMLStreamException {
        importFile(OSMWriter.fromGraphDatabase(database, stats, this, txInterval), dataset, allPoints, charset);
    }

    private int  progress     = 0;
    private long progressTime = 0;

    private void beginProgressMonitor(int length) {
        monitor.begin(length);
        progress = 0;
        progressTime = System.currentTimeMillis();
    }

    private void updateProgressMonitor(int currentProgress) {
        if (currentProgress > this.progress) {
            long time = System.currentTimeMillis();
            if (time - progressTime > 1000) {
                monitor.worked(currentProgress - progress);
                progress = currentProgress;
                progressTime = time;
            }
        }
    }

    private void endProgressMonitor() {
        monitor.done();
        progress = 0;
        progressTime = 0;
    }

    public void setCharset(Charset charset) {
        this.charset = charset;
    }

    public void importFile(OSMWriter<?> osmWriter, String dataset, boolean allPoints, Charset charset)
            throws IOException, XMLStreamException {
        System.out.println("Importing with osm-writer: " + osmWriter);
        osmWriter.getOrCreateOSMDataset(layerName);
        osm_dataset = osmWriter.getDatasetId();

        long startTime = System.currentTimeMillis();
        long[] times = new long[] { 0L, 0L, 0L, 0L };
        javax.xml.stream.XMLInputFactory factory = javax.xml.stream.XMLInputFactory.newInstance();
        CountedFileReader reader = new CountedFileReader(dataset, charset);
        javax.xml.stream.XMLStreamReader parser = factory.createXMLStreamReader(reader);
        int countXMLTags = 0;
        beginProgressMonitor(100);
        setLogContext(dataset);
        boolean startedWays = false;
        boolean startedRelations = false;
        try {
            ArrayList<String> currentXMLTags = new ArrayList<String>();
            int depth = 0;
            Map<String, Object> wayProperties = null;
            ArrayList<Long> wayNodes = new ArrayList<Long>();
            Map<String, Object> relationProperties = null;
            ArrayList<Map<String, Object>> relationMembers = new ArrayList<Map<String, Object>>();
            LinkedHashMap<String, Object> currentNodeTags = new LinkedHashMap<String, Object>();
            while (true) {
                updateProgressMonitor(reader.getPercentRead());
                incrLogContext();
                int event = parser.next();
                if (event == javax.xml.stream.XMLStreamConstants.END_DOCUMENT) {
                    break;
                }
                switch (event) {
                    case javax.xml.stream.XMLStreamConstants.START_ELEMENT:
                        currentXMLTags.add(depth, parser.getLocalName());
                        String tagPath = currentXMLTags.toString();
                        if (tagPath.equals("[osm]")) {
                            osmWriter.setDatasetProperties(extractProperties(parser));
                        }
                        else if (tagPath.equals("[osm, bounds]")) {
                            osmWriter.addOSMBBox(extractProperties("bbox", parser));
                        }
                        else if (tagPath.equals("[osm, node]")) {
                            // <node id="269682538" lat="56.0420950" lon="12.9693483" user="sanna" uid="31450"
                            // visible="true" version="1" changeset="133823" timestamp="2008-06-11T12:36:28Z"/>
                            osmWriter.createOSMNode(extractProperties("node", parser));
                        }
                        else if (tagPath.equals("[osm, way]")) {
                            // <way id="27359054" user="spull" uid="61533" visible="true" version="8"
                            // changeset="4707351" timestamp="2010-05-15T15:39:57Z">
                            if (!startedWays) {
                                startedWays = true;
                                times[0] = System.currentTimeMillis();
                                osmWriter.optimize();
                                times[1] = System.currentTimeMillis();
                            }
                            wayProperties = extractProperties("way", parser);
                            wayNodes.clear();
                        }
                        else if (tagPath.equals("[osm, way, nd]")) {
                            Map<String, Object> properties = extractProperties(parser);
                            wayNodes.add(Long.parseLong(properties.get("ref").toString()));
                        }
                        else if (tagPath.endsWith("tag]")) {
                            Map<String, Object> properties = extractProperties(parser);
                            currentNodeTags.put(properties.get("k").toString(), properties.get("v").toString());
                        }
                        else if (tagPath.equals("[osm, relation]")) {
                            // <relation id="77965" user="Grillo" uid="13957" visible="true" version="24"
                            // changeset="5465617" timestamp="2010-08-11T19:25:46Z">
                            if (!startedRelations) {
                                startedRelations = true;
                                times[2] = System.currentTimeMillis();
                                osmWriter.optimize();
                                times[3] = System.currentTimeMillis();
                            }
                            relationProperties = extractProperties("relation", parser);
                            relationMembers.clear();
                        }
                        else if (tagPath.equals("[osm, relation, member]")) {
                            relationMembers.add(extractProperties(parser));
                        }
                        if (startedRelations) {
                            if (countXMLTags < 10) {
                                log("Starting tag at depth " + depth + ": " + currentXMLTags.get(depth) + " - "
                                        + currentXMLTags.toString());
                                for (int i = 0; i < parser.getAttributeCount(); i++) {
                                    log("\t" + currentXMLTags.toString() + ": " + parser.getAttributeLocalName(i) + "["
                                            + parser.getAttributeNamespace(i) + "," + parser.getAttributePrefix(i)
                                            + "," + parser.getAttributeType(i) + "," + "] = "
                                            + parser.getAttributeValue(i));
                                }
                            }
                            countXMLTags++;
                        }
                        depth++;
                        break;
                    case javax.xml.stream.XMLStreamConstants.END_ELEMENT:
                        if (currentXMLTags.toString().equals("[osm, node]")) {
                            osmWriter.addOSMNodeTags(allPoints, currentNodeTags);
                        }
                        else if (currentXMLTags.toString().equals("[osm, way]")) {
                            osmWriter.createOSMWay(wayProperties, wayNodes, currentNodeTags);
                        }
                        else if (currentXMLTags.toString().equals("[osm, relation]")) {
                            osmWriter.createOSMRelation(relationProperties, relationMembers, currentNodeTags);
                        }
                        depth--;
                        currentXMLTags.remove(depth);
                        // log("Ending tag at depth " + depth + ": " + currentTags.get(depth));
                        break;
                    default:
                        break;
                }
            }
        } finally {
            endProgressMonitor();
            parser.close();
            osmWriter.finish();
            this.osm_dataset = osmWriter.getDatasetId();
        }
        describeTimes(startTime, times);
        osmWriter.describeMissing();
        osmWriter.describeLoaded();

        long stopTime = System.currentTimeMillis();
        log("info | Elapsed time in seconds: " + (1.0 * (stopTime - startTime) / 1000.0));
        stats.dumpGeomStats();
        stats.printTagStats();
    }

    private void describeTimes(long startTime, long[] times) {
        long endTime = System.currentTimeMillis();
        log("Completed load in " + (1.0 * (endTime - startTime) / 1000.0) + "s");
        log("\tImported nodes:  " + (1.0 * (times[0] - startTime) / 1000.0) + "s");
        log("\tOptimized index: " + (1.0 * (times[1] - times[0]) / 1000.0) + "s");
        log("\tImported ways:   " + (1.0 * (times[2] - times[1]) / 1000.0) + "s");
        log("\tOptimized index: " + (1.0 * (times[3] - times[2]) / 1000.0) + "s");
        log("\tImported rels:   " + (1.0 * (endTime - times[3]) / 1000.0) + "s");
    }

    private Map<String, Object> extractProperties(XMLStreamReader parser) {
        return extractProperties(null, parser);
    }

    private Map<String, Object> extractProperties(String name, XMLStreamReader parser) {
        // <node id="269682538" lat="56.0420950" lon="12.9693483" user="sanna" uid="31450" visible="true" version="1"
        // changeset="133823" timestamp="2008-06-11T12:36:28Z"/>
        // <way id="27359054" user="spull" uid="61533" visible="true" version="8" changeset="4707351"
        // timestamp="2010-05-15T15:39:57Z">
        // <relation id="77965" user="Grillo" uid="13957" visible="true" version="24" changeset="5465617"
        // timestamp="2010-08-11T19:25:46Z">
        LinkedHashMap<String, Object> properties = new LinkedHashMap<String, Object>();
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            String prop = parser.getAttributeLocalName(i);
            String value = parser.getAttributeValue(i);
            if (name != null && prop.equals("id")) {
                prop = name + "_osm_id";
                name = null;
            }
            if (prop.equals("lat") || prop.equals("lon")) {
                properties.put(prop, Double.parseDouble(value));
            }
            else if (name != null && prop.equals("version")) {
                properties.put(prop, Integer.parseInt(value));
            }
            else if (prop.equals("visible")) {
                if (!value.equals("true") && !value.equals("1")) {
                    properties.put(prop, false);
                }
            }
            else if (prop.equals("timestamp")) {
                try {
                    Date timestamp = timestampFormat.parse(value);
                    properties.put(prop, timestamp.getTime());
                } catch (ParseException e) {
                    error("Error parsing timestamp", e);
                }
            }
            else {
                properties.put(prop, value);
            }
        }
        if (name != null) {
            properties.put("name", name);
        }
        return properties;
    }

    /**
     * Detects if road has the only direction
     * 
     * @param wayProperties
     * @return RoadDirection
     */
    public static RoadDirection isOneway(Map<String, Object> wayProperties) {
        String oneway = (String) wayProperties.get("oneway");
        if (null != oneway) {
            if ("-1".equals(oneway))
                return RoadDirection.BACKWARD;
            if ("1".equals(oneway) || "yes".equalsIgnoreCase(oneway) || "true".equalsIgnoreCase(oneway))
                return RoadDirection.FORWARD;
        }
        return RoadDirection.BOTH;
    }

    /**
     * Calculate correct distance between 2 points on Earth.
     * 
     * @param latA
     * @param lonA
     * @param latB
     * @param lonB
     * @return distance in meters
     */
    public static double distance(double lonA, double latA, double lonB, double latB) {
        return WGS84.orthodromicDistance(lonA, latA, lonB, latB);
    }

    public void log(PrintStream out, String message, Exception e) {
        if (logContext != null) {
            message = logContext + "[" + contextLine + "]: " + message;
        }
        out.println(message);
        if (e != null) {
            e.printStackTrace(out);
        }
    }

    public void log(String message) {
        log(System.out, message, null);
    }

    public void error(String message) {
        log(System.err, message, null);
    }

    public void error(String message, Exception e) {
        log(System.err, message, e);
    }

    private String     logContext      = null;
    private int        contextLine     = 0;

    // "2008-06-11T12:36:28Z"
    private DateFormat timestampFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private void setLogContext(String context) {
        logContext = context;
        contextLine = 0;
    }

    private void incrLogContext() {
        contextLine++;
    }

    /**
     * This method allows for a console, command-line application for loading one or more *.osm files into a new
     * database.
     * 
     * @param args , the database directory followed by one or more osm files
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: osmimporter databasedir osmfile <..osmfiles..>");
        }
        else {
            OSMImportManager importer = new OSMImportManager(args[0]);
            for (int i = 1; i < args.length; i++) {
                try {
                    importer.loadTestOsmData(args[i], 5000);
                } catch (Exception e) {
                    System.err.println("Error importing OSM file '" + args[i] + "': " + e);
                    e.printStackTrace();
                } finally {
                    importer.shutdown();
                }
            }
        }
    }

    private static class OSMImportManager {

        private GraphDatabaseService graphDb;
        private File                 dbPath;

        public OSMImportManager(String path) {
            setDbPath(path);
        }

        public void setDbPath(String path) {
            dbPath = new File(path);
            if (dbPath.exists()) {
                if (!dbPath.isDirectory()) {
                    throw new RuntimeException("Database path is an existing file: " + dbPath.getAbsolutePath());
                }
            }
            else {
                dbPath.mkdirs();
            }
        }

        private void loadTestOsmData(String layerName, int commitInterval) throws Exception {
            String osmPath = layerName;
            System.out.println("\n=== Loading layer " + layerName + " from " + osmPath + " ===");
            long start = System.currentTimeMillis();
            switchToEmbeddedGraphDatabase();
            OSMImporter importer = new OSMImporter(layerName);
            importer.importFile(graphDb, osmPath, false, commitInterval);
            importer.reIndex(graphDb, commitInterval);
            shutdown();
            System.out.println("=== Completed loading " + layerName + " in " + (System.currentTimeMillis() - start)
                    / 1000.0 + " seconds ===");
        }

        private void switchToEmbeddedGraphDatabase() {
            shutdown();
            graphDb = new EmbeddedGraphDatabase(dbPath.getAbsolutePath());
        }

        protected void shutdown() {
            if (graphDb != null) {
                graphDb.shutdown();
                // batch
                graphDb = null;
            }
        }
    }
}
