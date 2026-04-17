package src;

import java.util.*;

import soot.*;
import soot.jimple.*;
import soot.toolkits.graph.BriefUnitGraph;
import soot.toolkits.graph.UnitGraph;
import src.Interprocedural.AllocSite;
import src.Interprocedural.PointsToState;

public class AnalysisTransformer extends SceneTransformer {

    private final Map<Unit, Set<Type>> resolvedType = new HashMap<>();

    @Override
    public void internalTransform(String phaseName, Map<String, String> options) {
        Interprocedural.cg = Scene.v().getCallGraph();
        for (SootClass cls : Scene.v().getApplicationClasses()) {
            for (SootMethod meth : cls.getMethods()) {
                if (!meth.isConcrete())
                    continue;
                Body body = meth.retrieveActiveBody();

                Map<Unit, Set<Type>> localResolved = new HashMap<>();

                UnitGraph graph = new BriefUnitGraph(body);
                // Map<Unit, Integer> unitToIndex = PointsToTransformer.buildUnitToIndex(body);
                Map<Unit, PointsToState> ptsIn = new HashMap<>();
                Map<Unit, PointsToState> ptsOut = new HashMap<>();
                Interprocedural.runPointsToAnalysis(graph, ptsIn, ptsOut, meth, false);

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
                        monomorph(ptsIn, stmt, expr, localResolved);
                    }
                }

                resolvedType.putAll(localResolved);

                for (Map.Entry<Unit, Set<Type>> entry : localResolved.entrySet()) {
                    if (entry.getValue().size() == 1) {
                        transformInvoke(body, entry.getKey(), entry.getValue().iterator().next());
                    }
                }

                MethodInline.inline(meth);
            }
        }
        // printResults();

    }

    private void monomorph(Map<Unit, PointsToState> ptsIn, Stmt stmt, InvokeExpr expr,
            Map<Unit, Set<Type>> localResolved) {
        Value base;
        if (expr instanceof VirtualInvokeExpr) {
            base = ((VirtualInvokeExpr) expr).getBase();
        } else {
            base = ((InterfaceInvokeExpr) expr).getBase();
        }
        if (!(base instanceof Local))
            return;

        Local receiver = (Local) base;
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
                localResolved.computeIfAbsent(stmt, k -> new HashSet<>()).add(type);
            }
        }
    }

    private void transformInvoke(Body body, Unit callUnit, Type resolvedType) {
        if (!(callUnit instanceof Stmt))
            return;

        Stmt stmt = (Stmt) callUnit;
        if (!stmt.containsInvokeExpr())
            return;

        InvokeExpr expr = stmt.getInvokeExpr();
        if (!(expr instanceof VirtualInvokeExpr) && !(expr instanceof InterfaceInvokeExpr))
            return;

        Local base;
        if (expr instanceof VirtualInvokeExpr) {
            base = (Local) ((VirtualInvokeExpr) expr).getBase();
        } else {
            base = (Local) ((InterfaceInvokeExpr) expr).getBase();
        }
        if (!(resolvedType instanceof RefType))
            return;

        SootClass cls = ((RefType) resolvedType).getSootClass();
        SootMethod meth = expr.getMethod();

        SootMethod target = cls.getMethod(meth.getName(), meth.getParameterTypes(), meth.getReturnType());
        
        Local castedBase = Jimple.v().newLocal("casted_" + base.getName(), RefType.v(cls));
        body.getLocals().add(castedBase);
        Unit castStmt = Jimple.v().newAssignStmt(castedBase, Jimple.v().newCastExpr(base, RefType.v(cls)));

        SpecialInvokeExpr specialExpr = Jimple.v().newSpecialInvokeExpr(castedBase, target.makeRef(), expr.getArgs());

        Unit sub;
        if (stmt instanceof AssignStmt) {
            Value lhs = ((AssignStmt) stmt).getLeftOp();
            sub = Jimple.v().newAssignStmt(lhs, specialExpr);
        } else {
            sub = Jimple.v().newInvokeStmt(specialExpr);
        }
        body.getUnits().insertBefore(castStmt, callUnit);
        body.getUnits().swapWith(callUnit, sub);
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