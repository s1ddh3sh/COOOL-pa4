package src;

import soot.*;

public class AnalysisTransformer extends SceneTransformer {
    @Override
    protected void internalTransform(String phaseName, java.util.Map<String, String> options) {
        // Baseline mode: no optimization.
    }
}