package src;

import java.util.*;
import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.invoke.SiteInliner;

public class AnalysisTransformer extends SceneTransformer {
    private final Set<SootClass> instantiatedClasses = new HashSet<>();
    private final Set<SootMethod> reachableMethods = new HashSet<>();
    private final Deque<SootMethod> worklist = new ArrayDeque<>();
    private final Map<SootMethod, List<CallSiteInfo>> callGraph = new LinkedHashMap<>();

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        Hierarchy hierarchy = Scene.v().getActiveHierarchy();

        for (SootClass cls : Scene.v().getApplicationClasses()) {
            for (SootMethod m : cls.getMethods()) {
                if (isEntryPoint(m)) enqueue(m);
            }
        }

        while (!worklist.isEmpty()) {
            SootMethod method = worklist.poll();
            processMethod(method, hierarchy);
        }

        printCallGraph();
    }

    private void processMethod(SootMethod method, Hierarchy hierarchy) {
        if (!method.isConcrete()) {
            return;
        }

        Body body = method.retrieveActiveBody();
        Map<Unit, SootMethod> monomorphicCalls = new LinkedHashMap<>();

        for (Unit unit : body.getUnits()) {
            if (!(unit instanceof AssignStmt)) {
                continue;
            }
            Value rhs = ((AssignStmt) unit).getRightOp();
            if (!(rhs instanceof NewExpr)) {
                continue;
            }

            SootClass allocated = ((RefType) ((NewExpr) rhs).getType()).getSootClass();
            if (instantiatedClasses.add(allocated)) {
                for (SootMethod visited : new ArrayList<>(reachableMethods)) {
                    if (!visited.equals(method)) {
                        enqueue(visited);
                    }
                }
            }
        }

        List<CallSiteInfo> sites = new ArrayList<>();

        for (Unit unit : body.getUnits()) {
            Stmt stmt = (Stmt) unit;
            if (!stmt.containsInvokeExpr()) {
                continue;
            }

            InvokeExpr invoke = stmt.getInvokeExpr();
            if (!(invoke instanceof VirtualInvokeExpr)
                    && !(invoke instanceof InterfaceInvokeExpr)) {
                SootMethod callee = invoke.getMethod();
                if (callee.getDeclaringClass().isApplicationClass()) {
                    enqueue(callee);
                }
                continue;
            }

            SootMethod declared = invoke.getMethod();
            SootClass declClass = declared.getDeclaringClass();
            if (!declClass.isApplicationClass()) {
                continue;
            }

            Set<SootMethod> targets = resolveRTA(hierarchy, invoke, declared, declClass);

            if (targets.size() == 1) {
                monomorphicCalls.put(stmt, targets.iterator().next());
            }

            for (SootMethod t : targets) {
                enqueue(t);
            }

            int line = stmt.getJavaSourceStartLineNumber();
            sites.add(new CallSiteInfo(line, declared.getName(), targets));
        }

        for (Map.Entry<Unit, SootMethod> entry : monomorphicCalls.entrySet()) {
            inlineMonomorphicCall(method, entry.getKey(), entry.getValue());
        }

        if (!sites.isEmpty()) {
            callGraph.put(method, sites);
        }
    }

    private void inlineMonomorphicCall(SootMethod caller, Unit callUnit, SootMethod target) {
        if (!(callUnit instanceof Stmt)) {
            return;
        }
        if (!canInline(caller, target)) {
            return;
        }

        Stmt stmt = (Stmt) callUnit;
        try {
            SiteInliner.inlineSite(target, stmt, caller);
        } catch (RuntimeException ignored) {
            // Keep the original call site when inlining is unsafe for this method.
        }
    }

    private boolean canInline(SootMethod caller, SootMethod target) {
        if (caller == null || target == null) {
            return false;
        }
        if (!target.isConcrete()) {
            return false;
        }
        String targetName = target.getName();
        if ("<init>".equals(targetName) || "<clinit>".equals(targetName)) {
            return false;
        }

        // Avoid illegal access after inlining private members across classes.
        return caller.getDeclaringClass().equals(target.getDeclaringClass());
    }

    // RTA resolution = CHA candidate set intersect instantiated classes.

    private Set<SootMethod> resolveRTA(
            Hierarchy hierarchy,
            InvokeExpr invoke,
            SootMethod declared,
            SootClass declaredClass) {

        Set<SootMethod> targets = new LinkedHashSet<>();

        List<SootClass> chaSubtypes = new ArrayList<>();
        if (invoke instanceof InterfaceInvokeExpr) {
            if (declaredClass.isInterface()) {
                for (SootClass impl : hierarchy.getImplementersOf(declaredClass)) {
                    addConcreteSubtypes(hierarchy, impl, chaSubtypes);
                }
            }
        } else {
            addConcreteSubtypes(hierarchy, declaredClass, chaSubtypes);
        }

        for (SootClass sub : chaSubtypes) {
            if (!instantiatedClasses.contains(sub)) {
                continue;
            }

            SootMethod resolved = dispatch(sub, declared);
            if (resolved != null) {
                targets.add(resolved);
            }
        }

        return targets;
    }

    private void addConcreteSubtypes(Hierarchy hierarchy, SootClass cls, List<SootClass> out) {
        if (!cls.isAbstract() && !cls.isInterface()) {
            out.add(cls);
        }
        for (SootClass sub : hierarchy.getSubclassesOf(cls)) {
            if (!sub.isAbstract() && !sub.isInterface()) {
                out.add(sub);
            }
        }
    }

    private SootMethod dispatch(SootClass cls, SootMethod declared) {
        String name = declared.getName();
        List<Type> params = declared.getParameterTypes();
        Type ret = declared.getReturnType();

        SootClass cur = cls;
        while (cur != null) {
            if (cur.declaresMethod(name, params, ret)) {
                return cur.getMethod(name, params, ret);
            }
            cur = cur.hasSuperclass() ? cur.getSuperclass() : null;
        }
        return null;
    }

    private boolean isEntryPoint(SootMethod m) {
        if (!m.isConcrete() || m.isPhantom()) {
            return false;
        }
        if (m.getName().equals("main") && m.isPublic() && m.isStatic()) {
            return true;
        }
        if (m.getName().equals("<clinit>")) {
            return true;
        }
        return false;
    }

    private void enqueue(SootMethod m) {
        if (m == null || !m.isConcrete() || m.isPhantom()) {
            return;
        }
        if (!m.getDeclaringClass().isApplicationClass()) {
            return;
        }
        if (reachableMethods.add(m)) {
            worklist.add(m);
        }
    }

    private void printCallGraph() {
        System.out.println("\n========== RTA Call Graph ==========");
        System.out.println("Instantiated classes: " + instantiatedClasses.stream()
                .map(SootClass::getShortName)
                .sorted()
                .reduce((a, b) -> a + ", " + b)
                .orElse("(none)"));
        System.out.println();

        if (callGraph.isEmpty()) {
            System.out.println("(no virtual/interface call sites found in reachable code)");
            return;
        }

        for (Map.Entry<SootMethod, List<CallSiteInfo>> entry : callGraph.entrySet()) {
            System.out.println("Caller: " + entry.getKey().getSignature());

            for (CallSiteInfo site : entry.getValue()) {
                System.out.print("  Line " + site.line + ": " + site.methodName + "() -> ");

                if (site.targets.isEmpty()) {
                    System.out.println("NO REACHABLE TARGETS (dead call site under RTA)");
                } else if (site.targets.size() == 1) {
                    System.out.println("MONOMORPHIC: "
                            + site.targets.iterator().next().getSignature());
                } else {
                    System.out.println("POLYMORPHIC (" + site.targets.size() + " targets):");
                    for (SootMethod t : site.targets) {
                        System.out.println("      " + t.getSignature());
                    }
                }
            }
            System.out.println();
        }
    }

    private static class CallSiteInfo {
        final int line;
        final String methodName;
        final Set<SootMethod> targets;

        CallSiteInfo(int line, String methodName, Set<SootMethod> targets) {
            this.line = line;
            this.methodName = methodName;
            this.targets = targets;
        }
    }
}