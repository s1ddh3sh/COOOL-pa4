package src;

import java.util.*;
import soot.*;
import soot.jimple.*;

/**
 * RTA (Rapid Type Analysis) — Transformer.
 *
 * RTA refines CHA with one extra constraint:
 *   A virtual target T.m() is only considered reachable if class T has been
 *   instantiated somewhere in the REACHABLE program.
 *
 * Algorithm (fixpoint worklist):
 *   1. Seed the worklist with every static entry point found in application classes
 *      (main(), static initialisers, etc.).
 *   2. For each newly reachable method:
 *        a. Scan every `new T()` statement  → add T to instantiatedClasses.
 *        b. For every virtual/interface call site, intersect CHA targets with
 *           instantiatedClasses → these are the RTA targets.
 *        c. Any RTA target not yet seen → add it to the worklist.
 *   3. Repeat until the worklist is empty (fixpoint).
 *   4. Print the final call graph.
 *
 * Precision gain over CHA:
 *   CHA: receiver type A, subtypes {A, B, C}  → always 3 targets (even if B/C are never new'd)
 *   RTA: same setup, but if only `new B()` appears in reachable code → 1 target → MONOMORPHIC
 */
public class AnalysisTransformer extends SceneTransformer {

    // ── RTA state ─────────────────────────────────────────────────────────────

    /** Every class for which a `new` expression has been seen in reachable code. */
    private final Set<SootClass> instantiatedClasses = new HashSet<>();

    /** Methods that have been (or are queued to be) processed. */
    private final Set<SootMethod> reachableMethods = new HashSet<>();

    /** Worklist of methods yet to be scanned. */
    private final Deque<SootMethod> worklist = new ArrayDeque<>();

    /** Final call-graph result: caller → ordered list of call sites. */
    private final Map<SootMethod, List<CallSiteInfo>> callGraph = new LinkedHashMap<>();

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {

        Hierarchy hierarchy = Scene.v().getActiveHierarchy();

        // ── Step 1: seed with static entry points ────────────────────────────
        for (SootClass cls : Scene.v().getApplicationClasses()) {
            for (SootMethod m : cls.getMethods()) {
                if (isEntryPoint(m)) enqueue(m);
            }
        }

        // ── Step 2: fixpoint worklist ─────────────────────────────────────────
        while (!worklist.isEmpty()) {
            SootMethod method = worklist.poll();
            processMethod(method, hierarchy);
        }

        // ── Step 3: report ────────────────────────────────────────────────────
        printCallGraph();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core: scan one method
    // ─────────────────────────────────────────────────────────────────────────

    private void processMethod(SootMethod method, Hierarchy hierarchy) {
        if (!method.isConcrete()) return;

        Body body = method.retrieveActiveBody();

        // First pass: harvest all `new T()` allocation sites.
        // Adding new classes to instantiatedClasses may unlock previously
        // unresolvable call sites in already-processed methods; those are
        // handled by the second pass below.
        boolean newClassFound = false;
        for (Unit unit : body.getUnits()) {
            if (!(unit instanceof AssignStmt)) continue;
            Value rhs = ((AssignStmt) unit).getRightOp();
            if (!(rhs instanceof NewExpr)) continue;

            SootClass allocated = ((RefType) ((NewExpr) rhs).getType()).getSootClass();
            if (instantiatedClasses.add(allocated)) {
                newClassFound = true;
                // A newly instantiated class may unlock targets in ALL already-
                // visited methods. Re-enqueue them so their call sites are
                // re-evaluated under the updated instantiated set.
                for (SootMethod visited : new ArrayList<>(reachableMethods)) {
                    if (!visited.equals(method)) enqueue(visited);
                }
            }
        }

        // Second pass: resolve call sites using the (possibly updated) instantiated set.
        List<CallSiteInfo> sites = new ArrayList<>();

        for (Unit unit : body.getUnits()) {
            Stmt stmt = (Stmt) unit;
            if (!stmt.containsInvokeExpr()) continue;

            InvokeExpr invoke = stmt.getInvokeExpr();

            // RTA only tightens dynamic dispatch; static/special calls are exact.
            if (!(invoke instanceof VirtualInvokeExpr)
                    && !(invoke instanceof InterfaceInvokeExpr)) {
                // Still enqueue statically-known callees so they are processed.
                SootMethod callee = invoke.getMethod();
                if (callee.getDeclaringClass().isApplicationClass()) enqueue(callee);
                continue;
            }

            SootMethod declared   = invoke.getMethod();
            SootClass  declClass  = declared.getDeclaringClass();
            if (!declClass.isApplicationClass()) continue;

            // ── RTA resolution ───────────────────────────────────────────────
            Set<SootMethod> targets = resolveRTA(hierarchy, invoke, declared, declClass);
            // ─────────────────────────────────────────────────────────────────

            // Every resolved target is now reachable — enqueue if new.
            for (SootMethod t : targets) enqueue(t);

            int line = stmt.getJavaSourceStartLineNumber();
            sites.add(new CallSiteInfo(line, declared.getName(), targets));
        }

        // Replace the stored record so re-processed methods always reflect the
        // latest instantiated set.
        if (!sites.isEmpty()) callGraph.put(method, sites);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RTA resolution  =  CHA candidate set  ∩  instantiatedClasses
    // ─────────────────────────────────────────────────────────────────────────

    private Set<SootMethod> resolveRTA(
            Hierarchy hierarchy,
            InvokeExpr invoke,
            SootMethod declared,
            SootClass  declaredClass) {

        Set<SootMethod> targets = new LinkedHashSet<>();

        // Build the CHA candidate list, then filter by instantiatedClasses.
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
            // ── RTA filter: only consider classes actually instantiated ───────
            if (!instantiatedClasses.contains(sub)) continue;
            // ─────────────────────────────────────────────────────────────────

            SootMethod resolved = dispatch(sub, declared);
            if (resolved != null) targets.add(resolved);
        }

        return targets;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Add {@code cls} and all concrete subclasses to {@code out}. */
    private void addConcreteSubtypes(Hierarchy hierarchy, SootClass cls, List<SootClass> out) {
        if (!cls.isAbstract() && !cls.isInterface()) out.add(cls);
        for (SootClass sub : hierarchy.getSubclassesOf(cls)) {
            if (!sub.isAbstract() && !sub.isInterface()) out.add(sub);
        }
    }

    /** Virtual dispatch: walk up hierarchy until the method is declared. */
    private SootMethod dispatch(SootClass cls, SootMethod declared) {
        String     name   = declared.getName();
        List<Type> params = declared.getParameterTypes();
        Type       ret    = declared.getReturnType();

        SootClass cur = cls;
        while (cur != null) {
            if (cur.declaresMethod(name, params, ret))
                return cur.getMethod(name, params, ret);
            cur = cur.hasSuperclass() ? cur.getSuperclass() : null;
        }
        return null;
    }

    /**
     * Entry points: main(String[]), static initialisers, and any public static
     * method in an application class (conservative seed for library-style code).
     */
    private boolean isEntryPoint(SootMethod m) {
        if (!m.isConcrete() || m.isPhantom()) return false;
        if (m.getName().equals("main") && m.isPublic() && m.isStatic()) return true;
        if (m.getName().equals("<clinit>"))                               return true;
        return false;
    }

    /** Add {@code m} to the worklist only if it has not been seen before. */
    private void enqueue(SootMethod m) {
        if (m == null || !m.isConcrete() || m.isPhantom()) return;
        if (!m.getDeclaringClass().isApplicationClass())    return;
        if (reachableMethods.add(m)) worklist.add(m);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Output
    // ─────────────────────────────────────────────────────────────────────────

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
                    // RTA produces empty sets when a call's receiver type has
                    // no instantiated subtypes in reachable code.
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

    // ─────────────────────────────────────────────────────────────────────────

    private static class CallSiteInfo {
        final int             line;
        final String          methodName;
        final Set<SootMethod> targets;

        CallSiteInfo(int line, String methodName, Set<SootMethod> targets) {
            this.line       = line;
            this.methodName = methodName;
            this.targets    = targets;
        }
    }
}