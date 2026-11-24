#!/usr/bin/env bash
# Build and run the Huffman compressor.  Usage: ./run.sh [port]
set -e
cd "$(dirname "$0")"
mkdir -p out
javac -d out src/huffman/*.java
java -cp out huffman.Server "${1:-8000}"
