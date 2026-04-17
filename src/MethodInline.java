package src;

import java.util.*;
import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.invoke.SiteInliner;

public class MethodInline {
    public static void inline(SootMethod method) {
        if (!method.isConcrete())
            return;
        List<Stmt> toInline = new ArrayList<>();
        Body body = method.retrieveActiveBody();
        for (Unit u : body.getUnits()) {
            Stmt stmt = (Stmt) u;

            if (!stmt.containsInvokeExpr())
                continue;

            InvokeExpr expr = stmt.getInvokeExpr();

            if (expr instanceof SpecialInvokeExpr) {
                if (expr.getMethod().getName().equals("<init>")) {
                    continue;
                }
                toInline.add(stmt);
            }
        }

        for (Stmt stmt : toInline) {
            SootMethod target = stmt.getInvokeExpr().getMethod();
            if (!target.isConcrete())
                continue;

            SiteInliner.inlineSite(target, stmt, method);
        }
    }
}
