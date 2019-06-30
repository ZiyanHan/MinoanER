#!/bin/sh

# Build the project.
echo "build start..."
mvn compile

# Run the project.
echo "run start..."
mvn exec:java -Dexec.mainClass="minoaner.workflow.Main" -Dexec.args="/home/ubuntu/hanzy/MinoanER/datasets/bbcMusic/blocks.txt /home/ubuntu/hanzy/MinoanER/datasets/bbcMusic/bbc-musicEntityIds.tsv /home/ubuntu/hanzy/MinoanER/datasets/bbcMusic/bbc-musicEntityIds.tsv /home/ubuntu/hanzy/MinoanER/datasets/bbcMusic/bbc-musicIds.txt /home/ubuntu/hanzy/MinoanER/datasets/bbcMusic/bbc-musicIds.txt /home/ubuntu/hanzy/MinoanER/datasets/bbcMusic/Output" -Dexec.cleanupDaemonThreads=false

