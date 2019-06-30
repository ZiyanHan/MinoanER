#!/bin/sh
echo "build start..."

# Define some constants.
PROJECT_PATH=/home/ubuntu/hanzy/MinoanER
#JAR_PATH=$PROJECT_PATH/lib
BIN_PATH=$PROJECT_PATH/bin
SRC_PATH=$PROJECT_PATH/src
SRC_FILE_LIST_PATH=$SRC_PATH/sources.list
DATASETS_PATH=/home/ubuntu/hanzy/MinoanER/datasets/bbcMusic
OUT_PATH=$DATASETS_PATH/Output

# First remove the sources.list file if it exists and then create the sources file of the project.
rm -f $SRC_PATH/sources.list
find $SRC_PATH/ -name *.java > $SRC_PATH/sources.list

# First remove the bin directory if it exists and then create the bin directory of .class files.
rm -rf $BIN_PATH/
mkdir $BIN_PATH/

# Generate jar list.
#for file in  $JAR_PATH/*.jar;
#do
#jarfile=$jarfile:$file
#done
#echo "jarfile = "$jarfile

# Set CLASSPATH.
#CLASSPATH=.:$JAVA_HOME/lib/dt.jar:$JAVA_HOME/lib/tools.jar:$BIN_PATH:$jarfile
CLASSPATH=.:$JAVA_HOME/lib/dt.jar:$JAVA_HOME/lib/tools.jar:$CLASSPATH:$BIN_PATH/

# Compile the project.
#javac -d $BIN_PATH/ -cp $jarfile @$SRC_FILE_LIST_PATH
javac -d $BIN_PATH/ -cp $BIN_PATH:$CLASSPATH @$SRC_FILE_LIST_PATH

# Run the project.
echo "run start..."
java -cp $CLASSPATH main.java.minoaner.workflow.Main $DATASETS_PATH/blocks.txt $DATASETS_PATH/bbc-musicEntityIds.tsv $DATASETS_PATH/bbc-musicEntityIds.tsv $DATASETS_PATH/bbc-musicIds.txt $DATASETS_PATH/bbc-musicIds.txt $OUT_PATH

