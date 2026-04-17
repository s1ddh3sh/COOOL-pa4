class A {
    int x = 0;

    void foo() {
        x += 1;
    }
}

class B extends A {
    @Override
    void foo() {
        x += 2;
    }
}

class C extends A {
    @Override
    void foo() {
        x -= 1;
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

    // static void callFoo(A a) {
    //     a.foo();
    // }

    static void case5() {
        A b = new B();
        b.foo();
    }

    static void case6(boolean flag) {
        A a;
        if (flag) {
            a = new B();
        } else {
            a = new C();
        }
        a.foo();
    }

    public static void main(String[] args) {
        for (long j = 0; j < 50000000L; j++) {
            case1();
            case2(true);
            case3();
            case4(false);
            case5();
            case6(true);
        }
    }
}