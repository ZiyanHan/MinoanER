#!/bin/sh

# Define some constants,i.e.,the input parameters.
DATASETS_PATH=/home/ubuntu/hanzy/datasets
BLOCK_PATH=$DATASETS_PATH/bbcMusic/blocks.txt
TRIPLE_PATH1=$DATASETS_PATH/bbcMusic/bbc-musicEntityIds.tsv
TRIPLE_PATH2=$DATASETS_PATH/bbcMusic/bbc-musicEntityIds.tsv
ENTITYID_PATH1=$DATASETS_PATH/bbcMusic/bbc-musicIds.txt
ENTITYID_PATH2=$DATASETS_PATH/bbcMusic/bbc-musicIds.txt
OUTPUT_PATH=$DATASETS_PATH/bbcMusic/Output

# Build the project.
echo "build start..."
mvn compile

# Run the project.
echo "run start..."
mvn exec:java -Dexec.mainClass="minoaner.workflow.Main" -Dexec.args="$BLOCK_PATH $TRIPLE_PATH1 $TRIPLE_PATH2 $ENTITYID_PATH1 $ENTITYID_PATH2 $OUTPUT_PATH" -Dexec.cleanupDaemonThreads=false
