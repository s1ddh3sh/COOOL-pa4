Run from project root.

Single script for evaluation:
    ./run_test.sh [iterations] [TestName ...]

Examples:
    ./run_test.sh 3
    ./run_test.sh 5 Test1
    ./run_test.sh 5 Test3 Test7 Test10

What the script does for each testcase:
1) Compiles the testcase.
2) Runs CHA, RTA, and VTA transformations to generate optimized bytecode.
3) Validates functional equivalence (Original vs before vs after).
4) Benchmarks baseline and optimized code using interpreter.
5) Prints average runtime and speedup for Original, CHA, RTA, and VTA.

If an optimized output crashes or changes program output, that variant is printed as INVALID and no speedup is reported for it.

Generated outputs:
- Optimized classes and logs are written under sootOutput/<TestName>/<ALGO>/
- Per-algorithm logs are in sootOutput/<TestName>/<ALGO>/run.log