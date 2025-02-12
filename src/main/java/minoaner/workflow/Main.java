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

package minoaner.workflow;

import it.unimi.dsi.fastutil.ints.Int2FloatLinkedOpenHashMap;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import minoaner.metablocking.entityBased.neighbors.CNPARCS;
import minoaner.matching.LabelMatchingHeuristic;
import minoaner.matching.ReciprocalMatchingFromMetaBlocking;
import minoaner.metablocking.preprocessing.BlockFilteringAdvanced;
import minoaner.metablocking.preprocessing.BlocksFromEntityIndex;
import minoaner.utils.Utils;
import org.apache.parquet.it.unimi.dsi.fastutil.ints.IntArrayList;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.storage.StorageLevel;
import org.apache.spark.util.LongAccumulator;

/**
 * The main file to run a complete MinoanER workflow.
 * @author vefthym
 */
public class Main {
    
    
    
    public static void main(String[] args) {
        String tmpPath;
        String master;
        String inputPath;      
        String outputPath;
        String inputTriples1, inputTriples2;
        String entityIds1, entityIds2;
        
        
        if (args.length == 0) {
            System.setProperty("hadoop.home.dir", "C:\\Users\\VASILIS\\Documents\\hadoop_home"); //only for local mode
            
            tmpPath = "/file:C:\\tmp";
            master = "local[2]";
            inputPath = "/file:C:\\Users\\VASILIS\\Documents\\OAEI_Datasets\\exportedBlocks\\testInput";  
            inputTriples1 = "";
            inputTriples2 = "";
            entityIds1 = "";
            entityIds2 = "";
            outputPath = "/file:C:\\Users\\VASILIS\\Documents\\OAEI_Datasets\\exportedBlocks\\testOutput";            
        } else if (args.length >= 6) {            
            //tmpPath = "/file:/tmp";
	    tmpPath = "/home/ubuntu/hanzy/datasets/bbcMusic";
            //master = "spark://master:7077";
            inputPath = args[0];
            inputTriples1 = args[1];
            inputTriples2 = args[2];
            entityIds1 = args[3];
            entityIds2 = args[4];
            outputPath = args[5];
            
            // 手动删除输出文件的位置！！！            
            // delete existing output directories
            // try {                                
                // Utils.deleteHDFSPath(outputPath);
            // } catch (IOException | URISyntaxException ex) {
                // Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
            // }
        } else {
            System.out.println("You can run Metablocking with the following arguments:"
                    + "0: inputBlocking" 
                    + "1: inputTriples1 (raw rdf triples)"
                    + "2: inputTriples2 (raw rdf triples)"
                    + "3: entityIds1: entityUrl\tentityId (positive)"
                    + "4: entityIds2: entityUrl\tentityId (also positive)"
                    + "5: outputPath");
            return;
        }
        
        String appName = "Running MinoanER on "+inputPath.substring(inputPath.lastIndexOf("/", inputPath.length()-2)+1);
        //SparkSession spark = Utils.setUpSpark(appName, 288, 8, 3, tmpPath);
	SparkSession spark = Utils.setUpSpark(appName, 2, 1, 1, tmpPath);
        int PARALLELISM = spark.sparkContext().getConf().getInt("spark.default.parallelism", 144);        
        JavaSparkContext jsc = JavaSparkContext.fromSparkContext(spark.sparkContext()); 
                       
        
        ////////////////////////
        //start the processing//
        ////////////////////////
        
        String SEPARATOR = (inputTriples1.endsWith(".tsv"))? "\t" : " ";        
        
        //load the attributes of each dataset that act as names/labels
        //those can be automatically be discovered by considering the top-N attributes w.r.t. F-1 of support and discriminability (see paper/PhD thesis)
        //entities that have exactly the same label values will be declared matches, if no other entity has the same label value        
        
        //YAGO-IMDb
        Set<String> labelAtts1 = new HashSet<>(Arrays.asList("rdfs:label", "label", "skos:prefLabel"));
        Set<String> labelAtts2 = labelAtts1;
        
        if (inputTriples1.contains("music")) {        
            //BBCmusic
            labelAtts1 = new HashSet<>(Arrays.asList("<http://purl.org/dc/elements/1.1/title>", "<http://open.vocab.org/terms/sortLabel>", "<http://xmlns.com/foaf/0.1/name>"));
            labelAtts2 = new HashSet<>(Arrays.asList("<http://www.w3.org/2000/01/rdf-schema#label>", "<http://dbpedia.org/property/name>", "<http://xmlns.com/foaf/0.1/name>"));
        } else if (inputTriples1.contains("rexa")) {
            //Rexa-DBLP
            labelAtts1 = new HashSet<>(Arrays.asList("http://xmlns.com/foaf/0.1/name", "http://www.w3.org/2000/01/rdf-schema#label"));
            labelAtts2 = labelAtts1;
        } else if (inputTriples1.contains("estaurant")) {
            //Restaurants
            labelAtts1 = new HashSet<>(Arrays.asList("<http://www.okkam.org/ontology_restaurant1.owl#name>"));
            labelAtts2 = new HashSet<>(Arrays.asList("<http://www.okkam.org/ontology_restaurant2.owl#name>"));
        }
        
        //parse triples and entity ids as RDDs
        JavaRDD<String> triples1 = jsc.textFile(inputTriples1, PARALLELISM).setName("triples1").persist(StorageLevel.MEMORY_AND_DISK_SER());        
        JavaRDD<String> triples2 = jsc.textFile(inputTriples2, PARALLELISM).setName("triples2").persist(StorageLevel.MEMORY_AND_DISK_SER());        
        JavaRDD<String> ids1 = jsc.textFile(entityIds1, PARALLELISM).setName("ids1").cache();
        JavaRDD<String> ids2 = jsc.textFile(entityIds2, PARALLELISM).setName("ids2").cache();
        
        //label matching heuristic first
        JavaPairRDD<Integer,Integer> matchesFromLabels = new LabelMatchingHeuristic().getMatchesFromLabels(triples1, triples2, ids1, ids2, SEPARATOR, labelAtts1, labelAtts2);
        matchesFromLabels.setName("matchesFromLabels").cache();
        
        //Meta-Blocking
        
        //Block Filtering (delete the biggest blocks of each entity)
        System.out.println("\n\nStarting BlockFiltering, reading from "+inputPath);
        LongAccumulator BLOCK_ASSIGNMENTS_ACCUM = jsc.sc().longAccumulator();        
        JavaPairRDD<Integer,IntArrayList> entityIndex = new BlockFilteringAdvanced().run(jsc.textFile(inputPath), BLOCK_ASSIGNMENTS_ACCUM);                 
        entityIndex.setName("entityIndex").cache();
        
        
        //Blocks From Entity Index (after block filtering, reconstruct the blocks with their remaining entities)
        System.out.println("\n\nStarting BlocksFromEntityIndex...");                
        LongAccumulator CLEAN_BLOCK_ACCUM = jsc.sc().longAccumulator();
        LongAccumulator NUM_COMPARISONS_ACCUM = jsc.sc().longAccumulator();                
        JavaPairRDD<Integer, IntArrayList> blocksFromEI = new BlocksFromEntityIndex().run(entityIndex, CLEAN_BLOCK_ACCUM, NUM_COMPARISONS_ACCUM);
        blocksFromEI.setName("blocksFromEI").cache(); //a few hundred MBs        
        
        System.out.println(blocksFromEI.count()+" blocks have been left after block filtering");
        
        double BCin = (double) BLOCK_ASSIGNMENTS_ACCUM.value() / entityIndex.count(); //BCin: average number of block assignments per entity
        final int K = (args.length >= 7) ? Integer.parseInt(args[6]) : Math.max(1, ((Double)Math.floor(BCin)).intValue()); //K = |_BCin -1_|        
        System.out.println(BLOCK_ASSIGNMENTS_ACCUM.value()+" block assignments");
        System.out.println(CLEAN_BLOCK_ACCUM.value()+" clean blocks");
        System.out.println(NUM_COMPARISONS_ACCUM.value()+" comparisons");
        System.out.println("BCin = "+BCin);
        System.out.println("K = "+K);
        
        entityIndex.unpersist();
        
        //CNP (Cardinality Node Pruning is a Meta-blocking pruning scheme. It keeps the top-K candidates of each entity and prunes the rest.)
        System.out.println("\n\nStarting CNP...");        
        final float MIN_SUPPORT_THRESHOLD = 0.01f;
        final int N = (args.length >= 8) ? Integer.parseInt(args[7]) : 3; //top-N relations
        System.out.println("N = "+N);
        
        System.out.println("Getting the top K value candidates...");
        CNPARCS cnp = new CNPARCS();        
        JavaPairRDD<Integer, Int2FloatLinkedOpenHashMap> topKValueCandidates = cnp.getTopKValueSims(blocksFromEI, K);
        
        blocksFromEI.unpersist();        
        //topKValueCandidates.setName("topKValueCandidates").persist(StorageLevel.MEMORY_AND_DISK_SER());l
        
        System.out.println("Getting the top K neighbor candidates...");
        JavaPairRDD<Integer, Int2FloatLinkedOpenHashMap> topKNeighborCandidates = cnp.run2(
                topKValueCandidates, 
                triples1, 
                triples2, 
                SEPARATOR, 
                ids1,
                ids2,
                MIN_SUPPORT_THRESHOLD, K, N, 
                jsc);
        
        triples1.unpersist();
        triples2.unpersist();
        
        //Matching
        final float valueFactor = (args.length >= 9) ? Float.parseFloat(args[8]) : 0.6f; //the weight of values vs neighbors for the rank aggregation (linear combination)        
        System.out.println("Starting reciprocal matching...");        
        JavaPairRDD<Integer,Integer> matches = new ReciprocalMatchingFromMetaBlocking()
                .getReciprocalMatches(topKValueCandidates, topKNeighborCandidates, valueFactor)
                .subtractByKey(matchesFromLabels) //delete the entities, whose matches have been already found from the label heuristic
                .union(matchesFromLabels); //and then add the matches of those entities from the label heuristic
        
        matches.saveAsTextFile(outputPath);
        
        spark.stop();
    }
    
}
