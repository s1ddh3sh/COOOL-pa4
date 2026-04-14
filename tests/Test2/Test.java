class A {
    int x;

    void foo(A a) {
        System.out.println(x);
    }
}

class B extends A {

    void foo(A b) {
        b.x = 10;
        A p = new A(); // O10
        b = p;
        return;
    }
}

class C extends A {
    void foo(A c) {
        System.out.println(c.x);
    }
}

class Test {
    public static void main(String[] args) {
        A a = new A(); // O19
        A b;
        int i = 2;
        if (i == 2) {
            b = new B(); // O20
        } else {
            b = new C();
        }
        a.foo(b);
        b.foo(b);
    }
}