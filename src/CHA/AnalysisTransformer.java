package src;

import java.util.*;
import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.invoke.SiteInliner;

public class AnalysisTransformer extends SceneTransformer {
    private final Map<SootMethod, List<CallSiteInfo>> callGraph = new LinkedHashMap<>();

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        Hierarchy hierarchy = Scene.v().getActiveHierarchy();

        for (SootClass cls : Scene.v().getApplicationClasses()) {
            for (SootMethod method : cls.getMethods()) {
                if (!method.isConcrete()) continue;

                Body body = method.retrieveActiveBody();
                Map<Unit, SootMethod> monomorphicCalls = new LinkedHashMap<>();
                List<CallSiteInfo> sites = new ArrayList<>();

                for (Unit unit : body.getUnits()) {
                    Stmt stmt = (Stmt) unit;
                    if (!stmt.containsInvokeExpr()) continue;

                    InvokeExpr invoke = stmt.getInvokeExpr();
                    if (!(invoke instanceof VirtualInvokeExpr) && !(invoke instanceof InterfaceInvokeExpr)) continue;

                    SootMethod declared = invoke.getMethod();
                    SootClass declaredClass = declared.getDeclaringClass();
                    if (!declaredClass.isApplicationClass()) continue;

                    Set<SootMethod> targets = resolveCHA(hierarchy, invoke, declared, declaredClass);
                    if (targets.size() == 1) {
                        monomorphicCalls.put(stmt, targets.iterator().next());
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
        }

        printCallGraph();
    }

    private Set<SootMethod> resolveCHA(
            Hierarchy hierarchy,
            InvokeExpr invoke,
            SootMethod declared,
            SootClass declaredClass) {
        List<SootClass> concreteSubtypes = new ArrayList<>();

        if (invoke instanceof InterfaceInvokeExpr) {
            if (declaredClass.isInterface()) {
                for (SootClass impl : hierarchy.getImplementersOf(declaredClass)) {
                    addConcreteSubtypes(hierarchy, impl, concreteSubtypes);
                }
            }
        } else {
            addConcreteSubtypes(hierarchy, declaredClass, concreteSubtypes);
        }

        Set<SootMethod> targets = new LinkedHashSet<>();
        for (SootClass sub : concreteSubtypes) {
            SootMethod resolved = dispatch(sub, declared);
            if (resolved != null) targets.add(resolved);
        }

        return targets;
    }

    private void addConcreteSubtypes(Hierarchy hierarchy, SootClass cls, List<SootClass> out) {
        if (!cls.isAbstract() && !cls.isInterface()) out.add(cls);
        for (SootClass sub : hierarchy.getSubclassesOf(cls)) {
            if (!sub.isAbstract() && !sub.isInterface()) out.add(sub);
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

    private void inlineMonomorphicCall(SootMethod caller, Unit callUnit, SootMethod target) {
        if (!(callUnit instanceof Stmt)) return;
        if (!canInline(caller, target)) return;

        Stmt stmt = (Stmt) callUnit;
        try {
            SiteInliner.inlineSite(target, stmt, caller);
        } catch (RuntimeException ignored) {
            // Keep the original call site when inlining is unsafe for this method.
        }
    }

    private boolean canInline(SootMethod caller, SootMethod target) {
        if (caller == null || target == null) return false;
        if (!target.isConcrete()) return false;
        String targetName = target.getName();
        if ("<init>".equals(targetName) || "<clinit>".equals(targetName)) return false;

        // Avoid illegal access after inlining private members across classes.
        return caller.getDeclaringClass().equals(target.getDeclaringClass());
    }

    private void printCallGraph() {
        System.out.println("\n========== CHA Call Graph ==========\n");

        if (callGraph.isEmpty()) {
            System.out.println("(no virtual / interface call sites found in application classes)");
            return;
        }

        for (Map.Entry<SootMethod, List<CallSiteInfo>> entry : callGraph.entrySet()) {
            System.out.println("Caller: " + entry.getKey().getSignature());

            for (CallSiteInfo site : entry.getValue()) {
                System.out.print("  Line " + site.line + ": " + site.methodName + "() -> ");

                if (site.targets.isEmpty()) {
                    System.out.println("NO TARGETS FOUND");
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