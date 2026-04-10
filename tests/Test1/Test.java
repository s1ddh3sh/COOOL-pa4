// ─── Class hierarchy ────────────────────────────────────────────────────────
//
//   A  (concrete)
//   ├── B  (concrete, overrides foo)
//   └── C  (concrete, overrides foo)
//
//  CHA rule: for any receiver of declared type T, every concrete subtype of T
//  that provides the method is a possible target — regardless of what value is
//  actually stored there at runtime.
// ────────────────────────────────────────────────────────────────────────────

class A {
    void foo() { System.out.println("A.foo"); }
}
class B extends A {
    @Override void foo() { System.out.println("B.foo"); }
}
class C extends A {
    @Override void foo() { System.out.println("C.foo"); }
}

public class Test {

    // ── case1 ────────────────────────────────────────────────────────────────
    // The only allocation is `new B()`, so the receiver can only ever be B.
    // A flow-sensitive points-to analysis sees a singleton points-to set {B}
    // and can safely devirtualise to B.foo().
    //
    // CHA  : POLYMORPHIC — declared type is A, subtypes {A, B, C} all have foo()
    // Ideal: MONOMORPHIC  — points-to set is {B} → B.foo()
    static void case1() {
        A a = new B();
        a.foo(); // CHA: poly {A.foo, B.foo, C.foo} | Ideal: mono → B.foo()
    }

    // ── case2 ────────────────────────────────────────────────────────────────
    // Both branches assign a different concrete type, so at the call site the
    // points-to set is {B, C} regardless of the path taken.  No analysis —
    // flow-sensitive or otherwise — can reduce this to a single target.
    //
    // CHA  : POLYMORPHIC — same reasoning as case1
    // Ideal: POLYMORPHIC — two distinct allocation sites reach the call site
    static void case2(boolean flag) {
        A a;
        if (flag) { a = new B(); }
        else      { a = new C(); }
        a.foo(); // CHA: poly {A.foo, B.foo, C.foo} | Ideal: poly {B.foo, C.foo}
    }

    // ── case3 ────────────────────────────────────────────────────────────────
    // The first `new B()` is immediately overwritten by a second `new B()`.
    // Flow-sensitivity kills the first site; the live points-to set at the call
    // is still {B}.  Identical to case1 from an analysis standpoint.
    //
    // CHA  : POLYMORPHIC — declared type A covers {A, B, C}
    // Ideal: MONOMORPHIC  — only the second allocation reaches foo(); → B.foo()
    static void case3() {
        A a = new B(); // this allocation is killed by the next line
        a = new B();
        a.foo(); // CHA: poly {A.foo, B.foo, C.foo} | Ideal: mono → B.foo()
    }

    // ── case4 ────────────────────────────────────────────────────────────────
    // `a` starts as B but may be overwritten with C inside the branch.
    // On the path where `flag` is false, the call sees {B}.
    // On the path where `flag` is true,  the call sees {C}.
    // A flow-sensitive join at the call site merges both paths → {B, C}.
    //
    // CHA  : POLYMORPHIC — declared type A covers {A, B, C}
    // Ideal: POLYMORPHIC — merged points-to set is {B, C}; two targets remain
    static void case4(boolean flag) {
        A a = new B();
        if (flag) { a = new C(); }
        a.foo(); // CHA: poly {A.foo, B.foo, C.foo} | Ideal: poly {B.foo, C.foo}
    }

    // ── callFoo ──────────────────────────────────────────────────────────────
    // Analysed context-insensitively, the formal parameter `a` merges every
    // argument passed across ALL call sites (case5 → B, case6 → B|C), giving
    // a points-to set of {B, C} — polymorphic.
    //
    // With context-sensitivity (e.g. clone the method per call site):
    //   • clone for case5 → points-to {B}  → MONOMORPHIC B.foo()
    //   • clone for case6 → points-to {B,C} → POLYMORPHIC
    //
    // CHA  : POLYMORPHIC — declared parameter type A covers {A, B, C}
    // Ideal (context-insensitive): POLYMORPHIC — {B, C} merged across callers
    // Ideal (context-sensitive)  : depends on call site (see case5 / case6)
    static void callFoo(A a) {
        a.foo(); // CHA: poly {A.foo, B.foo, C.foo} | Ideal ctx-insens: poly {B.foo, C.foo}
                 //                                  | Ideal ctx-sens  : see call sites below
    }

    // ── case5 ────────────────────────────────────────────────────────────────
    // Only `new B()` is passed to callFoo here.  With context-sensitivity the
    // specialised clone of callFoo receives a points-to set of {B}, making the
    // inner call monomorphic.  Context-insensitive analysis cannot exploit this.
    //
    // CHA  : POLYMORPHIC at the a.foo() inside callFoo (see above)
    // Ideal (context-sensitive clone for this site): MONOMORPHIC → B.foo()
    static void case5() {
        A b = new B();
        callFoo(b); // indirect call site; monomorphisability depends on callFoo analysis context
    }

    // ── case6 ────────────────────────────────────────────────────────────────
    // Either a B or a C reaches callFoo depending on `flag`.
    // Even with full context-sensitivity, the clone for this call site sees
    // {B, C} and cannot devirtualise the inner foo() call.
    //
    // CHA  : POLYMORPHIC at the a.foo() inside callFoo
    // Ideal (context-sensitive clone for this site): POLYMORPHIC — {B.foo, C.foo}
    static void case6(boolean flag) {
        A a;
        if (flag) { a = new B(); }
        else      { a = new C(); }
        callFoo(a); // indirect call site; polymorphic regardless of context sensitivity
    }

    public static void main(String[] args) {
        case1();
        case2(true);
        case3();
        case4(false);
        case5();
        case6(true);
    }
}