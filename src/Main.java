package src;

import soot.*;
import soot.options.Options;

public class Main {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java src.Main <test-directory>");
            System.exit(1);
        }

        String classpath = "./tests/" + args[0];

        // Keep source line numbers and enable whole-program analysis.
        Options.v().set_keep_line_number(true);
        Options.v().set_whole_program(true);

        // Use CHA for call graph construction.
        Options.v().setPhaseOption("cg", "enabled:true");
        Options.v().setPhaseOption("cg.cha", "enabled:true");

        // Register our transformer in wjtp.
        PackManager.v().getPack("wjtp").add(
                new Transform("wjtp.cha", new AnalysisTransformer()));

        soot.Main.main(new String[]{
            "-cp", classpath,
            "-pp", // prepend default Soot classpath
            "-w", // whole-program mode
            "-app", // analyze application classes only
                "-allow-phantom-refs",
                "-no-bodies-for-excluded",
                "-exclude", "java.*",
                "-exclude", "javax.*",
                "-exclude", "sun.*",
                "-exclude", "com.sun.*",
                "-exclude", "jdk.*",
            "-f", "J", // output Jimple (use "none" to skip)
                "-main-class", "Test",
                "-process-dir", classpath,
        });
    }
}