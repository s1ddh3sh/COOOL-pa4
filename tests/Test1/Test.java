package tests.Test1;

class A {
    void foo() {
        System.out.println("A.foo");
    }
}

class B extends A {
    @Override
    void foo() {
        System.out.println("B.foo");
    }
}

class C extends A {
    @Override
    void foo() {
        System.out.println("C.foo");
    }
}

public class Test {

    static void case1() {
        A a = new B();
        a.foo(); // monomorphized : B.foo()
    }

    static void case2(boolean flag) {
        A a;
        if (flag) {
            a = new B();
        } else {
            a = new C();
        }
        a.foo(); // cannot be monomorphized
    }

    static void case3() {
        A a = new B();
        a = new B();
        a.foo(); // monomorphic : B.foo()
    }

    static void case4(boolean flag) {
        A a = new B();
        if (flag) {
            a = new C();
        }
        a.foo(); // polymorphic (B or C)
    }

    static void callFoo(A a) {
        a.foo();
    }

    static void case5() {
        A b = new B();
        callFoo(b);
    }

    static void case6(boolean flag) {
        A a;
        if (flag) {
            a = new B();
        } else {
            a = new C();
        }
        callFoo(a);
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