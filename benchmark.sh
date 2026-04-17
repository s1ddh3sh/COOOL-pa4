#!/bin/bash

# Check if a testcase name is provided
if [ -z "$1" ]; then
    echo "Usage: $0 <TestCaseName>"
    echo "Example: $0 Test2"
    exit 1
fi

TESTCASE=$1

echo "======================================"
echo "    Running Benchmark for $TESTCASE   "
echo "======================================"

echo "1. Compiling tests/$TESTCASE/*.java..."
javac tests/$TESTCASE/*.java
if [ $? -ne 0 ]; then
    echo "Compilation failed! Exiting."
    exit 1
fi

echo ""
echo "2. Running Soot transformation (src.Main) on $TESTCASE..."
java -cp .:soot-4.6.0-jar-with-dependencies.jar src.Main $TESTCASE
if [ $? -ne 0 ]; then
    echo "Soot transformation failed! Exiting."
    exit 1
fi

echo ""
echo "3. Benchmarking using Interpreted Mode (-Xint)..."
echo "--- Baseline Execution ($TESTCASE/before) ---"
/usr/bin/time -f "baseline: %e s" java -Xint -cp sootOutput/$TESTCASE/before Test

echo ""
echo "--- Optimized Execution ($TESTCASE/after) ---"
/usr/bin/time -f "optimized: %e s" java -Xint -cp sootOutput/$TESTCASE/after Test

echo "======================================"
