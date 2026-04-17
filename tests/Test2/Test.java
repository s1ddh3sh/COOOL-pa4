class A {
    int x;

    void foo(A a) {
        x += 2;
    }
}

class B extends A {

    void foo(A b) {
        b.x += 10;
        A p = new A(); // O10
        b = p;
        return;
    }
}

class C extends A {
    void foo(A c) {
        c.x -= 5;
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
        for (long j = 0; j < 100000000L; j++) {
            a.foo(b);
            b.foo(b);
        }
        System.out.println(a.x);
    }
}