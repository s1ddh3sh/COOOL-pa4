package src;

import java.util.*;
import soot.*;
import soot.jimple.*;

public class AnalysisTransformer extends SceneTransformer {

    // caller -> call sites seen in that method
    private final Map<SootMethod, List<CallSiteInfo>> callGraph = new LinkedHashMap<>();

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {

        Hierarchy hierarchy = Scene.v().getActiveHierarchy();

        for (SootClass cls : Scene.v().getApplicationClasses()) {
            for (SootMethod method : cls.getMethods()) {

                if (!method.isConcrete()) continue;

                Body body = method.retrieveActiveBody();

                for (Unit unit : body.getUnits()) {
                    Stmt stmt = (Stmt) unit;
                    if (!stmt.containsInvokeExpr()) continue;

                    InvokeExpr invoke = stmt.getInvokeExpr();

                        // CHA is only for dynamic dispatch
                    if (!(invoke instanceof VirtualInvokeExpr)
                            && !(invoke instanceof InterfaceInvokeExpr)) continue;

                    SootMethod declared = invoke.getMethod();
                        SootClass declaredClass = declared.getDeclaringClass();

                        // Ignore library calls
                    if (!declaredClass.isApplicationClass()) continue;

                        // Resolve possible targets with CHA
                    Set<SootMethod> targets = resolveCHA(hierarchy, invoke, declared, declaredClass);

                    int line = stmt.getJavaSourceStartLineNumber();
                    callGraph
                        .computeIfAbsent(method, k -> new ArrayList<>())
                        .add(new CallSiteInfo(line, declared.getName(), targets));
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

        // Collect concrete receiver types.
        List<SootClass> concreteSubtypes = new ArrayList<>();

        if (invoke instanceof InterfaceInvokeExpr) {
            // Interface call: all implementers and their subclasses.
            if (declaredClass.isInterface()) {
                for (SootClass impl : hierarchy.getImplementersOf(declaredClass)) {
                    addConcreteSubtypes(hierarchy, impl, concreteSubtypes);
                }
            }
        } else {
            // Virtual call: declared class and subclasses.
            addConcreteSubtypes(hierarchy, declaredClass, concreteSubtypes);
        }

        // Dispatch from each concrete subtype.
        Set<SootMethod> targets = new LinkedHashSet<>();
        for (SootClass sub : concreteSubtypes) {
            SootMethod resolved = dispatch(sub, declared);
            if (resolved != null) targets.add(resolved);
        }

        return targets;
    }

    // Add cls (if concrete) and all concrete subclasses.
    private void addConcreteSubtypes(Hierarchy hierarchy, SootClass cls, List<SootClass> out) {
        if (!cls.isAbstract() && !cls.isInterface()) out.add(cls);
        for (SootClass sub : hierarchy.getSubclassesOf(cls)) {
            if (!sub.isAbstract() && !sub.isInterface()) out.add(sub);
        }
    }

    // Walk up the hierarchy and return the first matching declaration.
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