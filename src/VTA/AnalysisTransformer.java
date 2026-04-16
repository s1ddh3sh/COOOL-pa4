package src;

import java.util.*;
import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.invoke.SiteInliner;

public class AnalysisTransformer extends SceneTransformer {
    private final Map<Node, Set<Node>> tpg = new HashMap<>();
    private final Map<Node, Set<SootClass>> typeMap = new HashMap<>();
    private final Map<SootMethod, List<CallSiteInfo>> callGraph = new LinkedHashMap<>();

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        Hierarchy hierarchy = Scene.v().getActiveHierarchy();

        for (SootClass cls : Scene.v().getApplicationClasses()) {
            for (SootMethod method : cls.getMethods()) {
                if (method.isConcrete()) buildTPG(method, hierarchy);
            }
        }

        propagate();

        for (SootClass cls : Scene.v().getApplicationClasses()) {
            for (SootMethod method : cls.getMethods()) {
                if (method.isConcrete()) resolveCallSites(method, hierarchy);
            }
        }

        printCallGraph();
    }

    private void buildTPG(SootMethod method, Hierarchy hierarchy) {
        Body body = method.retrieveActiveBody();

        for (Unit unit : body.getUnits()) {
            if (!(unit instanceof Stmt)) continue;
            Stmt stmt = (Stmt) unit;

            if (stmt instanceof ReturnStmt) {
                Value op = ((ReturnStmt) stmt).getOp();
                if (op instanceof Local)
                    addEdge(localNode(method, (Local) op), retNode(method));
                continue;
            }

            if (stmt instanceof AssignStmt) {
                processAssign((AssignStmt) stmt, method, hierarchy);
                continue;
            }

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

        Node lhsNode = null;
        if      (lhs instanceof Local)           lhsNode = localNode(method, (Local) lhs);
        else if (lhs instanceof InstanceFieldRef) lhsNode = fieldNode(((InstanceFieldRef) lhs).getField());
        else if (lhs instanceof StaticFieldRef)   lhsNode = fieldNode(((StaticFieldRef)   lhs).getField());

        if (lhsNode == null) return;

        if (rhs instanceof Local) {
            addEdge(localNode(method, (Local) rhs), lhsNode);

        } else if (rhs instanceof CastExpr) {
            Value op = ((CastExpr) rhs).getOp();
            if (op instanceof Local) addEdge(localNode(method, (Local) op), lhsNode);

        } else if (rhs instanceof InstanceFieldRef) {
            addEdge(fieldNode(((InstanceFieldRef) rhs).getField()), lhsNode);

        } else if (rhs instanceof StaticFieldRef) {
            addEdge(fieldNode(((StaticFieldRef) rhs).getField()), lhsNode);

        } else if (rhs instanceof InvokeExpr) {
            addCallEdges((InvokeExpr) rhs, lhs instanceof Local ? (Local) lhs : null, method, hierarchy);
        }
    }

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

            int n = Math.min(args.size(), formalParams.size());
            for (int i = 0; i < n; i++) {
                if (args.get(i) instanceof Local)
                    addEdge(localNode(caller, (Local) args.get(i)),
                            localNode(target, formalParams.get(i)));
            }

            if (retLocal != null)
                addEdge(retNode(target), localNode(caller, retLocal));
        }
    }

    private void propagate() {
        Deque<Node> worklist = new ArrayDeque<>();

        for (Map.Entry<Node, Set<SootClass>> e : typeMap.entrySet()) {
            if (!e.getValue().isEmpty()) worklist.add(e.getKey());
        }

        while (!worklist.isEmpty()) {
            Node src = worklist.poll();
            Set<SootClass> srcTypes = typeMap.getOrDefault(src, Collections.emptySet());
            if (srcTypes.isEmpty()) continue;

            for (Node dst : tpg.getOrDefault(src, Collections.emptySet())) {
                Set<SootClass> dstTypes = typeMap.computeIfAbsent(dst, k -> new HashSet<>());
                if (dstTypes.addAll(srcTypes)) worklist.add(dst);
            }
        }
    }

    private void resolveCallSites(SootMethod method, Hierarchy hierarchy) {
        Body body = method.retrieveActiveBody();
        List<CallSiteInfo> sites = new ArrayList<>();
        Map<Unit, SootMethod> monomorphicCalls = new LinkedHashMap<>();

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

            Node           receiverNode  = localNode(method, (Local) base);
            Set<SootClass> receiverTypes = typeMap.getOrDefault(receiverNode, Collections.emptySet());

            Set<SootMethod> targets = new LinkedHashSet<>();
            for (SootClass cls : receiverTypes) {
                if (cls.isAbstract() || cls.isInterface())      continue;
                if (!isSubtype(hierarchy, cls, declClass))       continue;
                SootMethod resolved = dispatch(cls, declared);
                if (resolved != null) targets.add(resolved);
            }

            if (targets.size() == 1) {
                monomorphicCalls.put(stmt, targets.iterator().next());
            }

            int line = stmt.getJavaSourceStartLineNumber();
            sites.add(new CallSiteInfo(line, declared.getName(), targets));
        }

        for (Map.Entry<Unit, SootMethod> entry : monomorphicCalls.entrySet()) {
            transformInvoke(body, entry.getKey(), entry.getValue());
        }

        inlineSpecialInvokes(method);

        if (!sites.isEmpty()) callGraph.put(method, sites);
    }

    private void transformInvoke(Body body, Unit callUnit, SootMethod target) {
        if (!(callUnit instanceof Stmt)) return;

        Stmt stmt = (Stmt) callUnit;
        if (!stmt.containsInvokeExpr()) return;

        InvokeExpr expr = stmt.getInvokeExpr();
        if (!(expr instanceof VirtualInvokeExpr) && !(expr instanceof InterfaceInvokeExpr)) return;

        Local base;
        if (expr instanceof VirtualInvokeExpr) {
            base = (Local) ((VirtualInvokeExpr) expr).getBase();
        } else {
            base = (Local) ((InterfaceInvokeExpr) expr).getBase();
        }

        SpecialInvokeExpr specialExpr = Jimple.v().newSpecialInvokeExpr(base, target.makeRef(), expr.getArgs());

        Unit replacement;
        if (stmt instanceof AssignStmt) {
            Value lhs = ((AssignStmt) stmt).getLeftOp();
            replacement = Jimple.v().newAssignStmt(lhs, specialExpr);
        } else {
            replacement = Jimple.v().newInvokeStmt(specialExpr);
        }
        body.getUnits().swapWith(callUnit, replacement);
    }

    private void inlineSpecialInvokes(SootMethod method) {
        if (!method.isConcrete()) return;

        List<Stmt> toInline = new ArrayList<>();
        Body body = method.retrieveActiveBody();

        for (Unit unit : body.getUnits()) {
            Stmt stmt = (Stmt) unit;
            if (!stmt.containsInvokeExpr()) continue;
            if (stmt.getInvokeExpr() instanceof SpecialInvokeExpr) {
                toInline.add(stmt);
            }
        }

        for (Stmt stmt : toInline) {
            SootMethod target = stmt.getInvokeExpr().getMethod();
            if (!target.isConcrete()) continue;
            String targetName = target.getName();
            if ("<init>".equals(targetName) || "<clinit>".equals(targetName)) continue;
            SiteInliner.inlineSite(target, stmt, method);
        }
    }

    private void addEdge(Node src, Node dst) {
        tpg.computeIfAbsent(src, k -> new HashSet<>()).add(dst);
    }

    private void seedType(Node n, SootClass cls) {
        typeMap.computeIfAbsent(n, k -> new HashSet<>()).add(cls);
    }

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
            return false;
        }
    }

    private Node localNode(SootMethod m, Local l) { return new LocalNode(m, l); }
    private Node fieldNode(SootField  f)           { return new FieldNode(f);    }
    private Node retNode  (SootMethod m)           { return new RetNode(m);      }

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

    abstract static class Node {}

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

    static final class FieldNode extends Node {
        final SootField field;

        FieldNode(SootField field) { this.field = field; }

        @Override public boolean equals(Object o) {
            return o instanceof FieldNode && Objects.equals(field, ((FieldNode) o).field);
        }

        @Override public int hashCode() { return Objects.hash(field); }

        @Override public String toString() { return "Field(" + field.getName() + ")"; }
    }

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