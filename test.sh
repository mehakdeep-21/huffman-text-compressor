#!/usr/bin/env bash
# Build and run the unit tests.
set -e
cd "$(dirname "$0")"
mkdir -p out
javac -d out src/huffman/*.java
java -cp out huffman.HuffmanTests
