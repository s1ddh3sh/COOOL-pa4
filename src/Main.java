package src;

import soot.*;
import soot.options.Options;

public class Main {
    public static void main(String[] args) {
        String classpath = "./tests/" + args[0];

        Options.v().set_keep_line_number(true);
        Options.v().set_whole_program(true);

        BodyTransformer pointsToTransformer = new PointsToTransformer();
        PackManager.v().getPack("jtp").add(new Transform("jtp.pta", pointsToTransformer));

        // Register monomorphism analysis (SceneTransformer - runs on whole program)
        SceneTransformer sceneTransformer = new AnalysisTransformer();
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.dfa", sceneTransformer));

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
                "-f", "J",
                "-t", "1",
                "-main-class", "Test",
                "-process-dir", classpath,

        };
        soot.Main.main(sootArgs);
    }
}
