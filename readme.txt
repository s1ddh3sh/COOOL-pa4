1. javac tests/Test1/Test.java
2. javac -cp soot-4.6.0-jar-with-dependencies.jar -d . src/Main.java src/AnalysisTransformer.java
3. java -cp .:soot-4.6.0-jar-with-dependencies.jar src.Main <TestName> <ALGO> <FORMAT>

Example (RTA on Test1, class output):
    java -cp .:soot-4.6.0-jar-with-dependencies.jar src.Main Test1 RTA C

Arguments:
- TestName: Test1 ... Test10
- ALGO: CHA | RTA | VTA | BASELINE
- FORMAT: J (Jimple) | C (class files)

Generated output for a run:
- Baseline: sootOutput/<TestName>/<ALGO>/before
- Transformed: sootOutput/<TestName>/<ALGO>/after

Test and benchmark testcase(s):
    ./run_test.sh [iterations] [TestName ...]

Examples:
    ./run_test.sh 3
    ./run_test.sh 5 Test1
    ./run_test.sh 5 Test3 Test7 Test10

What run_test.sh does for each testcase:
1) Compiles the testcase.
2) Runs CHA, RTA, and VTA transformations.
3) Checks functional equivalence (Original vs before vs after).
4) Benchmarks Original vs optimized outputs.
5) Prints average runtime and speedup.

If a transformed version crashes, it is marked INVALID.