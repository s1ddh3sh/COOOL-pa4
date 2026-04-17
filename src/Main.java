package src;

import soot.*;
import soot.options.Options;

public class Main {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java -cp .:soot-4.6.0-jar-with-dependencies.jar src/Main <TestDir> [J|C]");
            return;
        }

        String testName = args[0];
        String outputFormat = parseOutputFormat(args.length >= 2 ? args[1] : "C");

        boolean isDaCapo = testName.startsWith("out-");

        String outputRoot = "./sootOutput/" + testName;
        String baselineOutputDir = outputRoot + "/before";
        String transformedOutputDir = outputRoot + "/after";

        runSoot(testName, baselineOutputDir, false, outputFormat, isDaCapo);
        runSoot(testName, transformedOutputDir, true, outputFormat, isDaCapo);
    }

    private static String parseOutputFormat(String raw) {
        String format = raw == null ? "C" : raw.trim().toUpperCase();
        if ("J".equals(format)) {
            return "J";
        }
        if ("C".equals(format)) {
            return "c";
        }
        throw new IllegalArgumentException("Output format must be J or C");
    }

    private static void runSoot(String testName, String outputDir, boolean transformedPass, String outputFormat, boolean isDaCapo) {
        G.reset();

        Options.v().set_keep_line_number(true);
        Options.v().set_whole_program(true);

        if (transformedPass) {
            SceneTransformer sceneTransformer = new AnalysisTransformer();
            PackManager.v().getPack("wjtp").add(new Transform("wjtp.dfa", sceneTransformer));
            System.out.println("[INFO] transformed output: " + outputDir);
        } else {
            System.out.println("[INFO] baseline output: " + outputDir);
        }

        java.io.File outDir = new java.io.File(outputDir);
        if (outDir.exists() && !outDir.isDirectory()) {
            throw new RuntimeException("Output path exists and is not a directory: " + outputDir);
        }
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new RuntimeException("Unable to create output directory: " + outputDir);
        }

        String[] sootArgs;
        if (isDaCapo) {
            String cp = "decapo/" + testName + ":decapo/dacapo-9.12-MR1-bach.jar";
            String refl_log = "reflection-log:decapo/" + testName + "/refl.log";
            sootArgs = new String[] {
                    "-whole-program",
                    "-app",
                    "-allow-phantom-refs",
                    "-no-bodies-for-excluded",
                    "-soot-classpath", cp,
                    "-prepend-classpath",
                    "-keep-line-number",
                    "-main-class", "Harness",
                    "-process-dir", "decapo/" + testName,
                    "-p", "cg.spark", "on",
                    "-p", "cg", refl_log,
                    "-f", outputFormat,
                    "-d", outputDir,
                    "-ire",
                    "-i", "org.apache.*",
                    "-i", "org.dacapo.*",
                    "-i", "jdt.*",
                    "-i", "jdk.*",
                    "-i", "java.*",
                    "-i", "org.*",
                    "-i", "com.*"
            };
        } else {
            String classpath = "./tests/" + testName;
            sootArgs = new String[] {
                    "-cp", classpath,
                    "-pp",
                    "-w",
                    "-app",
                    "-allow-phantom-refs",
                    "-no-bodies-for-excluded",
                    "-exclude", "java.*",
                    "-exclude", "javax.*",
                    "-exclude", "sun.*",
                    "-exclude", "com.sun.*",
                    "-exclude", "jdk.*",
                    "-f", outputFormat,
                    "-d", outputDir,
                    "-t", "1",
                    "-main-class", "Test",
                    "-process-dir", classpath,
            };
        }

        soot.Main.main(sootArgs);
    }
}
