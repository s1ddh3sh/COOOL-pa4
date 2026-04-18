Compilation commands:
javac tests/Test1/Test.java
javac -cp soot-4.6.0-jar-with-dependencies.jar -d . src/Main.java src/CHA/AnalysisTransformer.java

Run commands:
java -cp .:soot-4.6.0-jar-with-dependencies.jar src.Main Test1 CHA C
bash script.sh