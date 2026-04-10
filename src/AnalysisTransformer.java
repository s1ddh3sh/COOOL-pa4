package src;

import src.InterProcedural.*;
import java.util.*;
import soot.*;
import soot.jimple.*;
import soot.toolkits.graph.BriefUnitGraph;
import soot.toolkits.graph.UnitGraph;

public class AnalysisTransformer extends SceneTransformer {

    private static final Map<Unit, Set<Type>> resolvedType = new HashMap<>();

    @Override
    public void internalTransform(String phaseName, Map<String, String> options) {
        InterProcedural.cg = Scene.v().getCallGraph();
        for (SootClass cls : Scene.v().getApplicationClasses()) {
            for (SootMethod method : cls.getMethods()) {
                if (!method.isConcrete())
                    continue;
                Body body = method.retrieveActiveBody();

                UnitGraph graph = new BriefUnitGraph(body);
                Map<Unit, PointsToState> ptsIn = new HashMap<>();
                Map<Unit, PointsToState> ptsOut = new HashMap<>();
                InterProcedural.runPointsToAnalysis(graph, ptsIn, ptsOut, method, false);

                // Body body = meth.retrieveActiveBody();
                for (Unit u : body.getUnits()) {
                    Stmt stmt = (Stmt) u;
                    if (!stmt.containsInvokeExpr())
                        continue;

                    InvokeExpr expr = stmt.getInvokeExpr();
                    if (expr instanceof VirtualInvokeExpr || expr instanceof InterfaceInvokeExpr) {
                        SootMethod target = expr.getMethod();
                        if (!InterProcedural.shouldAnalyze(target)) {
                            continue;
                        }
                        System.out.println(expr);
                        monomorph(stmt, expr, ptsIn);
                    }
                }
            }
        }
        printResults();

    }

    private void monomorph(Stmt stmt, InvokeExpr expr, Map<Unit, PointsToState> ptsIn) {
        Value base;
        if (expr instanceof VirtualInvokeExpr) {
            base = ((VirtualInvokeExpr) expr).getBase();
        } else {
            base = ((InterfaceInvokeExpr) expr).getBase();
        }
        if (!(base instanceof Local))
            return;

        PointsToState state = ptsIn.get(stmt);
        if (state == null)
            return;

        Set<AllocSite> pts = state.getValue().getVar((Local) base);
        if (!pts.contains(InterProcedural.UNKNOWN_ALLOC)) {
            for (AllocSite site : pts) {
                Unit unit = site.unit;
                AssignStmt as = (AssignStmt) unit;
                Value rhs = as.getRightOp();
                NewExpr exp = (NewExpr) rhs;
                Type t = exp.getType();
                resolvedType.computeIfAbsent((Unit) stmt, k -> new HashSet<>()).add(t);
            }

        }

        // System.out.println(base.getType());
    }

    private void printResults() {

        for (Unit u : resolvedType.keySet()) {

            System.out.println("\nCall Site: " + u);

            System.out.println("Possible Types:");
            Set<Type> types = resolvedType.get(u);
            for (Type t : types) {
                System.out.println(" : " + t);
            }

            if (types.size() == 1) {
                System.out.println("monomorphic");
            } else {
                System.out.println("not monomorphic");
            }
        }
    }
}