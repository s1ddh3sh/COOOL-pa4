package src;

import java.util.*;
import soot.*;
import soot.jimple.*;
import soot.toolkits.graph.BriefUnitGraph;
import soot.toolkits.graph.UnitGraph;
import src.PointsToTransformer.AllocSite;
import src.PointsToTransformer.PointsToState;

public class AnalysisTransformer extends SceneTransformer {

    private static final Map<Unit, Set<Type>> resolvedType = new HashMap<>();

    @Override
    public void internalTransform(String phaseName, Map<String, String> options) {

        for (SootClass cls : Scene.v().getApplicationClasses()) {
            for (SootMethod meth : cls.getMethods()) {
                if (!meth.isConcrete())
                    continue;
                Body body = meth.retrieveActiveBody();

                UnitGraph graph = new BriefUnitGraph(body);
                Map<Unit, Integer> unitToIndex = PointsToTransformer.buildUnitToIndex(body);
                Map<Unit, PointsToState> ptsIn = new HashMap<>();
                Map<Unit, PointsToState> ptsOut = new HashMap<>();
                PointsToTransformer.runPointsToAnalysis(graph, unitToIndex, ptsIn, ptsOut);

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
                        monomorph(ptsIn, stmt, expr);
                    }
                }
            }
        }
        printResults();

    }

    private void monomorph(Map<Unit, PointsToState> ptsIn, Stmt stmt, InvokeExpr expr) {
        Value base;
        if (expr instanceof VirtualInvokeExpr) {
            base = ((VirtualInvokeExpr) expr).getBase();
        } else {
            base = ((InterfaceInvokeExpr) expr).getBase();
        }
        if (!(base instanceof Local))
            return;

        Local receiver = (Local) base;
        // System.out.println(receiver);
        PointsToState state = ptsIn.get(stmt);
        if (state == null)
            return;
        Set<AllocSite> possibleTypes = state.getVar(receiver);
        if (possibleTypes == null)
            return;
        for (AllocSite a : possibleTypes) {
            if (!(a.unit instanceof AssignStmt))
                continue;
            AssignStmt st = (AssignStmt) a.unit;
            Value rhs = st.getRightOp();
            if (rhs instanceof NewExpr) {
                Type type = ((NewExpr) rhs).getType();
                // System.out.println(type);
                resolvedType.computeIfAbsent(stmt, k -> new HashSet<>()).add(type);
            }

        }

    }

    private void printResults() {

        for (Unit u : resolvedType.keySet()) {

            System.out.println("\nCall Site: " + u);

            System.out.println("Possible Types:");
            Set<Type> resolvedCalls = resolvedType.get(u);
            for (Type t : resolvedCalls) {
                System.out.println(" : " + t);
            }

            if (resolvedCalls.size() == 1) {
                System.out.println("monomorphic");
            } else {
                System.out.println("not monomorphic");
            }
        }
    }
}