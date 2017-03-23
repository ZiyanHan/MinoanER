/*
 * Copyright 2017 vefthym.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package metablockingspark.workflow;

import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.logging.Level;
import java.util.logging.Logger;
import metablockingspark.entityBased.neighbors.EntityBasedCNPNeighborsInMemory;
import metablockingspark.preprocessing.BlockFilteringAdvanced;
import metablockingspark.preprocessing.BlocksFromEntityIndex;
import metablockingspark.preprocessing.EntityWeightsWJS;
import metablockingspark.utils.Utils;
import org.apache.parquet.it.unimi.dsi.fastutil.ints.IntArrayList;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.storage.StorageLevel;
import org.apache.spark.util.LongAccumulator;

/**
 *
 * @author vefthym
 */
public class FullMetaBlockingWorkflow {
    
    
    
    public static void main(String[] args) {
        String tmpPath;
        String master;
        String inputPath;      
        String outputPath;
        String inputTriples1, inputTriples2;
        
        
        if (args.length == 0) {
            System.setProperty("hadoop.home.dir", "C:\\Users\\VASILIS\\Documents\\hadoop_home"); //only for local mode
            
            tmpPath = "/file:C:\\tmp";
            master = "local[2]";
            inputPath = "/file:C:\\Users\\VASILIS\\Documents\\OAEI_Datasets\\exportedBlocks\\testInput";  
            inputTriples1 = "";
            inputTriples2 = "";
            outputPath = "/file:C:\\Users\\VASILIS\\Documents\\OAEI_Datasets\\exportedBlocks\\testOutput";            
        } else if (args.length == 4) {            
            tmpPath = "/file:/tmp";
            //master = "spark://master:7077";
            inputPath = args[0];
            inputTriples1 = args[1];
            inputTriples2 = args[2];
            outputPath = args[3];
            // delete existing output directories
            try {                                
                Utils.deleteHDFSPath(outputPath);
            } catch (IOException | URISyntaxException ex) {
                Logger.getLogger(FullMetaBlockingWorkflow.class.getName()).log(Level.SEVERE, null, ex);
            }
        } else {
            System.out.println("You can run Metablocking with the following arguments:"
                    + "0: inputBlocking"
                    + "1: inputTriples1 (raw rdf triples)"
                    + "2: inputTriples2 (raw rdf triples)"
                    + "3: outputPath");
            return;
        }
                       
        final int NUM_CORES_IN_CLUSTER = 128; //128 in ISL cluster, 28 in okeanos cluster
                       
        SparkSession spark = SparkSession.builder()
            .appName("FullMetaBlocking WJS on "+inputPath.substring(inputPath.lastIndexOf("/", inputPath.length()-2)+1)) 
            .config("spark.sql.warehouse.dir", tmpPath)
            .config("spark.eventLog.enabled", true)
            .config("spark.default.parallelism", NUM_CORES_IN_CLUSTER * 4) //x tasks for each core (128 cores) --> x "reduce" rounds
            .config("spark.rdd.compress", true)
            
            //memory configurations (deprecated)            
            .config("spark.memory.useLegacyMode", true)
            .config("spark.shuffle.memoryFraction", 0.4)
            .config("spark.storage.memoryFraction", 0.4)                
            .config("spark.memory.offHeap.enabled", true)
            .config("spark.memory.offHeap.size", "10g")            
            
            //un-comment the following for Kryo serializer (does not seem to improve compression, only speed)            
            /*
            .config("spark.kryo.registrator", MyKryoRegistrator.class.getName())
            .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
            .config("spark.kryo.registrationRequired", true) //just to be safe that everything is serialized as it should be (otherwise runtime error)
            */
            .getOrCreate();        
        
        JavaSparkContext jsc = JavaSparkContext.fromSparkContext(spark.sparkContext());        
        LongAccumulator BLOCK_ASSIGNMENTS_ACCUM = jsc.sc().longAccumulator();
        
        
        ////////////////////////
        //start the processing//
        ////////////////////////
        
        //Block Filtering
        System.out.println("\n\nStarting BlockFiltering, reading from "+inputPath);
        
        BlockFilteringAdvanced bf = new BlockFilteringAdvanced();
        JavaPairRDD<Integer,IntArrayList> entityIndex = bf.run(jsc.textFile(inputPath), BLOCK_ASSIGNMENTS_ACCUM); 
        entityIndex.cache();
        //entityIndex.persist(StorageLevel.DISK_ONLY_2()); //store to disk with replication factor 2
        
        
        //long numEntities = entityIndex.keys().count();
        
        //Blocks From Entity Index
        System.out.println("\n\nStarting BlocksFromEntityIndex...");
                
        LongAccumulator CLEAN_BLOCK_ACCUM = jsc.sc().longAccumulator();
        LongAccumulator NUM_COMPARISONS_ACCUM = jsc.sc().longAccumulator();
        
        BlocksFromEntityIndex bFromEI = new BlocksFromEntityIndex();
        JavaPairRDD<Integer, IntArrayList> blocksFromEI = bFromEI.run(entityIndex, CLEAN_BLOCK_ACCUM, NUM_COMPARISONS_ACCUM);
        blocksFromEI.persist(StorageLevel.DISK_ONLY());
        
        
        //get the total weights of each entity, required by WJS weigthing scheme (only)
        System.out.println("\n\nStarting EntityWeightsWJS...");
        EntityWeightsWJS wjsWeights = new EntityWeightsWJS();        
        Int2FloatOpenHashMap totalWeights = new Int2FloatOpenHashMap(); //saves memory by storing data as primitive types        
        wjsWeights.getWeights(blocksFromEI, entityIndex).foreach(entry -> {
            totalWeights.put(entry._1().intValue(), entry._2().floatValue());
        });
        Broadcast<Int2FloatOpenHashMap> totalWeights_BV = jsc.broadcast(totalWeights);
        
        double BCin = (double) BLOCK_ASSIGNMENTS_ACCUM.value() / entityIndex.count(); //BCin = average number of block assignments per entity
        final int K = ((Double)Math.floor(BCin - 1)).intValue(); //K = |_BCin -1_|
        System.out.println(BLOCK_ASSIGNMENTS_ACCUM.value()+" block assignments");
        System.out.println(CLEAN_BLOCK_ACCUM.value()+" clean blocks");
        System.out.println(NUM_COMPARISONS_ACCUM.value()+" comparisons");
        System.out.println("BCin = "+BCin);
        System.out.println("K = "+K);
        
        entityIndex.unpersist();
        
        long numNegativeEntities = wjsWeights.getNumNegativeEntities();
        long numPositiveEntities = wjsWeights.getNumPositiveEntities();
        
        System.out.println("Found "+numNegativeEntities+" negative entities");
        System.out.println("Found "+numPositiveEntities+" positive entities");
        
        //CNP
        System.out.println("\n\nStarting CNP...");
        String SEPARATOR = " ";
        if (inputTriples1.endsWith(".tsv")) {
            SEPARATOR = "\t";
        }
        final float MIN_SUPPORT_THRESHOLD = 0.01f;
        final int N = 3; //for top-N neighbors
        
        System.out.println("Getting the top K value candidates...");
        EntityBasedCNPNeighborsInMemory cnp = new EntityBasedCNPNeighborsInMemory();        
        JavaPairRDD<Integer, Int2FloatOpenHashMap> topKValueCandidates = cnp.getTopKValueSims(blocksFromEI, totalWeights_BV, K, numNegativeEntities, numPositiveEntities);        
        
        System.out.println("Getting the top K neighbor candidates...");
        JavaPairRDD<Integer, IntArrayList> topKNeighborCandidates = cnp.run(topKValueCandidates, jsc.textFile(inputTriples1), jsc.textFile(inputTriples2), SEPARATOR, MIN_SUPPORT_THRESHOLD, K, N);
        
        System.out.println("Writing results to HDFS...");
        topKNeighborCandidates
                .mapValues(x -> x.toString()).saveAsTextFile(outputPath); //only to see the output and add an action (saving to file may not be needed)
        System.out.println("Job finished successfully. Output written in "+outputPath);
        
        //rank aggregation
        //JavaPairRDD<Integer,Integer> aggregate = topKValueCandidates.join(topKNeighborCandidates). ... ; --> getTop1 candidate per entity (after local borda)
        
        //reciprocal matching
        //JavaPairRDD<Integer,Integer> matches = aggregate.map(pair->((-Id,+Id),1)).reduceByKey((x,y)->x+y).filter(pair->pair._2() == 2).keys();
    }
    
}
