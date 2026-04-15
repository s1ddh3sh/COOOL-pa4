package src;

import java.util.*;
import soot.*;
import soot.jimple.*;

/**
 * Variable Type Analysis (VTA).
 *
 * This version tracks types per variable, not as one global set like RTA.
 *
 * High-level flow:
 * 1) Build a type propagation graph (locals/fields/returns as nodes).
 * 2) Propagate allocation types to a fixpoint.
 * 3) Use propagated receiver types to resolve virtual/interface call targets.
 */
public class AnalysisTransformer extends SceneTransformer {

    // TPG edges: src -> dst nodes
    private final Map<Node, Set<Node>> tpg = new HashMap<>();

    // Concrete classes that can flow to each node
    private final Map<Node, Set<SootClass>> typeMap = new HashMap<>();

    // Output call graph grouped by caller method
    private final Map<SootMethod, List<CallSiteInfo>> callGraph = new LinkedHashMap<>();

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        Hierarchy hierarchy = Scene.v().getActiveHierarchy();

        // Phase 1: build graph
        for (SootClass cls : Scene.v().getApplicationClasses()) {
            for (SootMethod method : cls.getMethods()) {
                if (method.isConcrete()) buildTPG(method, hierarchy);
            }
        }

        // Phase 2: propagate types
        propagate();

        // Phase 3: resolve call sites
        for (SootClass cls : Scene.v().getApplicationClasses()) {
            for (SootMethod method : cls.getMethods()) {
                if (method.isConcrete()) resolveCallSites(method, hierarchy);
            }
        }

        printCallGraph();
    }

    // Build graph edges from statements

    private void buildTPG(SootMethod method, Hierarchy hierarchy) {
        Body body = method.retrieveActiveBody();

        for (Unit unit : body.getUnits()) {
            if (!(unit instanceof Stmt)) continue;
            Stmt stmt = (Stmt) unit;

            // return x
            if (stmt instanceof ReturnStmt) {
                Value op = ((ReturnStmt) stmt).getOp();
                if (op instanceof Local)
                    addEdge(localNode(method, (Local) op), retNode(method));
                continue;
            }

            // x = ...
            if (stmt instanceof AssignStmt) {
                processAssign((AssignStmt) stmt, method, hierarchy);
                continue;
            }

            // invoke with no lhs (void call)
            if (stmt.containsInvokeExpr())
                addCallEdges(stmt.getInvokeExpr(), null, method, hierarchy);
        }
    }

    private void processAssign(AssignStmt stmt, SootMethod method, Hierarchy hierarchy) {
        Value lhs = stmt.getLeftOp();
        Value rhs = stmt.getRightOp();

        // x = new T()
        if (rhs instanceof NewExpr) {
            if (lhs instanceof Local) {
                SootClass allocated = ((RefType) ((NewExpr) rhs).getType()).getSootClass();
                seedType(localNode(method, (Local) lhs), allocated);
            }
            return;
        }

        // Map lhs to a graph node
        Node lhsNode = null;
        if      (lhs instanceof Local)           lhsNode = localNode(method, (Local) lhs);
        else if (lhs instanceof InstanceFieldRef) lhsNode = fieldNode(((InstanceFieldRef) lhs).getField());
        else if (lhs instanceof StaticFieldRef)   lhsNode = fieldNode(((StaticFieldRef)   lhs).getField());

        if (lhsNode == null) return; // skip array refs etc.

        // Add flow from rhs to lhs
        if (rhs instanceof Local) {
            addEdge(localNode(method, (Local) rhs), lhsNode);

        } else if (rhs instanceof CastExpr) {
            // treat cast as copy flow
            Value op = ((CastExpr) rhs).getOp();
            if (op instanceof Local) addEdge(localNode(method, (Local) op), lhsNode);

        } else if (rhs instanceof InstanceFieldRef) {
            addEdge(fieldNode(((InstanceFieldRef) rhs).getField()), lhsNode);

        } else if (rhs instanceof StaticFieldRef) {
            addEdge(fieldNode(((StaticFieldRef) rhs).getField()), lhsNode);

        } else if (rhs instanceof InvokeExpr) {
            // x = call(...)
            addCallEdges((InvokeExpr) rhs, lhs instanceof Local ? (Local) lhs : null, method, hierarchy);
        }
        // primitives/constants do not add reference-type flow
    }

    /**
     * Adds argument and return edges for one call site.
     * retLocal is null for invoke statements without assignment.
     */
    private void addCallEdges(InvokeExpr invoke, Local retLocal,
                              SootMethod caller, Hierarchy hierarchy) {

        List<SootMethod> targets = new ArrayList<>();

        if (invoke instanceof VirtualInvokeExpr || invoke instanceof InterfaceInvokeExpr) {
            SootMethod declared  = invoke.getMethod();
            SootClass  declClass = declared.getDeclaringClass();
            if (declClass.isApplicationClass())
                targets.addAll(resolveCHA(hierarchy, invoke, declared, declClass));

        } else if (invoke instanceof StaticInvokeExpr || invoke instanceof SpecialInvokeExpr) {
            SootMethod callee = invoke.getMethod();
            if (callee.getDeclaringClass().isApplicationClass() && callee.isConcrete())
                targets.add(callee);
        }

        for (SootMethod target : targets) {
            if (!target.isConcrete()) continue;

            List<Value> args         = invoke.getArgs();
            List<Local> formalParams = target.retrieveActiveBody().getParameterLocals();

            // actual arg -> formal parameter
            int n = Math.min(args.size(), formalParams.size());
            for (int i = 0; i < n; i++) {
                if (args.get(i) instanceof Local)
                    addEdge(localNode(caller, (Local) args.get(i)),
                            localNode(target, formalParams.get(i)));
            }

            // return value -> lhs in caller
            if (retLocal != null)
                addEdge(retNode(target), localNode(caller, retLocal));
        }
    }

    // Worklist propagation to fixpoint

    private void propagate() {
        Deque<Node> worklist = new ArrayDeque<>();

        // seed with nodes that already have types (from allocations)
        for (Map.Entry<Node, Set<SootClass>> e : typeMap.entrySet()) {
            if (!e.getValue().isEmpty()) worklist.add(e.getKey());
        }

        while (!worklist.isEmpty()) {
            Node src = worklist.poll();
            Set<SootClass> srcTypes = typeMap.getOrDefault(src, Collections.emptySet());
            if (srcTypes.isEmpty()) continue;

            for (Node dst : tpg.getOrDefault(src, Collections.emptySet())) {
                Set<SootClass> dstTypes = typeMap.computeIfAbsent(dst, k -> new HashSet<>());
                // enqueue only when dst grows
                if (dstTypes.addAll(srcTypes)) worklist.add(dst);
            }
        }
    }

    // Resolve virtual/interface call targets from receiver types

    private void resolveCallSites(SootMethod method, Hierarchy hierarchy) {
        Body body = method.retrieveActiveBody();
        List<CallSiteInfo> sites = new ArrayList<>();

        for (Unit unit : body.getUnits()) {
            Stmt stmt = (Stmt) unit;
            if (!stmt.containsInvokeExpr()) continue;

            InvokeExpr invoke = stmt.getInvokeExpr();
            if (!(invoke instanceof VirtualInvokeExpr)
                    && !(invoke instanceof InterfaceInvokeExpr)) continue;

            SootMethod declared  = invoke.getMethod();
            SootClass  declClass = declared.getDeclaringClass();
            if (!declClass.isApplicationClass()) continue;

            Value base = (invoke instanceof VirtualInvokeExpr)
                    ? ((VirtualInvokeExpr)   invoke).getBase()
                    : ((InterfaceInvokeExpr) invoke).getBase();
            if (!(base instanceof Local)) continue;

            // VTA lookup for receiver variable
            Node           receiverNode  = localNode(method, (Local) base);
            Set<SootClass> receiverTypes = typeMap.getOrDefault(receiverNode, Collections.emptySet());

            Set<SootMethod> targets = new LinkedHashSet<>();
            for (SootClass cls : receiverTypes) {
                if (cls.isAbstract() || cls.isInterface())      continue;
                if (!isSubtype(hierarchy, cls, declClass))       continue;
                SootMethod resolved = dispatch(cls, declared);
                if (resolved != null) targets.add(resolved);
            }

            int line = stmt.getJavaSourceStartLineNumber();
            sites.add(new CallSiteInfo(line, declared.getName(), targets));
        }

        if (!sites.isEmpty()) callGraph.put(method, sites);
    }

    // Helpers

    /** Add directed edge src -> dst. */
    private void addEdge(Node src, Node dst) {
        tpg.computeIfAbsent(src, k -> new HashSet<>()).add(dst);
    }

    /** Seed one concrete type into a node. */
    private void seedType(Node n, SootClass cls) {
        typeMap.computeIfAbsent(n, k -> new HashSet<>()).add(cls);
    }

    /** CHA helper used while adding call edges. */
    private Set<SootMethod> resolveCHA(Hierarchy hierarchy, InvokeExpr invoke,
                                       SootMethod declared, SootClass declaredClass) {
        List<SootClass> concreteSubtypes = new ArrayList<>();
        if (invoke instanceof InterfaceInvokeExpr) {
            if (declaredClass.isInterface()) {
                for (SootClass impl : hierarchy.getImplementersOf(declaredClass))
                    addConcreteSubtypes(hierarchy, impl, concreteSubtypes);
            }
        } else {
            addConcreteSubtypes(hierarchy, declaredClass, concreteSubtypes);
        }
        Set<SootMethod> targets = new LinkedHashSet<>();
        for (SootClass sub : concreteSubtypes) {
            SootMethod m = dispatch(sub, declared);
            if (m != null) targets.add(m);
        }
        return targets;
    }

    private void addConcreteSubtypes(Hierarchy hierarchy, SootClass cls, List<SootClass> out) {
        if (!cls.isAbstract() && !cls.isInterface()) out.add(cls);
        for (SootClass sub : hierarchy.getSubclassesOf(cls))
            if (!sub.isAbstract() && !sub.isInterface()) out.add(sub);
    }

    /** Standard virtual dispatch search up the class chain. */
    private SootMethod dispatch(SootClass cls, SootMethod declared) {
        String     name   = declared.getName();
        List<Type> params = declared.getParameterTypes();
        Type       ret    = declared.getReturnType();
        SootClass  cur    = cls;
        while (cur != null) {
            if (cur.declaresMethod(name, params, ret))
                return cur.getMethod(name, params, ret);
            cur = cur.hasSuperclass() ? cur.getSuperclass() : null;
        }
        return null;
    }

    /** Inclusive subtype check. */
    private boolean isSubtype(Hierarchy hierarchy, SootClass sub, SootClass sup) {
        if (sub.equals(sup)) return true;
        try {
            if (sup.isInterface()) {
                for (SootClass impl : hierarchy.getImplementersOf(sup)) {
                    if (impl.equals(sub) || hierarchy.getSubclassesOf(impl).contains(sub))
                        return true;
                }
                return false;
            }
            return hierarchy.getSuperclassesOfIncluding(sub).contains(sup);
        } catch (Exception e) {
            return false; // phantom/unresolved class
        }
    }

    // Node factories

    private Node localNode(SootMethod m, Local l) { return new LocalNode(m, l); }
    private Node fieldNode(SootField  f)           { return new FieldNode(f);    }
    private Node retNode  (SootMethod m)           { return new RetNode(m);      }

    // Output

    private void printCallGraph() {
        System.out.println("\n========== VTA Call Graph ==========\n");

        if (callGraph.isEmpty()) {
            System.out.println("(no virtual/interface call sites found in application classes)");
            return;
        }

        for (Map.Entry<SootMethod, List<CallSiteInfo>> entry : callGraph.entrySet()) {
            System.out.println("Caller: " + entry.getKey().getSignature());

            for (CallSiteInfo site : entry.getValue()) {
                System.out.print("  Line " + site.line + ": " + site.methodName + "() -> ");

                if (site.targets.isEmpty()) {
                    // No matching receiver type reaches this call.
                    System.out.println("NO REACHABLE TARGETS (dead call site under VTA)");
                } else if (site.targets.size() == 1) {
                    System.out.println("MONOMORPHIC: "
                            + site.targets.iterator().next().getSignature());
                } else {
                    System.out.println("POLYMORPHIC (" + site.targets.size() + " targets):");
                    for (SootMethod t : site.targets)
                        System.out.println("      " + t.getSignature());
                }
            }
            System.out.println();
        }
    }

    // Node types

    /** Base class for all TPG nodes. */
    abstract static class Node {}

    /** A local variable inside a specific method. */
    static final class LocalNode extends Node {
        final SootMethod method;
        final Local      local;

        LocalNode(SootMethod method, Local local) {
            this.method = method;
            this.local  = local;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof LocalNode)) return false;
            LocalNode n = (LocalNode) o;
            return Objects.equals(method, n.method) && Objects.equals(local, n.local);
        }

        @Override public int hashCode() { return Objects.hash(method, local); }

        @Override public String toString() {
            return "Local(" + method.getName() + "::" + local.getName() + ")";
        }
    }

    /**
        * A field (instance or static).
        * Field-insensitive: all instances of the same SootField share one node.
     */
    static final class FieldNode extends Node {
        final SootField field;

        FieldNode(SootField field) { this.field = field; }

        @Override public boolean equals(Object o) {
            return o instanceof FieldNode && Objects.equals(field, ((FieldNode) o).field);
        }

        @Override public int hashCode() { return Objects.hash(field); }

        @Override public String toString() { return "Field(" + field.getName() + ")"; }
    }

    /** The return-value slot of a method. */
    static final class RetNode extends Node {
        final SootMethod method;

        RetNode(SootMethod method) { this.method = method; }

        @Override public boolean equals(Object o) {
            return o instanceof RetNode && Objects.equals(method, ((RetNode) o).method);
        }

        @Override public int hashCode() { return Objects.hash(method); }

        @Override public String toString() { return "Ret(" + method.getName() + ")"; }
    }

    private static final class CallSiteInfo {
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