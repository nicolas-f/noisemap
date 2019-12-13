package org.noise_planet.noisemodelling.wps.NoiseModelling


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.primitives.Doubles;
import geoserver.GeoServer
import geoserver.catalog.Store

/*
 * @Author Pierre Aumond
 */
import groovy.sql.Sql
import groovy.transform.CompileStatic
import org.geotools.jdbc.JDBCDataStore
import org.h2gis.api.EmptyProgressVisitor

import org.h2gis.functions.io.geojson.GeoJsonDriverFunction
import org.h2gis.utilities.wrapper.ConnectionWrapper

import org.locationtech.jts.geom.Geometry
import org.noise_planet.noisemodelling.emission.EvaluateRoadSourceCnossos
import org.noise_planet.noisemodelling.emission.RSParametersCnossos
import org.noise_planet.noisemodelling.propagation.ComputeRays
import org.noise_planet.noisemodelling.propagation.ComputeRaysOut
import org.noise_planet.noisemodelling.propagation.EvaluateAttenuationCnossos
import org.noise_planet.noisemodelling.propagation.PointPath
import org.noise_planet.noisemodelling.propagation.PropagationPath
import org.noise_planet.noisemodelling.propagation.PropagationProcessPathData
import org.noise_planet.noisemodelling.propagation.SegmentPath

import java.sql.Connection
import java.sql.SQLException
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

title = 'Compute MultiRuns'
description = 'Compute MultiRuns. A ver...'

inputs = [databaseName      : [name: 'Name of the database', title: 'Name of the database', description: 'Name of the database. (default : h2gisdb)', min: 0, max: 1, type: String.class],
          workingDir : [name: 'workingDir', title: 'workingDir', description: 'workingDir (ex : C:/Desktop/), shall contain Rays.zip and MR_Input.json', type: String.class],
          nbSimu  : [name: 'nSimu', title: 'nSimu',min: 0, max: 1, type: Integer.class],
          threadNumber      : [name: 'Thread number', title: 'Thread number', description: 'Number of thread to use on the computer (default = 1)', min: 0, max: 1, type: String.class]]

outputs = [result: [name: 'result', title: 'Result', type: String.class]]


def static Connection openPostgreSQLDataStoreConnection(String dbName) {
    Store store = new GeoServer().catalog.getStore(dbName)
    JDBCDataStore jdbcDataStore = (JDBCDataStore) store.getDataStoreInfo().getDataStore(null)
    return jdbcDataStore.getDataSource().getConnection()
}

def run(input) {

    MultiRunsProcessData multiRunsProcessData = new MultiRunsProcessData()
    Properties prop = new Properties()
    // -------------------
    // Get inputs
    // -------------------
    String workingDir = "D:\\aumond\\Documents\\Boulot\\Articles\\2019_XX_XX Sensitivity"
    if (input['workingDir']) {
        workingDir = input['workingDir']
    }

    int n_Simu = -1
    if (input['nbSimu']) {
        n_Simu = Integer.valueOf(input['nbSimu'])
    }

    int n_thread = 1
    if (input['threadNumber']) {
        n_thread = Integer.valueOf(input['threadNumber'])
    }

    // Get name of the database
    String dbName = "h2gisdb"
    if (input['databaseName']) {
        dbName = input['databaseName'] as String
    }


    // ----------------------------------
    // Start... 
    // ----------------------------------

    System.out.println("Run ...")

    List<ComputeRaysOut.verticeSL> allLevels = new ArrayList<>()

    // Open connection
    openPostgreSQLDataStoreConnection(dbName).withCloseable { Connection connection ->

        //Need to change the ConnectionWrapper to WpsConnectionWrapper to work under postgis database
        connection = new ConnectionWrapper(connection)

        System.out.println("Run ...")
        // Init output logger

        System.out.println(String.format("Working directory is %s", new File(workingDir).getAbsolutePath()))

        // Create spatial database

        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())

        Sql sql = new Sql(connection)





        // Evaluate receiver points using provided buildings
        String fileZip = workingDir +"Rays.zip"
        FileInputStream fileInputStream = new FileInputStream(new File(fileZip).getAbsolutePath())
        ZipInputStream zipInputStream = new ZipInputStream(fileInputStream)
        ZipEntry entry = zipInputStream.getNextEntry()
        // iterates over entries in the zip file
        while (entry != null) {
            String filePath = workingDir + File.separator + entry.getName()
            GeoJsonDriverFunction geoJsonDriver = new GeoJsonDriverFunction()
            File file = new File(filePath)
            switch (entry.getName()) {

                case 'buildings.geojson':
                    sql.execute("DROP TABLE BUILDINGS_MR if exists;")
                    extractFile(zipInputStream, filePath)
                    geoJsonDriver.importFile(connection, 'BUILDINGS_MR', file, new EmptyProgressVisitor())
                    System.println("import Buildings")
                    break

                case 'sources.geojson':
                    sql.execute("DROP TABLE SOURCES_MR if exists;")
                    extractFile(zipInputStream, filePath)
                    geoJsonDriver.importFile(connection, 'SOURCES_MR', file, new EmptyProgressVisitor())
                    System.println("import Sources")
                    break

                case 'receivers.geojson':
                    sql.execute("DROP TABLE RECEIVERS_MR if exists;")
                    extractFile(zipInputStream, filePath)
                    geoJsonDriver.importFile(connection, 'RECEIVERS_MR', file, new EmptyProgressVisitor())
                    System.println("import RECEIVERS")
                    break

                case 'ground.geojson':
                    sql.execute("DROP TABLE GROUND_TYPE_MR if exists;")
                    extractFile(zipInputStream, filePath)
                    geoJsonDriver.importFile(connection, 'GROUND_TYPE_MR', file, new EmptyProgressVisitor())
                    System.println("import GROUND_TYPE")
                    break

                case 'dem.geojson':
                    sql.execute("DROP TABLE DEM_MR if exists;")
                    extractFile(zipInputStream, filePath)
                    geoJsonDriver.importFile(connection, 'DEM_MR', file, new EmptyProgressVisitor())
                    System.println("import DEM")
                    break


                case 'NM.properties':
                    extractFile(zipInputStream, filePath)
                    prop.load(new FileInputStream(filePath))
                    break
            }
            file.delete()
            zipInputStream.closeEntry()
            entry = zipInputStream.getNextEntry()
        }

        fileInputStream.close()
        zipInputStream.close()

        /* System.out.println("Read Sources")
         sql.execute("DROP TABLE IF EXISTS RECEIVERS")
         SHPRead.readShape(connection, "D:\\aumond\\Documents\\CENSE\\LorientMapNoise\\data\\RecepteursQuest3D.shp", "RECEIVERS")

         HashMap<Integer, Double> pop = new HashMap<>()
         // memes valeurs d e et n
         sql.eachRow('SELECT id, pop FROM RECEIVERS;') { row ->
             int id = (int) row[0]
             pop.put(id, (Double) row[1])
         }

         // Load roads
         System.out.println("Read road geometries and traffic")
         // ICA 2019 - Sensitivity
         sql.execute("DROP TABLE ROADS2 if exists;")
         SHPRead.readShape(connection, "D:\\aumond\\Documents\\CENSE\\LorientMapNoise\\data\\RoadsICA2.shp", "ROADS2")
         sql.execute("DROP TABLE ROADS if exists;")
         sql.execute('CREATE TABLE ROADS AS SELECT CAST( OSM_ID AS INTEGER ) OSM_ID , THE_GEOM, TMJA_D,TMJA_E,TMJA_N,\n' +
                 'PL_D,PL_E,PL_N,\n' +
                 'LV_SPEE,PV_SPEE, PVMT FROM ROADS2;')

         sql.execute('ALTER TABLE ROADS ALTER COLUMN OSM_ID SET NOT NULL;')
         sql.execute('ALTER TABLE ROADS ADD PRIMARY KEY (OSM_ID);')
         sql.execute("CREATE SPATIAL INDEX ON ROADS(THE_GEOM)")

         System.out.println("Road file loaded")

         // ICA 2019 - Sensitivity
         sql.execute("DROP TABLE ROADS3 if exists;")
         SHPRead.readShape(connection, "D:\\aumond\\Documents\\CENSE\\LorientMapNoise\\data\\Roads09083D.shp", "ROADS3")

         System.out.println("Road file loaded")*/


        PropagationProcessPathData genericMeteoData = new PropagationProcessPathData()
        int nSimu = multiRunsProcessData.setSensitivityTable(new File(workingDir + "MR_input.json"),prop,n_Simu)




        multiRunsProcessData.setRoadTable("SOURCES_MR",sql, prop)

        int GZIP_CACHE_SIZE = (int) Math.pow(2, 19)

        System.out.println("Start time :" + df.format(new Date()))

        HashMap<Integer,Double> pop = new HashMap<>()
        // memes valeurs d e et n
        sql.eachRow('SELECT pk, pop FROM RECEIVERS_MR;') { row ->
            int id = (int) row[0]
            pop.put(id, (Double) row[1])
        }

        //FileInputStream fileInputStream = new FileInputStream(new File("D:\\aumond\\Documents\\CENSE\\LorientMapNoise\\rays0908.gz").getAbsolutePath())


        //DataInputStream dataInputStream = new DataInputStream(gzipInputStream)
        //System.out.println(dataInputStream)

        int oldIdReceiver = -1
        int oldIdSource = -1

        List<double[]> simuSpectrum = new ArrayList<>()
        sql.execute("drop table if exists MultiRunsResults;")
        sql.execute("create table MultiRunsResults (idRun integer,idReceiver integer, pop double precision, " +
                "Lden63 double precision, Lden125 double precision, Lden250 double precision, Lden500 double precision, Lden1000 double precision, Lden2000 double precision, Lden4000 double precision, Lden8000 double precision);")

        def qry = 'INSERT INTO MultiRunsResults(idRun,  idReceiver, pop,' +
                'Lden63, Lden125, Lden250, Lden500, Lden1000,Lden2000, Lden4000, Lden8000) ' +
                'VALUES (?,?,?,?,?,?,?,?,?,?,?);'



        Map<Integer, List<double[]>> sourceLevel = new HashMap<>()

        System.out.println("Prepare Sources")
        def timeStart = System.currentTimeMillis()

        multiRunsProcessData.getTrafficLevel(nSimu)

        def timeStart2 = System.currentTimeMillis()
        System.out.println(timeStart2 - timeStart)
        System.out.println("End Emission time :" + df.format(new Date()))
        System.out.println("Run SA")
        long computationTime = 0
        long startSimulationTime = System.currentTimeMillis()

        FileInputStream fileInputStream2 = new FileInputStream(new File(fileZip).getAbsolutePath())
        ZipInputStream zipInputStream2 = new ZipInputStream(fileInputStream2)
        ZipEntry entry2 = zipInputStream2.getNextEntry()

        AtomicBoolean doInsertResults = new AtomicBoolean(true);
        ResultsInsertThread resultsInsertThread = new ResultsInsertThread(doInsertResults, sql)
        new Thread(resultsInsertThread).start()
        ExecutorService executorService = Executors.newFixedThreadPool(12);
        while (entry2 != null) {
            switch (entry2.getName()) {
                case 'rays.gz':
                    System.println(entry2.getName())
                    System.println(entry2)
                    String filePath = workingDir + entry2.getName()
//                    extractFile(zipInputStream2, filePath)
//
//                    System.out.println(filePath)
//                    FileInputStream fileInput = new FileInputStream(new File(filePath))
                    GZIPInputStream gzipInputStream = new GZIPInputStream(zipInputStream2, GZIP_CACHE_SIZE)
                    DataInputStream dataInputStream = new DataInputStream(gzipInputStream)

                    List<PointToPointPathsMultiRuns> pointToPointPathsMultiRuns = new ArrayList<>();
                    while (zipInputStream2.available() > 0) {

                        PointToPointPathsMultiRuns paths = new PointToPointPathsMultiRuns()
                        paths.readPropagationPathListStream(dataInputStream)
                        int idReceiver = (Integer) paths.receiverId
                        int idSource = (Integer) paths.sourceId

                        if (idReceiver != oldIdReceiver) {
                            // todo Add thread
                            ReceiverSimulationProcess receiverSimulationProcess =
                                    new ReceiverSimulationProcess(resultsInsertThread.getConcurrentLinkedQueue(),
                                            multiRunsProcessData, pointToPointPathsMultiRuns)
                            executorService.execute(receiverSimulationProcess)
                            pointToPointPathsMultiRuns = new ArrayList<>(pointToPointPathsMultiRuns.size());
                        }

                        oldIdReceiver = idReceiver

                        pointToPointPathsMultiRuns.add(paths);
                    }
                    fileInput.close()

            }
            System.out.println("3 ComputationTime :" + computationTime.toString())
            zipInputStream2.closeEntry()
            entry2 = zipInputStream2.getNextEntry()

        }

        executorService.awaitTermination(30, TimeUnit.DAYS)

        doInsertResults.set(false)

        System.out.println("4 ComputationTime :" + computationTime.toString())
        System.out.println("SimulationTime :" + (System.currentTimeMillis() - startSimulationTime).toString())

        // csvFile.close()
        System.out.println("End time :" + df.format(new Date()))


        fileInputStream2.close()

        sql.execute("drop table if exists MultiRunsResults_geom;")
        sql.execute("create table MultiRunsResults_geom  as select a.idRun, a.idReceiver, b.pop, b.THE_GEOM, a.Lden63, a.Lden125, a.Lden250, a.Lden500, a.Lden1000, a.Lden2000, a.Lden4000, a.Lden8000 FROM RECEIVERS_MR b LEFT JOIN MultiRunsResults a ON a.IDRECEIVER = b."+prop.getProperty("pkReceivers")+";")
        sql.execute("drop table if exists MultiRunsResults;")

        // long computationTime = System.currentTimeMillis() - start;
        return [result: "Calculation Done !"]
    }

}

class SimulationResult {
    final int r;
    final int receiver;
    final double pop;
    final double[] spectrum;

    SimulationResult(int r, int receiver, double pop, double[] spectrum) {
        this.r = r
        this.receiver = receiver
        this.pop = pop
        this.spectrum = spectrum
    }
}

class ResultsInsertThread implements Runnable {
    ConcurrentLinkedQueue<SimulationResult> concurrentLinkedQueue = new ConcurrentLinkedQueue<>();
    AtomicBoolean doInsertResults;
    Sql sql;

    ResultsInsertThread(AtomicBoolean doInsertResults, Sql sql) {
        this.doInsertResults = doInsertResults
        this.sql = sql
    }

    ConcurrentLinkedQueue<SimulationResult> getConcurrentLinkedQueue() {
        return concurrentLinkedQueue
    }

    @Override
    void run() {
        def qry = 'INSERT INTO MultiRunsResults(idRun,  idReceiver, pop,' +
                'Lden63, Lden125, Lden250, Lden500, Lden1000,Lden2000, Lden4000, Lden8000) ' +
                'VALUES (?,?,?,?,?,?,?,?,?,?,?);'
        sql.withBatch(100, qry) { ps ->
            while (doInsertResults.get()) {
                SimulationResult result = concurrentLinkedQueue.poll()
                if (result != null) {
                    ps.addBatch(result.r as Integer, result.receiver as Integer, result.pop as Double,
                            result.spectrum[0] as Double, result.spectrum[1] as Double, result.spectrum[2] as Double,
                            result.spectrum[3] as Double, result.spectrum[4] as Double, result.spectrum[5] as Double,
                            result.spectrum[6] as Double, result.spectrum[7] as Double)
                } else {
                    Thread.sleep(5)
                }
            }
        }
    }
}

class ReceiverSimulationProcess implements Runnable {
    ConcurrentLinkedQueue<SimulationResult> concurrentLinkedQueue;
    MultiRunsProcessData multiRunsProcessData;
    List<PointToPointPathsMultiRuns> pointToPointPathsMultiRuns;

    ReceiverSimulationProcess(ConcurrentLinkedQueue<SimulationResult> concurrentLinkedQueue, MultiRunsProcessData multiRunsProcessData, List<PointToPointPathsMultiRuns> pointToPointPathsMultiRuns) {
        this.concurrentLinkedQueue = concurrentLinkedQueue
        this.multiRunsProcessData = multiRunsProcessData
        this.pointToPointPathsMultiRuns = pointToPointPathsMultiRuns
    }

    @Override
    void run() {
        List<double[]> simuSpectrum = new ArrayList<>()
        for (int r = 0; r < multiRunsProcessData.Simu.size(); ++r) {
            simuSpectrum.add(new double[PropagationProcessPathData.freq_lvl.size()])
        }
        for (PointToPointPathsMultiRuns paths : pointToPointPathsMultiRuns) {
            int idReceiver = (Integer) paths.receiverId
            int idSource = (Integer) paths.sourceId
            MRComputeRaysOut outD = new MRComputeRaysOut(false, multiRunsProcessData.createGenericMeteoData(0, 'day'))
            MRComputeRaysOut outE = new MRComputeRaysOut(false, multiRunsProcessData.createGenericMeteoData(0, 'evening'))
            MRComputeRaysOut outN = new MRComputeRaysOut(false, multiRunsProcessData.createGenericMeteoData(0, 'night'))
            double[] attenuationD = null
            double[] attenuationE = null
            double[] attenuationN = null

            List<MRPropagationPath> propagationPaths = new ArrayList<>()

            for (int r = 0; r < multiRunsProcessData.Simu.size(); r++) {
                propagationPaths.clear()

                for (int pP = 0; pP < paths.propagationPathList.size(); pP++) {

                    paths.propagationPathList.get(pP).initPropagationPath()
                    paths.propagationPathList.get(pP).setAlphaModif(multiRunsProcessData.wallAlpha[r])
                    if (paths.propagationPathList.get(pP).refPoints.size() <= multiRunsProcessData.Refl[r] && paths.propagationPathList.get(pP).difVPoints.size() <= multiRunsProcessData.Dif_hor[r] && paths.propagationPathList.get(pP).difHPoints.size() <= multiRunsProcessData.Dif_ver[r] && paths.propagationPathList.get(pP).SRList[0].dp <= multiRunsProcessData.DistProp[r]) {
                        propagationPaths.add(paths.propagationPathList.get(pP))
                    }
                }


                if (propagationPaths.size() > 0) {

                    if (attenuationD != null) {
                        if (multiRunsProcessData.meteoFav[r] != multiRunsProcessData.meteoFav[r - 1] || multiRunsProcessData.WindDir[r] != multiRunsProcessData.WindDir[r - 1] || multiRunsProcessData.TempMean[r] != multiRunsProcessData.TempMean[r - 1] || multiRunsProcessData.HumMean[r] != multiRunsProcessData.HumMean[r - 1]) {
                            multiRunsProcessData
                            attenuationD = outD.MRcomputeAttenuation(multiRunsProcessData.createGenericMeteoData(r, 'day'), idSource, paths.getLi(), idReceiver, propagationPaths)
                            attenuationE = outE.MRcomputeAttenuation(multiRunsProcessData.createGenericMeteoData(r, 'evening'), idSource, paths.getLi(), idReceiver, propagationPaths)
                            attenuationN = outN.MRcomputeAttenuation(multiRunsProcessData.createGenericMeteoData(r, 'night'), idSource, paths.getLi(), idReceiver, propagationPaths)

                        }
                    } else {
                        attenuationD = outD.MRcomputeAttenuation(multiRunsProcessData.createGenericMeteoData(r, 'day'), idSource, paths.getLi(), idReceiver, propagationPaths)
                        attenuationE = outE.MRcomputeAttenuation(multiRunsProcessData.createGenericMeteoData(r, 'evening'), idSource, paths.getLi(), idReceiver, propagationPaths)
                        attenuationN = outN.MRcomputeAttenuation(multiRunsProcessData.createGenericMeteoData(r, 'night'), idSource, paths.getLi(), idReceiver, propagationPaths)
                    }

                    double[] wjSourcesD = multiRunsProcessData.getWjSourcesD(idSource, r)
                    double[] wjSourcesE = multiRunsProcessData.getWjSourcesE(idSource, r)
                    double[] wjSourcesN = multiRunsProcessData.getWjSourcesN(idSource, r)
                    double[] soundLevelDay = ComputeRays.wToDba(ComputeRays.multArray(wjSourcesD, ComputeRays.dbaToW(attenuationD)))
                    double[] soundLevelEve = ComputeRays.wToDba(ComputeRays.multArray(wjSourcesE, ComputeRays.dbaToW(attenuationE)))
                    double[] soundLevelNig = ComputeRays.wToDba(ComputeRays.multArray(wjSourcesN, ComputeRays.dbaToW(attenuationN)))
                    double[] lDen = new double[soundLevelDay.length]
                    double[] lN = new double[soundLevelDay.length]
                    for (int i = 0; i < soundLevelDay.length; ++i) {
                        lDen[i] = 10.0D * Math.log10((12.0D / 24.0D) * Math.pow(10.0D, soundLevelDay[i] / 10.0D) + (4.0D / 24.0D) * Math.pow(10.0D, (soundLevelEve[i] + 5.0D) / 10.0D) + (8.0D / 24.0D) * Math.pow(10.0D, (soundLevelNig[i] + 10.0D) / 10.0D))
                        lN[i] = soundLevelNig[i]
                    }

                    double[] spectrum = ComputeRays.sumDbArray(simuSpectrum[r], lDen)
                    SimulationResult simulationResult = new SimulationResult(r, idReceiver, pop, spectrum)
                    concurrentLinkedQueue.add(simulationResult)
                }
            }
        }
    }
}


static double[] DBToDBA(double[] db) {
    double[] dbA = [-26.2, -16.1, -8.6, -3.2, 0, 1.2, 1.0, -1.1]
    for (int i = 0; i < db.length; ++i) {
        db[i] = db[i] + dbA[i]
    }
    return db

}

static double[] sumArraySR(double[] array1, double[] array2) {
    if (array1.length != array2.length) {
        throw new IllegalArgumentException("Not same size array");
    } else {
        double[] sum = new double[array1.length];

        for (int i = 0; i < array1.length; ++i) {
            sum[i] = (array1[i]) + (array2[i]);
        }

        return sum;
    }
}


static double[] sumArray(double[] array1, double[] array2) {
    if (array1.length != array2.length) {
        throw new IllegalArgumentException("Not same size array")
    } else {
        double[] sum = new double[array1.length]

        for (int i = 0; i < array1.length; ++i) {
            sum[i] = array1[i] + array2[i]
        }

        return sum
    }
}

@CompileStatic
class MRPropagationPath extends PropagationPath {

    double alphaModif

    double getAlphaModif() {
        return alphaModif
    }

    void setAlphaModif(double alphaModif) {
        this.alphaModif = alphaModif
    }
}

@CompileStatic
class MRComputeRaysOut extends ComputeRaysOut {

    MRComputeRaysOut(boolean keepRays, PropagationProcessPathData pathData) {
        super(keepRays, pathData)
    }


    static double[] MRcomputeAttenuation(PropagationProcessPathData pathData, long sourceId, double sourceLi, long receiverId, List<MRPropagationPath> propagationPath) {
        if(pathData != null) {
            // Compute receiver/source attenuation
            MREvaluateAttenuationCnossos evaluateAttenuationCnossos = new MREvaluateAttenuationCnossos();
            double[] aGlobalMeteo = null;
            for (MRPropagationPath propath : propagationPath) {
                List<PointPath> ptList = propath.getPointList();
                int roseindex = getRoseIndex(ptList.get(0).coordinate, ptList.get(ptList.size() - 1).coordinate);

                // Compute homogeneous conditions attenuation
                propath.setFavorable(false);
                evaluateAttenuationCnossos.evaluate(propath, pathData);
                double[] aGlobalMeteoHom = evaluateAttenuationCnossos.getaGlobal();

                // Compute favorable conditions attenuation
                propath.setFavorable(true);
                evaluateAttenuationCnossos.evaluate(propath, pathData);
                double[] aGlobalMeteoFav = evaluateAttenuationCnossos.getaGlobal();

                // Compute attenuation under the wind conditions using the ray direction
                double[] aGlobalMeteoRay = ComputeRays.sumArrayWithPonderation(aGlobalMeteoFav, aGlobalMeteoHom, pathData.getWindRose()[roseindex]);

                if (aGlobalMeteo != null) {
                    aGlobalMeteo = ComputeRays.sumDbArray(aGlobalMeteoRay, aGlobalMeteo);
                } else {
                    aGlobalMeteo = aGlobalMeteoRay;
                }
            }
            if (aGlobalMeteo != null) {
                // For line source, take account of li coefficient
                if(sourceLi > 1.0) {
                    for (int i = 0; i < aGlobalMeteo.length; i++) {
                        aGlobalMeteo[i] = ComputeRays.wToDba(ComputeRays.dbaToW(aGlobalMeteo[i]) * sourceLi);
                    }
                }
                return aGlobalMeteo;
            } else {
                return new double[0];
            }
        } else {
            return new double[0];
        }
    }
}

@CompileStatic
class MREvaluateAttenuationCnossos extends EvaluateAttenuationCnossos {
    //private int nbfreq;
    private double[] freq_lambda
    private double[] aGlobal
    private final static double ONETHIRD = 1.0/3.0

    double[] getaGlobal() {
        return aGlobal
    }

    @Override
    double[] getDeltaDif(SegmentPath srpath, PropagationProcessPathData data) {
        double[] DeltaDif = new double[data.freq_lvl.size()];
        double cprime;

        for (int idfreq = 0; idfreq < data.freq_lvl.size(); idfreq++) {

            double Ch = 1;// Math.min(h0 * (data.celerity / freq_lambda[idfreq]) / 250, 1);

            if (srpath.eLength > 0.3) {
                double gammaPart = Math.pow((5 * freq_lambda[idfreq]) / srpath.eLength, 2);
                cprime = (1.0 + gammaPart) / (ONETHIRD + gammaPart);
            } else {
                cprime = 1.0;
            }

            //(7.11) NMP2008 P.32
            double testForm = (40 / freq_lambda[idfreq]) * cprime * srpath.delta;

            double deltaDif= 0.0

            if (testForm >= -2.0) {
                deltaDif = 10 * Ch * Math
                        .log10(Math.max(0, 3 + testForm));
            }

            DeltaDif[idfreq] = Math.max(0,deltaDif);

        }
        return  DeltaDif;

    }

    static double[] getMRARef(MRPropagationPath path) {
        double[] aRef = new double[PropagationProcessPathData.freq_lvl.size()]

        for (int idf = 0; idf < PropagationProcessPathData.freq_lvl.size(); ++idf) {
            for (int idRef = 0; idRef < path.refPoints.size(); ++idRef) {
                //List<Double> alpha = ((PointPath) path.getPointList().get((Integer) path.refPoints.get(idRef))).alphaWall
                List<Double> alpha = ((PointPath) path.getPointList().get((Integer) path.refPoints.get(idRef))).alphaWall.collect{it*path.getAlphaModif()}
                aRef[idf] += -10.0D * Math.log10(1.0D - (Double) alpha.get(idf))
            }
        }

        return aRef
    }


    double[] evaluate(MRPropagationPath path, PropagationProcessPathData data) {
        aGlobal = new double[PropagationProcessPathData.freq_lvl.size()]
        freq_lambda = new double[PropagationProcessPathData.freq_lvl.size()]

        for(int idf = 0; idf < PropagationProcessPathData.freq_lvl.size(); ++idf) {
            if (PropagationProcessPathData.freq_lvl.get(idf) > 0) {
                freq_lambda[idf] = data.getCelerity() / (double)(Integer)PropagationProcessPathData.freq_lvl.get(idf)
            } else {
                freq_lambda[idf] = 1.0D
            }
        }
        //setFreq_lambda(freq_lambda)

        path.initPropagationPath();
        double[] alpha_atmo = data.getAlpha_atmo();
        double aDiv
        if (path.refPoints.size() > 0) {
            aDiv = getADiv(((SegmentPath)path.getSRList().get(0)).dPath);
        } else {
            aDiv = getADiv(((SegmentPath)path.getSRList().get(0)).d);
        }

        double[] aBoundary = getABoundary(path, data)
        double[] aRef = getMRARef(path)

        for(int idfreq = 0; idfreq < PropagationProcessPathData.freq_lvl.size(); ++idfreq) {
            double aAtm;
            if (path.difVPoints.size() <= 0 && path.refPoints.size() <= 0) {
                aAtm = getAAtm(((SegmentPath)path.getSRList().get(0)).d, alpha_atmo[idfreq]);
            } else {
                aAtm = getAAtm(((SegmentPath)path.getSRList().get(0)).dPath, alpha_atmo[idfreq]);
            }

            aGlobal[idfreq] = -(aDiv + aAtm + aBoundary[idfreq] + aRef[idfreq]);
        }

        return aGlobal
    }




}

// fonction pour Copier les fichiers dans un autre répertoire
@CompileStatic
private static void copyFileUsingStream(File source, File dest) throws IOException {
    InputStream is = null
    OutputStream os = null
    try {
        is = new FileInputStream(source)
        os = new FileOutputStream(dest)
        byte[] buffer = new byte[1024]
        int length
        while ((length = is.read(buffer)) > 0) {
            os.write(buffer, 0, length)
        }
    } finally {
        is.close()
        os.close()
    }
}

/**
 * Read source database and compute the sound emission spectrum of roads sources
 */
class MultiRunsProcessData {
    public Map<Integer, List<double[]>> wjSourcesD = new HashMap<>()
    public Map<Integer, List<double[]>> wjSourcesE = new HashMap<>()
    public Map<Integer, List<double[]>> wjSourcesN = new HashMap<>()

    // Init des variables
    ArrayList<Integer> Simu = new ArrayList<Integer>() // numero simu
    ArrayList<Double> CalcTime = new ArrayList<Double>() // temps calcul

    ArrayList<Integer> Refl = new ArrayList<Integer>()
    ArrayList<Integer> Dif_hor = new ArrayList<Integer>()
    ArrayList<Integer> Dif_ver = new ArrayList<Integer>()
    ArrayList<Double> DistProp = new ArrayList<Double>()
    ArrayList<Integer> Veg = new ArrayList<Integer>()
    ArrayList<Integer> TMJA = new ArrayList<Integer>()
    ArrayList<Double> HV = new ArrayList<Double>()
    ArrayList<Double> MV = new ArrayList<Double>()
    ArrayList<Double> LV = new ArrayList<Double>()
    ArrayList<Double> WV = new ArrayList<Double>()
    ArrayList<Integer> Speed = new ArrayList<Integer>()
    ArrayList<Integer> RoadType = new ArrayList<Integer>()
    ArrayList<Double> TempMean = new ArrayList<Double>()
    ArrayList<Double> HumMean = new ArrayList<Double>()
    ArrayList<Integer> Meteo = new ArrayList<Integer>()
    ArrayList<Double> SpeedMean = new ArrayList<Double>()
    ArrayList<Double> FlowMean = new ArrayList<Double>()
    ArrayList<Double> FlowMean_MajAxes = new ArrayList<Double>()
    ArrayList<Double> FlowMean_MedAxes = new ArrayList<Double>()
    ArrayList<Double> FlowMean_SmaAxes = new ArrayList<Double>()
    ArrayList<Double> wallAlpha = new ArrayList<Double>()
    ArrayList<Double> meteoFav = new ArrayList<Double>()
    ArrayList<Double> WindDir = new ArrayList<Double>()
    ArrayList<Integer> RoadJunction = new ArrayList<Integer>()

    Map<Integer, Integer> pk = new HashMap<>()
    Map<Integer, Long> OSM_ID = new HashMap<>()
    Map<Integer,Geometry> the_geom = new HashMap<>()

    Map<Integer,Double> TV_D = new HashMap<>()
    Map<Integer,Double> TV_E = new HashMap<>()
    Map<Integer,Double> TV_N = new HashMap<>()
    Map<Integer,Double> HV_D = new HashMap<>()
    Map<Integer,Double> HV_E = new HashMap<>()
    Map<Integer,Double> HV_N = new HashMap<>()
    Map<Integer,Double> LV_SPD_D = new HashMap<>()
    Map<Integer,Double> LV_SPD_E = new HashMap<>()
    Map<Integer,Double> LV_SPD_N = new HashMap<>()
    Map<Integer,Double> HV_SPD_D = new HashMap<>()
    Map<Integer,Double> HV_SPD_E = new HashMap<>()
    Map<Integer,Double> HV_SPD_N = new HashMap<>()
    Map<Integer,String> PVMT = new HashMap<>()

    void initialise(int nSimu, Properties prop){
        for (int r = 0; r < nSimu; ++r) {
            Refl[r] = prop.getProperty("reflexion_order").toInteger()
            if (prop.getProperty("computeHorizontal").toBoolean()) {Dif_hor[r] = 10}else{Dif_hor[r] = 0}
            if (prop.getProperty("computeVertical").toBoolean()) {Dif_ver[r] = 10}else{Dif_ver[r] = 0}
            DistProp[r] = prop.getProperty("maxSrcDistance").toDouble()
            wallAlpha[r] = prop.getProperty("wallAlpha").toDouble()
            TempMean[r] = 12.0d // todo pop.getpro
            HumMean[r] = 82.5d // todo pop.getpro
            SpeedMean[r] = 1.0d
            FlowMean[r] = 1.0d
            FlowMean_MajAxes[r] = 1.0d
            FlowMean_MedAxes[r] = 1.0d
            FlowMean_SmaAxes[r] = 1.0d
            HV[r] = 1.0d
            MV[r] = 5.0d
            WV[r] = 3.0d
            meteoFav[r] = 1.0d
            WindDir[r] = 0.0d
            RoadJunction[r] = 0
        }
    }
// Getter
    double[] getWjSourcesD(int idSource, int r ) {
        return this.wjSourcesD.get(idSource).get(r)
    }
    double[] getWjSourcesE(int idSource, int r ) {
        return this.wjSourcesE.get(idSource).get(r)
    }
    double[] getWjSourcesN(int idSource, int r ) {
        return this.wjSourcesN.get(idSource).get(r)
    }

    void getTrafficLevel(int nSimu) throws SQLException {
        /**
         * Vehicles category Table 3 P.31 CNOSSOS_EU_JRC_REFERENCE_REPORT
         * lv : Passenger cars, delivery vans ≤ 3.5 tons, SUVs , MPVs including trailers and caravans
         * mv: Medium heavy vehicles, delivery vans > 3.5 tons,  buses, touring cars, etc. with two axles and twin tyre mounting on rear axle
         * hgv: Heavy duty vehicles, touring cars, buses, with three or more axles
         * wav:  mopeds, tricycles or quads ≤ 50 cc
         * wbv:  motorcycles, tricycles or quads > 50 cc
         * @param lv_speed Average light vehicle speed
         * @param mv_speed Average medium vehicle speed
         * @param hgv_speed Average heavy goods vehicle speed
         * @param wav_speed Average light 2 wheels vehicle speed
         * @param wbv_speed Average heavy 2 wheels vehicle speed
         * @param lvPerHour Average light vehicle per hour
         * @param mvPerHour Average heavy vehicle per hour
         * @param hgvPerHour Average heavy vehicle per hour
         * @param wavPerHour Average heavy vehicle per hour
         * @param wbvPerHour Average heavy vehicle per hour
         * @param FreqParam Studied Frequency
         * @param Temperature Temperature (Celsius)
         * @param roadSurface roadSurface empty default, NL01 FR01 ..
         * @param Ts_stud A limited period Ts (in months) over the year where a average proportion pm of light vehicles are equipped with studded tyres and during .
         * @param Pm_stud Average proportion of vehicles equipped with studded tyres
         * @param Junc_dist Distance to junction
         * @param Junc_type Type of junction ((k = 1 for a crossing with traffic lights ; k = 2 for a roundabout)
         */
        //connect
       // Map<Integer, List<double[]>> sourceLevel = new HashMap<>()

        // memes valeurs d e et n


        def list = [63, 125, 250, 500, 1000, 2000, 4000, 8000]
        int ii= 0
        double computeRatio = Math.round(0*100/ pk.size())
        pk.each { id, val ->
            if (Math.round(ii*100/ pk.size() as float)!= computeRatio) {
                computeRatio = Math.round(ii*100/ pk.size() as float)
                System.println(computeRatio + " % ")
            }
            ii=ii+1



            def speed_lv = LV_SPD_D.get(id)
            def speed_pl = HV_SPD_D.get(id)
            def speed_lv_d = LV_SPD_D.get(id)

            def lv_d_speed = LV_SPD_D.get(id)
            def mv_d_speed = (double) 0.0
            def hv_d_speed = HV_SPD_D.get(id)
            def wav_d_speed = (double) 0.0
            def wbv_d_speed = (double) 0.0
            def lv_e_speed = LV_SPD_E.get(id)
            def mv_e_speed = (double) 0.0
            def hv_e_speed = HV_SPD_E.get(id)
            def wav_e_speed = (double) 0.0
            def wbv_e_speed = (double) 0.0
            def lv_n_speed = LV_SPD_N.get(id)
            def mv_n_speed = (double) 0.0
            def hv_n_speed = HV_SPD_N.get(id)
            def wav_n_speed = (double) 0.0
            def wbv_n_speed = (double) 0.0

            def Zstart = (double) 0.0
            def Zend = (double) 0.0
            def Juncdist = (double) 250.0
            def Junc_type = (int) 1
            def road_pav = (String) PVMT.get(id)

            // Ici on calcule les valeurs d'emission par tronçons et par fréquence

            List<double[]> sourceLevel2 = new ArrayList<>()
            List<double[]> sl_res_d = new ArrayList<>()
            List<double[]> sl_res_e = new ArrayList<>()
            List<double[]> sl_res_n = new ArrayList<>()

            for (int r = 0; r < nSimu; ++r) {

                int kk = 0
                double[] res_d = [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]
                double[] res_e = [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]
                double[] res_n = [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]

                /*if (PL[r]) {
                    PLD = PLD_Ref
                    PLE = PLE_Ref
                    PLN = PLN_Ref
                } else {
                    PLD = PLD_D
                    PLE = PLE_D
                    PLN = PLN_D
                }

                if (TMJA[r]) {
                    TMJAD = TMJAD_Ref
                    TMJAE = TMJAE_Ref
                    TMJAN = TMJAN_Ref

                } else {
                    TMJAD = TMJAD_D
                    TMJAE = TMJAE_D
                    TMJAN = TMJAN_D
                }*/

                /*vl_d_per_hour = (TMJAD - (PLD * TMJAD / 100))
                pl_d_per_hour = (TMJAD * PLD / 100)
                vl_e_per_hour = (TMJAE - (TMJAE * PLE / 100))
                pl_e_per_hour = (TMJAE * PLE / 100)
                vl_n_per_hour = (TMJAN - (TMJAN * PLN / 100))
                pl_n_per_hour = (TMJAN * PLN / 100)*/

                /*if (Speed[r]) {
                    lv_d_speed = speed_lv
                    hv_d_speed = speed_pl
                    lv_e_speed = speed_lv
                    hv_e_speed = speed_pl
                    lv_n_speed = speed_lv
                    hv_n_speed = speed_pl
                } else {
                    lv_d_speed = speed_lv_d
                    hv_d_speed = speed_lv_d
                    lv_e_speed = speed_lv_d
                    hv_e_speed = speed_lv_d
                    lv_n_speed = speed_lv_d
                    hv_n_speed = speed_lv_d
                }*/

                for (f in list) {
                    // fois 0.5 car moitié dans un sens et moitié dans l'autre
                    /*String RS = "NL01
                    "
                    switch (R_Road[r]){
                        case 1:
                            RS ="NL01"
                            break
                        case 2 :
                            RS ="NL02"
                            break
                        case 3 :
                            RS ="NL03"
                            break
                        case 4 :
                            RS ="NL04"
                            break
                    }*/
                    String RS = "NL05"
                    if (RoadType[r] == 1) {
                        RS = road_pav
                    }

                    /*"Refl",...
                    "Dif_hor",...
                    "Dif_ver ",...
                    "DistProp",...
                    "Veg",...
                    "TMJA",...
                    "PL",...
                    "Speed",...
                    "RoadType",...
                    "TempMean",...
                    "HumMean",...
                    "Meteo",...
                    "SpeedMean",...
                    "FlowMean"};*/
                    def ml_d_per_hour = (double) TV_D.get(id)*MV[r]/100
                    def pl_d_per_hour = (double) TV_D.get(id)*(HV_D.get(id)*HV[r])/100
                    def wa_d_per_hour = (double) TV_D.get(id)*WV[r]/100
                    def wb_d_per_hour = (double) 0.0
                    def vl_d_per_hour = (double) TV_D.get(id) - pl_d_per_hour - ml_d_per_hour - wa_d_per_hour

                    def ml_e_per_hour = (double) TV_E.get(id)*MV[r]/100
                    def pl_e_per_hour = (double) HV_E.get(id) * (HV_E.get(id)*HV[r])/100
                    def wa_e_per_hour = (double) TV_E.get(id)*WV[r]/100
                    def wb_e_per_hour = (double) 0.0
                    def vl_e_per_hour = (double) TV_E.get(id) - pl_e_per_hour - ml_e_per_hour - wa_e_per_hour

                    def ml_n_per_hour = (double) TV_N.get(id) *MV[r]/100
                    def pl_n_per_hour = (double) HV_N.get(id)* (HV_N.get(id)*HV[r])/100
                    def wa_n_per_hour = (double) TV_N.get(id) * WV[r]/100
                    def wb_n_per_hour = (double) 0.0
                    def vl_n_per_hour = (double) TV_N.get(id) - pl_n_per_hour - ml_n_per_hour - wa_n_per_hour

                    def total_flow_per_hour = (double) TV_D.get(id)

                    if (total_flow_per_hour>1000){
                        FlowMean[r] = FlowMean_MajAxes[r]
                    }
                    if (total_flow_per_hour<=1000 && total_flow_per_hour>=300){
                        FlowMean[r] = FlowMean_MedAxes[r]
                    }
                    if (total_flow_per_hour<300){
                        FlowMean[r] = FlowMean_SmaAxes[r]
                    }

                    RSParametersCnossos srcParameters_d = new RSParametersCnossos(lv_d_speed * SpeedMean[r], mv_d_speed * SpeedMean[r], hv_d_speed * SpeedMean[r], wav_d_speed * SpeedMean[r], wbv_d_speed * SpeedMean[r],
                            vl_d_per_hour * FlowMean[r], ml_d_per_hour * FlowMean[r], pl_d_per_hour * FlowMean[r], wa_d_per_hour * FlowMean[r], wb_d_per_hour * FlowMean[r],
                            f, TempMean[r], RS, 0, 0, 250, 1)
                    RSParametersCnossos srcParameters_e = new RSParametersCnossos(lv_e_speed * SpeedMean[r], mv_e_speed * SpeedMean[r], hv_e_speed * SpeedMean[r], wav_e_speed * SpeedMean[r], wbv_e_speed * SpeedMean[r],
                            vl_e_per_hour * FlowMean[r], ml_e_per_hour * FlowMean[r], pl_e_per_hour * FlowMean[r], wa_e_per_hour * FlowMean[r], wb_e_per_hour * FlowMean[r],
                            f, TempMean[r], RS, 0, 0, 250, 1)
                    RSParametersCnossos srcParameters_n = new RSParametersCnossos(lv_n_speed * SpeedMean[r], mv_n_speed * SpeedMean[r], hv_n_speed * SpeedMean[r], wav_n_speed * SpeedMean[r], wbv_n_speed * SpeedMean[r],
                            vl_n_per_hour * FlowMean[r], ml_n_per_hour * FlowMean[r], pl_n_per_hour * FlowMean[r], wa_n_per_hour * FlowMean[r], wb_n_per_hour * FlowMean[r],
                            f, TempMean[r], RS, 0, 0, 250, 1)

                    srcParameters_d.setSlopePercentage(RSParametersCnossos.computeSlope(0, 0, the_geom.get(id).getLength()))
                    srcParameters_e.setSlopePercentage(RSParametersCnossos.computeSlope(0, 0, the_geom.get(id).getLength()))
                    srcParameters_n.setSlopePercentage(RSParametersCnossos.computeSlope(0, 0, the_geom.get(id).getLength()))
                    //res_d[kk] = EvaluateRoadSourceCnossos.evaluate(srcParameters_d)
                    //res_e[kk] = EvaluateRoadSourceCnossos.evaluate(srcParameters_e)
                    //res_n[kk] = EvaluateRoadSourceCnossos.evaluate(srcParameters_n)
                    //srcParameters_d.setSlopePercentage(RSParametersCnossos.computeSlope(Zend, Zstart, the_geom.getLength()))
                    //srcParameters_e.setSlopePercentage(RSParametersCnossos.computeSlope(Zend, Zstart, the_geom.getLength()))
                    //srcParameters_n.setSlopePercentage(RSParametersCnossos.computeSlope(Zend, Zstart, the_geom.getLength()))
                    res_d[kk] = 10 * Math.log10(
                            (1.0 / 24.0) *
                                    (12 * Math.pow(10, (10 * Math.log10(Math.pow(10, EvaluateRoadSourceCnossos.evaluate(srcParameters_d) / 10))) / 10)
                                            + 4 * Math.pow(10, (10 * Math.log10(Math.pow(10, EvaluateRoadSourceCnossos.evaluate(srcParameters_e) / 10))) / 10)
                                            + 8 * Math.pow(10, (10 * Math.log10(Math.pow(10, EvaluateRoadSourceCnossos.evaluate(srcParameters_n) / 10))) / 10))
                    )
                    res_d[kk] += ComputeRays.dbaToW(EvaluateRoadSourceCnossos.evaluate(srcParameters_d))
                    res_e[kk] += ComputeRays.dbaToW(EvaluateRoadSourceCnossos.evaluate(srcParameters_e))
                    res_n[kk] += ComputeRays.dbaToW(EvaluateRoadSourceCnossos.evaluate(srcParameters_n))

                    kk++
                }

                sl_res_d.add(res_d)
                sl_res_e.add(res_e)
                sl_res_n.add(res_n)
            }
            wjSourcesD.put(id, sl_res_d)
            wjSourcesE.put(id, sl_res_e)
            wjSourcesN.put(id, sl_res_n)
        }
    }

    static double[] pushAndPop(double[] arrayList, int nTimes){
        def list = Doubles.asList(arrayList)
        def stack = list as Stack
        for (i in 1..nTimes){
            def lastElement = stack.pop()
            stack.insertElementAt(lastElement,0)
        }
        return stack as double[]
       }

    PropagationProcessPathData createGenericMeteoData(int r, String DEN) {
        PropagationProcessPathData genericMeteoData = new PropagationProcessPathData()

        genericMeteoData.setHumidity(HumMean[r])
        genericMeteoData.setTemperature(TempMean[r])

        double[] favrose = [0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0]
        //double[] favrose_6_18 = [0.41, 0.39, 0.38, 0.37, 0.36, 0.35, 0.34, 0.34, 0.34, 0.38, 0.44, 0.47, 0.48, 0.49, 0.49, 0.47, 0.45, 0.43];
        //double[] favrose_18_22 = [0.53, 0.45, 0.41, 0.39, 0.37, 0.36, 0.36, 0.38, 0.41, 0.53, 0.62, 0.65, 0.67, 0.67, 0.68, 0.69, 0.68, 0.64];
        //double[] favrose_22_6 = [0.64, 0.57, 0.51, 0.48, 0.46, 0.43, 0.41, 0.38, 0.36, 0.38, 0.44, 0.49, 0.52, 0.55, 0.58, 0.61, 0.64, 0.67];
        if (DEN == "day") favrose = [0.41, 0.39, 0.38, 0.36, 0.35, 0.34, 0.34, 0.34, 0.38, 0.44, 0.47, 0.48,  0.49, 0.47, 0.45, 0.43]
        if (DEN == "evening") favrose = [0.41, 0.39, 0.38, 0.36, 0.35, 0.34, 0.34, 0.34, 0.38, 0.44, 0.47, 0.48,  0.49, 0.47, 0.45, 0.43]
        if (DEN == "night") favrose = [0.41, 0.39, 0.38, 0.36, 0.35, 0.34, 0.34, 0.34, 0.38, 0.44, 0.47, 0.48,  0.49, 0.47, 0.45, 0.43]

        if (windDir[r] == -60) favrose = pushAndPop(favrose,10)
        if (windDir[r] == -30) favrose = pushAndPop(favrose,11)
        if (windDir[r] == 30) favrose =  pushAndPop(favrose,1)
        if (windDir[r] == 60) favrose = pushAndPop(favrose,2)
        //favrose = favrose.collect{it*meteoFav[r]}

        for (int i = 0; i < favrose.length; i++) {
            favrose[i] = favrose.collect{it*meteoFav[r]}.get(i) as double
        }
        genericMeteoData.setWindRose(favrose)
        return genericMeteoData
    }




    int setSensitivityTable(File file, Properties prop, int nbSimu) {
        //////////////////////
        // Import file text
        //////////////////////
       // int i_read = 0

        ObjectMapper mapper = new ObjectMapper()
       // JsonNode Data = EvaluateRoadSourceCnossos.parse(EvaluateRoadSourceCnossos.getResourceAsStream("D:\\aumond\\Documents\\Boulot\\Articles\\2019_XX_XX Sensitivity\\MR_input.json"));


        // JSON file to Java object
        //MrInputs mrInputs = mapper.readValue(file, MrInputs.class)
        JsonNode  mrInputs = mapper.readValue(file, JsonNode.class)

        // pretty print
        if (nbSimu == -1) {nbSimu = mrInputs.getAt(0).size()}


        initialise(nbSimu, prop)

        for (int i = 0; i < mrInputs.fieldNames().size(); ++i ) {
            for (int r = 0; r < nbSimu; ++r) {
                switch (mrInputs.fieldNames()[i]) {
                    case 'Refl':
                        Refl[r] = mrInputs.get('Refl').get(r).asInt()
                        break
                    case 'Dif_hor' :
                        Dif_hor[r] = mrInputs.get('Dif_hor').get(r).asInt()
                        break
                    case 'Dif_ver' :
                        Dif_ver[r] = mrInputs.get('Dif_ver').get(r).asInt()
                        break
                    case 'DistProp':
                        DistProp[r] = mrInputs.get('DistProp').get(r).asDouble()
                        break
                    case 'wallAlpha' :
                        wallAlpha[r] = mrInputs.get('wallAlpha').get(r).asDouble()
                        break
                    case 'TempMean' :
                        TempMean[r] = mrInputs.get('TempMean').get(r).asDouble()
                        break
                    case 'HumMean' :
                        HumMean[r] = mrInputs.get('HumMean').get(r).asDouble()
                        break
                    case 'SpeedMean' :
                        SpeedMean[r] = mrInputs.get('SpeedMean').get(r).asDouble()
                        break
                    case 'FlowMean' :
                        FlowMean[r] = mrInputs.get('FlowMean').get(r).asDouble()
                        break
                    case 'FlowMean_MajAxes' :
                        FlowMean_MajAxes[r] = mrInputs.get('FlowMean_MajAxes').get(r).asDouble()
                        break
                    case 'FlowMean_MedAxes' :
                        FlowMean_MedAxes[r] = mrInputs.get('FlowMean_MedAxes').get(r).asDouble()
                        break
                    case 'FlowMean_SmaAxes' :
                        FlowMean_SmaAxes[r] = mrInputs.get('FlowMean_SmaAxes').get(r).asDouble()
                        break
                    case 'HV' :
                        HV[r] = mrInputs.get('HV').get(r).asDouble()
                        break
                    case 'MV' :
                        MV[r] = mrInputs.get('MV').get(r).asDouble()
                        break
                    case 'WV' :
                        WV[r] = mrInputs.get('WV').get(r).asDouble()
                        break
                    case 'meteoFav' :
                        meteoFav[r] = mrInputs.get('meteoFav').get(r).asDouble()
                        break
                    case 'WindDir' :
                        WindDir[r] = mrInputs.get('WindDir').get(r).asDouble()
                        break
                    case 'RoadJunction' :
                        RoadJunction[r] = mrInputs.get('RoadJunction').get(r).asInt()
                        break
                }

            }
        }

        return nbSimu
        // Remplissage des variables avec le contenu du fichier plan d'exp
        /*file.splitEachLine(",") { fields ->

            Refl.add(fields[0].toInteger())
            Dif_hor.add(fields[1].toInteger())
            Dif_ver.add(fields[2].toInteger())
            DistProp.add(fields[3].toFloat())
            Veg.add(fields[4].toInteger())
            TMJA.add(fields[5].toInteger())
            PL.add(fields[6].toInteger())
            Speed.add(fields[7].toInteger())
            RoadType.add(fields[8].toInteger())
            TempMean.add(fields[9].toFloat())
            HumMean.add(fields[10].toFloat())
            Meteo.add(fields[11].toInteger())
            SpeedMean.add(fields[12].toFloat())
            FlowMean.add(fields[13].toFloat())

            Simu.add(fields[14].toInteger())

            i_read = i_read + 1
        }*/

    }


    void setRoadTable(String tablename, Sql sql, Properties prop) {
        //////////////////////
        // Import file text
        //////////////////////


        sql.eachRow('SELECT '+prop.getProperty("pkSources")+', CAST(OSM_ID AS INTEGER) OSM_ID, the_geom , ' +
                'TV_D,TV_E,TV_N,' +
                'HV_D,HV_E,HV_N, ' +
                'LV_SPD_D, LV_SPD_E, LV_SPD_N, ' +
                'HV_SPD_D, HV_SPD_E, HV_SPD_N, ' +
                'PVMT FROM ' + tablename + ';') { fields ->


            int pkId = (int) fields[prop.getProperty("pkSources")]




            pk.put((int) fields[prop.getProperty("pkSources")] ,(int) fields[prop.getProperty("pkSources")])
            OSM_ID.put((int) fields[prop.getProperty("pkSources")],(long) fields["OSM_ID"])
            the_geom.put((int) fields[prop.getProperty("pkSources")], (Geometry) fields["the_geom"])
            TV_D.put((int) fields[prop.getProperty("pkSources")],(double) fields["TV_D"])
            TV_E.put((int) fields[prop.getProperty("pkSources")],(double) fields["TV_E"])
            TV_N.put((int) fields[prop.getProperty("pkSources")],(double) fields["TV_N"])
            HV_D.put((int) fields[prop.getProperty("pkSources")],(double) fields["HV_D"])
            HV_E.put((int) fields[prop.getProperty("pkSources")],(double) fields["HV_E"])
            HV_N.put((int) fields[prop.getProperty("pkSources")],(double) fields["HV_N"])
            LV_SPD_D.put((int) fields[prop.getProperty("pkSources")],(double) fields["LV_SPD_D"])
            LV_SPD_E.put((int) fields[prop.getProperty("pkSources")],(double) fields["LV_SPD_E"])
            LV_SPD_N.put((int) fields[prop.getProperty("pkSources")],(double) fields["LV_SPD_N"])
            HV_SPD_D.put((int) fields[prop.getProperty("pkSources")],(double) fields["HV_SPD_D"])
            HV_SPD_E.put((int) fields[prop.getProperty("pkSources")],(double) fields["HV_SPD_E"])
            HV_SPD_N.put((int) fields[prop.getProperty("pkSources")],(double) fields["HV_SPD_N"])
            PVMT.put((int) fields[prop.getProperty("pkSources")],(String) fields["PVMT"])

        }

    }

}


@CompileStatic
class PointToPointPathsMultiRuns {
    ArrayList<MRPropagationPath> propagationPathList
    double li
    long sourceId
    long receiverId

    /**
     * Writes the content of this object into <code>out</code>.
     * @param out the stream to write into
     * @throws java.io.IOException if an I/O-error occurs
     */
    void writePropagationPathListStream( DataOutputStream out) throws IOException {

        out.writeLong(receiverId)
        out.writeLong(sourceId)
        out.writeDouble(li)
        out.writeInt(propagationPathList.size())
        for(MRPropagationPath propagationPath : propagationPathList) {
            propagationPath.writeStream(out);
        }
    }

    /**
     * Reads the content of this object from <code>out</code>. All
     * properties should be set to their default value or to the value read
     * from the stream.
     * @param in the stream to read
     * @throws IOException if an I/O-error occurs
     */
    void readPropagationPathListStream( DataInputStream inputStream) throws IOException {
        if (propagationPathList==null){
            propagationPathList = new ArrayList<>()
        }

        receiverId = inputStream.readLong()
        sourceId = inputStream.readLong()
        li = inputStream.readDouble()
        int propagationPathsListSize = inputStream.readInt()
        propagationPathList.ensureCapacity(propagationPathsListSize)
        for(int i=0; i < propagationPathsListSize; i++) {
            MRPropagationPath propagationPath = new MRPropagationPath()
            propagationPath.readStream(inputStream)
            propagationPathList.add(propagationPath)
        }
    }

}

private void extractFile(ZipInputStream zipIn, String filePath) throws IOException {
    BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath));
    byte[] bytesIn = new byte[4096]
    int read = 0
    while ((read = zipIn.read(bytesIn)) != -1) {
        bos.write(bytesIn, 0, read)
    }
    bos.close()
}