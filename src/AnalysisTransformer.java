package src;

import java.util.*;
import soot.*;
import soot.jimple.*;

public class AnalysisTransformer extends SceneTransformer {

    private static final Map<Unit, Set<Type>> possibleTypes = new HashMap<>();
    private static final Map<Unit, Set<SootMethod>> possibleTargets = new HashMap<>();

    @Override
    public void internalTransform(String phaseName, Map<String, String> options) {
        PointsToAnalysis pta = Scene.v().getPointsToAnalysis();

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
                        // monomorphize the call
                        monomorph(stmt, expr, pta);
                    }
                }
            }
        }
        printResults();

    }

    private void monomorph(Stmt stmt, InvokeExpr expr, PointsToAnalysis pta) {
        Value base;
        if (expr instanceof VirtualInvokeExpr) {
            base = ((VirtualInvokeExpr) expr).getBase();
        } else {
            base = ((InterfaceInvokeExpr) expr).getBase();
        }

        if (!(base instanceof Local))
            return;
        PointsToSet pts = pta.reachingObjects((Local) base);

        Set<Type> types = new HashSet<>(pts.possibleTypes());
        Set<SootMethod> targets = new HashSet<>();

        for (Type t : types) {
            if (t instanceof RefType) {
                SootClass cls = ((RefType) t).getSootClass();
                SootMethod m = resolveMethod(cls, expr.getMethod());
                if (m != null)
                    targets.add(m);
            } 
            // else if (t instanceof AnySubType) {
            //     RefType baseType = ((AnySubType) t).getBase();
            //     SootClass baseClass = baseType.getSootClass();

            //     SootMethod m = resolveMethod(baseClass, expr.getMethod());
            //     if (m != null)
            //         targets.add(m);

            //     for (SootClass sub : Scene.v().getActiveHierarchy().getSubclassesOf(baseClass)) {
            //         SootMethod subM = resolveMethod(sub, expr.getMethod());
            //         if (subM != null)
            //             targets.add(subM);
            //     }
            // }
        }
        possibleTypes.put(stmt, types);
        possibleTargets.put(stmt, targets);
    }

    private SootMethod resolveMethod(SootClass cls, SootMethod method) {

        String subSig = method.getSubSignature();
        SootClass current = cls;

        while (current != null) {

            if (current.declaresMethod(subSig)) {
                SootMethod m = current.getMethod(subSig);

                if (!m.isAbstract()) {
                    return m;
                }
            }

            if (!current.hasSuperclass())
                break;
            current = current.getSuperclass();
        }

        return null;
    }

    private void printResults() {

        for (Unit u : possibleTypes.keySet()) {

            System.out.println("\nCall Site: " + u);

            System.out.println("Possible Types:");
            for (Type t : possibleTypes.get(u)) {
                System.out.println(" : " + t);
            }

            System.out.println("Resolved Targets:");
            Set<SootMethod> targets = possibleTargets.get(u);
            for (SootMethod m : targets) {
                System.out.println(" : " + m.getSignature());
            }

            if (targets.size() == 1) {
                System.out.println("monomorphic");
            } else {
                System.out.println("not monomorphic");
            }
        }
    }
}