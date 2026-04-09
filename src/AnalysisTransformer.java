package src;
import soot.jimple.spark.*;
import java.util.*;
import soot.*;
import soot.jimple.*;

public class AnalysisTransformer extends SceneTransformer {

    private static final Map<Unit, Set<Type>> resolvedType = new HashMap<>();

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
                        monomorph(stmt, expr);
                    }
                }
            }
        }
        // printResults();

    }

    private void monomorph(Stmt stmt, InvokeExpr expr) {
        Value base;
        if (expr instanceof VirtualInvokeExpr) {
            base = ((VirtualInvokeExpr) expr).getBase();
        } else {
            base = ((InterfaceInvokeExpr) expr).getBase();
        }
        if (!(base instanceof Local))
            return;

        

        // System.out.println(base.getType());
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