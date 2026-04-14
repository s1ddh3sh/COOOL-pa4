package src;
import java.util.*;
import soot.*;
import soot.jimple.*;

public class AnalysisTransformer extends SceneTransformer {

    private static final Map<Unit, Set<Type>> resolvedType = new HashMap<>();

    public static Map<Unit, Set<Type>> getResolvedTypes() {
        return new HashMap<>(resolvedType);
    }

    public static void printResults() {
        System.out.println("\n=== Monomorphism Analysis Results ===");
        int monomorphicCount = 0;
        int polymorphicCount = 0;
        
        for (Map.Entry<Unit, Set<Type>> entry : resolvedType.entrySet()) {
            Set<Type> types = entry.getValue();
            if (types.size() == 1) {
                monomorphicCount++;
                System.out.println("[MONOMORPHIC] " + entry.getKey() + " -> " + types.iterator().next());
            } else if (types.size() > 1) {
                polymorphicCount++;
                System.out.println("[POLYMORPHIC] " + entry.getKey() + " -> " + types);
            }
        }
        
        System.out.println("\nSummary:");
        System.out.println("  Monomorphic call sites: " + monomorphicCount);
        System.out.println("  Polymorphic call sites: " + polymorphicCount);
        System.out.println("  Total call sites analyzed: " + resolvedType.size());
    }

    @Override
    public void internalTransform(String phaseName, Map<String, String> options) {

        for (SootClass cls : Scene.v().getApplicationClasses()) {
            for (SootMethod meth : cls.getMethods()) {
                if (!meth.isConcrete())
                    continue;
                Body body = meth.retrieveActiveBody();
                for (Unit u : body.getUnits()) {
                    Stmt stmt = (Stmt) u;
                    if (!stmt.containsInvokeExpr())
                        continue;

                    InvokeExpr expr = stmt.getInvokeExpr();
                    if (expr instanceof VirtualInvokeExpr || expr instanceof InterfaceInvokeExpr) {
                        SootMethod target = expr.getMethod();
                        if (!target.getDeclaringClass().isApplicationClass()) {
                            continue;
                        }
                        monomorph(meth, stmt, expr);
                    }
                }
            }
        }
        printResults();

    }

    private void monomorph(SootMethod method, Stmt stmt, InvokeExpr expr) {
        Value base;
        if (expr instanceof VirtualInvokeExpr) {
            base = ((VirtualInvokeExpr) expr).getBase();
        } else {
            base = ((InterfaceInvokeExpr) expr).getBase();
        }
        if (!(base instanceof Local))
            return;

        Local baseLocal = (Local) base;

        // Get the points-to state at this statement
        PointsToTransformer.PointsToState ptsState = PointsToTransformer.getPointsToStateAtUnit(method, stmt);
        if (ptsState == null) {
            resolvedType.put(stmt, new HashSet<>());
            return;
        }

        Set<Type> possibleTypes = new HashSet<>();
        Set<PointsToTransformer.AllocSite> basePointsTo = ptsState.getVar(baseLocal);
        
        for (PointsToTransformer.AllocSite allocSite : basePointsTo) {
            Type type = PointsToTransformer.getTypeFromAllocSite(allocSite);
            if (type != null) {
                possibleTypes.add(type);
            }
        }

        resolvedType.put(stmt, possibleTypes);
    }

    // private void printResults() {

    // for (Unit u : possibleTypes.keySet()) {

    // System.out.println("\nCall Site: " + u);

    // System.out.println("Possible Types:");
    // for (Type t : possibleTypes.get(u)) {
    // System.out.println(" : " + t);
    // }

    // System.out.println("Resolved Targets:");
    // Set<SootMethod> targets = possibleTargets.get(u);
    // for (SootMethod m : targets) {
    // System.out.println(" : " + m.getSignature());
    // }

    // if (targets.size() == 1) {
    // System.out.println("monomorphic");
    // } else {
    // System.out.println("not monomorphic");
    // }
    // }
    // }
}