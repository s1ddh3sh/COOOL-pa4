package src;

import soot.*;
import soot.options.Options;
import java.io.File;
import java.util.*;

public class Main {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err
                    .println("Usage: java -cp .:soot-4.6.0-jar-with-dependencies.jar src/Main <TestDir> [ALGO] [J|C]");
            return;
        }

        String testName = args[0];
        String algorithm = args.length >= 2 ? args[1].toUpperCase() : "BASELINE";
        String format = args.length >= 3 ? args[2].trim().toUpperCase() : "J";
        String outputFormat;
        if ("J".equals(format)) {
            outputFormat = "J";
        } else if ("C".equals(format)) {
            outputFormat = "c";
        } else {
            throw new IllegalArgumentException("Output format must be J or C");
        }

        String classpath;
        String processDir;
        String mainClass;

        File decapoDir = new File("./decapo/" + testName);
        if (decapoDir.exists() && decapoDir.isDirectory()) {
            processDir = "./decapo/" + testName;
            mainClass = "Harness";
            classpath = processDir;
        } else {
            processDir = "./tests/" + testName;
            mainClass = "tests." + testName + ".Test";
            classpath = ".";
        }

        String outputRoot = "./sootOutput/" + testName + "/" + algorithm;
        String baselineOutputDir = outputRoot + "/before";
        String transformedOutputDir = outputRoot + "/after";

        boolean isDaCapo = decapoDir.exists() && decapoDir.isDirectory();

        runSoot(classpath, processDir, mainClass, baselineOutputDir, false, algorithm, outputFormat, isDaCapo);
        runSoot(classpath, processDir, mainClass, transformedOutputDir, true, algorithm, outputFormat, isDaCapo);
    }

    private static void runSoot(
            String classpath,
            String processDir,
            String mainClass,
            String outputDir,
            boolean transformedPass,
            String algorithm,
            String outputFormat,
            boolean isDaCapo) {
        G.reset();

        Options.v().set_keep_line_number(true);
        Options.v().set_whole_program(true);

        boolean applyTransform = transformedPass
                && !"BASELINE".equals(algorithm)
                && !"NONE".equals(algorithm)
                && !"NOOPT".equals(algorithm);
        if (applyTransform) {
            SceneTransformer sceneTransformer = new AnalysisTransformer();
            PackManager.v().getPack("wjtp").add(new Transform("wjtp.dfa", sceneTransformer));
            System.out.println("[INFO] transformed output: " + outputDir);
        } else {
            System.out.println("[INFO] baseline output: " + outputDir);
        }

        List<String> argsList = new ArrayList<>(Arrays.asList(
                "-w",
                "-app",
                "-allow-phantom-refs",
                "-no-bodies-for-excluded",
                "-keep-line-number",
                "-f", outputFormat,
                "-d", outputDir,
                "-main-class", mainClass,
                "-process-dir", processDir));

        if (isDaCapo) {
            argsList.add("-soot-classpath");
            File dacapoJar = new File("./decapo/dacapo-9.12-MR1-bach.jar");
            if (dacapoJar.exists()) {
                argsList.add(classpath + ":" + dacapoJar.getPath());
            } else {
                argsList.add(classpath);
            }
            argsList.add("-prepend-classpath");

            File reflLog = new File(processDir + "/refl.log");
            if (reflLog.exists()) {
                argsList.add("-p");
                argsList.add("cg");
                argsList.add("reflection-log:" + reflLog.getPath());
            }

            argsList.add("-p");
            argsList.add("cg.spark");
            argsList.add("on");
            argsList.add("-ire");
            for (String pkg : new String[] { "org.apache.*", "org.dacapo.*", "jdt.*", "jdk.*", "java.*", "org.*",
                    "com.*" }) {
                argsList.add("-i");
                argsList.add(pkg);
            }
        } else {
            argsList.add("-cp");
            argsList.add(classpath);
            argsList.add("-pp");
            for (String pkg : new String[] { "java.*", "javax.*", "sun.*", "com.sun.*", "jdk.*" }) {
                argsList.add("-exclude");
                argsList.add(pkg);
            }
        }

        String[] sootArgs = argsList.toArray(new String[0]);
        soot.Main.main(sootArgs);
    }
}
