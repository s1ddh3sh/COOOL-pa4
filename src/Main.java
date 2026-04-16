package src;

import soot.*;
import soot.options.Options;

public class Main {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java -cp .:soot-4.6.0-jar-with-dependencies.jar src/Main <TestDir> [ALGO] [J|C]");
            return;
        }

        String testName = args[0];
        String algorithm = args.length >= 2 ? args[1].toUpperCase() : "BASELINE";
        String outputFormat = parseOutputFormat(args.length >= 3 ? args[2] : "J");

        String classpath = "./tests/" + testName;
        String outputRoot = "./sootOutput/" + testName + "/" + algorithm;
        String baselineOutputDir = outputRoot + "/before";
        String transformedOutputDir = outputRoot + "/after";

        runSoot(classpath, baselineOutputDir, false, algorithm, outputFormat);
        runSoot(classpath, transformedOutputDir, true, algorithm, outputFormat);
    }

    private static String parseOutputFormat(String raw) {
        String format = raw == null ? "J" : raw.trim().toUpperCase();
        if ("J".equals(format)) {
            return "J";
        }
        if ("C".equals(format)) {
            return "c";
        }
        throw new IllegalArgumentException("Output format must be J or C");
    }

    private static boolean isBaseline(String algorithm) {
        return "BASELINE".equals(algorithm) || "NONE".equals(algorithm) || "NOOPT".equals(algorithm);
    }

    private static void runSoot(String classpath, String outputDir, boolean transformedPass, String algorithm, String outputFormat) {
        G.reset();

        Options.v().set_keep_line_number(true);
        Options.v().set_whole_program(true);

        boolean applyTransform = transformedPass && !isBaseline(algorithm);
        if (applyTransform) {
            SceneTransformer sceneTransformer = new AnalysisTransformer();
            PackManager.v().getPack("wjtp").add(new Transform("wjtp.dfa", sceneTransformer));
            System.out.println("[INFO] transformed output: " + outputDir);
        } else {
            System.out.println("[INFO] baseline output: " + outputDir);
        }

        String[] sootArgs = {
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
        soot.Main.main(sootArgs);
    }
}
