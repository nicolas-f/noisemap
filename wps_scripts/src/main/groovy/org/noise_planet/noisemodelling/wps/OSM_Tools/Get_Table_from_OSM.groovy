/**
 * @Author Aumond Pierre
 */

package org.noise_planet.noisemodelling.wps.OSM_Tools

import geoserver.GeoServer
import geoserver.catalog.Store
import org.geotools.jdbc.JDBCDataStore
import org.geotools.data.simple.*

import org.locationtech.jts.geom.Geometry

import groovy.sql.Sql

import org.h2gis.api.EmptyProgressVisitor
import org.h2gis.api.ProgressVisitor

import org.noise_planet.noisemodelling.propagation.jdbc.TriangleNoiseMap
import org.noise_planet.noisemodelling.propagation.RootProgressVisitor

import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger

import org.h2gis.functions.io.gpx.*
import org.h2gis.functions.io.osm.*

import org.h2gis.utilities.wrapper.*

import geoserver.GeoServer
import geoserver.catalog.Store
import org.geotools.jdbc.JDBCDataStore
import org.geotools.data.simple.*

import org.locationtech.jts.geom.Geometry
import java.io.*;
import java.sql.*;

import org.h2gis.functions.io.csv.*
import org.h2gis.functions.io.dbf.*
import org.h2gis.functions.io.geojson.*
import org.h2gis.functions.io.json.*
import org.h2gis.functions.io.kml.*
import org.h2gis.functions.io.shp.*
import org.h2gis.functions.io.tsv.*


import groovy.sql.Sql

import org.h2gis.api.EmptyProgressVisitor
import org.h2gis.api.ProgressVisitor

import org.noise_planet.noisemodelling.propagation.jdbc.TriangleNoiseMap
import org.noise_planet.noisemodelling.propagation.RootProgressVisitor

import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger
//import org.orbisgis.orbisprocess.geoclimate.Geoclimate


import groovy.json.JsonSlurper
import groovy.transform.BaseScript
import org.h2gis.functions.spatial.crs.ST_Transform
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry

import org.cts.crs.CRSException;
import org.cts.op.CoordinateOperationException;
import org.h2gis.api.EmptyProgressVisitor;
import org.h2gis.api.ProgressVisitor;
import org.h2gis.functions.io.geojson.GeoJsonRead;
import org.h2gis.functions.io.shp.SHPRead;
import org.h2gis.utilities.SFSUtilities;
import org.locationtech.jts.geom.Coordinate;
import org.noise_planet.noisemodelling.propagation.*;
import org.noise_planet.noisemodelling.propagation.jdbc.PointNoiseMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLStreamException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;


title = 'Import from OSM'
description = 'Convert OSM file to a compatible building file.'

inputs = [pathFile       : [name: 'Path of the input File', description: 'Path of the input File (including extension .osm.gz)', title: 'Path of the input File', type: String.class],
          convert2Building: [name: 'convert2Building', title: 'convert2Building', description: 'convert2Building', type: Boolean.class],
          convert2Vegetation: [name: 'convert2Vegetation', title: 'convert2Vegetation', description: 'convert2Vegetation', type: Boolean.class],
          databaseName   : [name: 'Name of the database', title: 'Name of the database', description: 'Name of the database. (default : h2gisdb)', min: 0, max: 1, type: String.class]]

outputs = [tableNameCreated: [name: 'tableNameCreated', title: 'tableNameCreated', type: String.class]]

def static Connection openPostgreSQLDataStoreConnection(String dbName) {
    Store store = new GeoServer().catalog.getStore(dbName)
    JDBCDataStore jdbcDataStore = (JDBCDataStore) store.getDataStoreInfo().getDataStore(null)
    return jdbcDataStore.getDataSource().getConnection()
}

def run(input) {

    Boolean convert2Building = false
    if ('convert2Building' in input) {
        convert2Building = input['convert2Building'] as Boolean
    }

    Boolean convert2Vegetation = false
    if ('convert2Vegetation' in input) {
        convert2Vegetation = input['convert2Vegetation'] as Boolean
    }


    // Get name of the database
    String dbName = "h2gisdb"
    if (input['databaseName']) {
        dbName = input['databaseName'] as String
    }

    // Open connection
    openPostgreSQLDataStoreConnection(dbName).withCloseable { Connection connection ->

        String pathFile = input["pathFile"] as String

        Statement sql = connection.createStatement()

        sql.execute("DROP TABLE IF EXISTS MAP_NODE")
        sql.execute("DROP TABLE IF EXISTS MAP_NODE_MEMBER")
        sql.execute("DROP TABLE IF EXISTS  MAP_NODE_TAG")
        sql.execute("DROP TABLE IF EXISTS MAP_RELATION")
        sql.execute("DROP TABLE IF EXISTS  MAP_RELATION_MEMBER")
        sql.execute("DROP TABLE IF EXISTS MAP_RELATION_TAG")
        sql.execute("DROP TABLE IF EXISTS MAP_TAG")
        sql.execute("DROP TABLE IF EXISTS MAP_WAY")
        sql.execute("DROP TABLE IF EXISTS MAP_WAY_MEMBER")
        sql.execute("DROP TABLE IF EXISTS  MAP_WAY_NODE")
        sql.execute("DROP TABLE IF EXISTS MAP_WAY_TAG")

        sql.execute("CALL OSMREAD('"+pathFile+"', 'MAP')")

        if (convert2Building){
            String Buildings_Import = "DROP TABLE IF EXISTS MAP_BUILDINGS;\n" +
                    "CREATE TABLE MAP_BUILDINGS(ID_WAY BIGINT PRIMARY KEY) AS SELECT DISTINCT ID_WAY\n" +
                    "FROM MAP_WAY_TAG WT, MAP_TAG T\n" +
                    "WHERE WT.ID_TAG = T.ID_TAG AND T.TAG_KEY IN ('building');\n" +
                    "DROP TABLE IF EXISTS MAP_BUILDINGS_GEOM;\n" +
                    "\n" +
                    "CREATE TABLE MAP_BUILDINGS_GEOM AS SELECT ID_WAY,\n" +
                    "ST_MAKEPOLYGON(ST_MAKELINE(THE_GEOM)) THE_GEOM FROM (SELECT (SELECT\n" +
                    "ST_ACCUM(THE_GEOM) THE_GEOM FROM (SELECT N.ID_NODE, N.THE_GEOM,WN.ID_WAY IDWAY FROM\n" +
                    "MAP_NODE N,MAP_WAY_NODE WN WHERE N.ID_NODE = WN.ID_NODE ORDER BY\n" +
                    "WN.NODE_ORDER) WHERE  IDWAY = W.ID_WAY) THE_GEOM ,W.ID_WAY\n" +
                    "FROM MAP_WAY W,MAP_BUILDINGS B\n" +
                    "WHERE W.ID_WAY = B.ID_WAY) GEOM_TABLE WHERE ST_GEOMETRYN(THE_GEOM,1) =\n" +
                    "ST_GEOMETRYN(THE_GEOM, ST_NUMGEOMETRIES(THE_GEOM)) AND ST_NUMGEOMETRIES(THE_GEOM) >\n" +
                    "2;\n" +
                    "DROP TABLE MAP_BUILDINGS;\n" +
                    "alter table MAP_BUILDINGS_GEOM add column height double;\n" +
                    "update MAP_BUILDINGS_GEOM set height = (select round(\"VALUE\" * 3.0 + RAND() * 2,1) from MAP_WAY_TAG where id_tag = (SELECT ID_TAG FROM MAP_TAG T WHERE T.TAG_KEY = 'building:levels' LIMIT 1) and id_way = MAP_BUILDINGS_GEOM.id_way);\n" +
                    "update MAP_BUILDINGS_GEOM set height = round(4 + RAND() * 2,1) where height is null;\n" +
                    "drop table if exists BUILDINGS_OSM;\n" +
                    "create table BUILDINGS_OSM(id_way serial, the_geom geometry, height double) as select id_way,  ST_SimplifyPreserveTopology(st_buffer(ST_TRANSFORM(ST_SETSRID(THE_GEOM, 4326), 2154), -0.1, 'join=mitre'),0.1) the_geom , height from MAP_BUILDINGS_GEOM;\n" +
                    "drop table if exists MAP_BUILDINGS_GEOM;"
            sql.execute(Buildings_Import)
        }
        if (convert2Vegetation){
            String Vegetation_Import = "DROP TABLE IF EXISTS MAP_SURFACE;\n" +
                    "CREATE TABLE MAP_SURFACE(id serial, ID_WAY BIGINT, surf_cat varchar) AS SELECT null, ID_WAY, \"VALUE\" surf_cat\n" +
                    "FROM MAP_WAY_TAG WT, MAP_TAG T\n" +
                    "WHERE WT.ID_TAG = T.ID_TAG AND T.TAG_KEY IN ('surface', 'landcover', 'natural', 'landuse', 'leisure');\n" +
                    "DROP TABLE IF EXISTS MAP_SURFACE_GEOM;\n" +
                    "CREATE TABLE MAP_SURFACE_GEOM AS SELECT ID_WAY,\n" +
                    "ST_MAKEPOLYGON(ST_MAKELINE(THE_GEOM)) THE_GEOM, surf_cat FROM (SELECT (SELECT\n" +
                    "ST_ACCUM(THE_GEOM) THE_GEOM FROM (SELECT N.ID_NODE, N.THE_GEOM,WN.ID_WAY IDWAY FROM\n" +
                    "MAP_NODE N,MAP_WAY_NODE WN WHERE N.ID_NODE = WN.ID_NODE ORDER BY\n" +
                    "WN.NODE_ORDER) WHERE  IDWAY = W.ID_WAY) THE_GEOM ,W.ID_WAY, B.surf_cat\n" +
                    "FROM MAP_WAY W,MAP_SURFACE B\n" +
                    "WHERE W.ID_WAY = B.ID_WAY) GEOM_TABLE WHERE ST_GEOMETRYN(THE_GEOM,1) =\n" +
                    "ST_GEOMETRYN(THE_GEOM, ST_NUMGEOMETRIES(THE_GEOM)) AND ST_NUMGEOMETRIES(THE_GEOM) >\n" +
                    "2;\n" +
                    "drop table if exists SURFACE_OSM;\n" +
                    "create table SURFACE_OSM(id_way serial, the_geom geometry, surf_cat varchar, G double) as select id_way,  ST_TRANSFORM(ST_SETSRID(THE_GEOM, 4326), 2154) the_geom , surf_cat, 1 g from MAP_SURFACE_GEOM where surf_cat IN ('grass', 'village_green', 'park');\n" +
                    "drop table if exists MAP_SURFACE_GEOM;"
            sql.execute(Vegetation_Import)
        }

        sql.execute("DROP TABLE IF EXISTS MAP_NODE")
        sql.execute("DROP TABLE IF EXISTS MAP_NODE_MEMBER")
        sql.execute("DROP TABLE IF EXISTS  MAP_NODE_TAG")
        sql.execute("DROP TABLE IF EXISTS MAP_RELATION")
        sql.execute("DROP TABLE IF EXISTS  MAP_RELATION_MEMBER")
        sql.execute("DROP TABLE IF EXISTS MAP_RELATION_TAG")
        sql.execute("DROP TABLE IF EXISTS MAP_TAG")
        sql.execute("DROP TABLE IF EXISTS MAP_WAY")
        sql.execute("DROP TABLE IF EXISTS MAP_WAY_MEMBER")
        sql.execute("DROP TABLE IF EXISTS  MAP_WAY_NODE")
        sql.execute("DROP TABLE IF EXISTS MAP_WAY_TAG")

        //sql.execute(String.format("RUNSCRIPT FROM '%s'", Main.class.getResource("import_roads.sql").getFile()));


    }
    System.out.println("Process Done !")
    return [tableNameCreated: "Process done !"]
}




