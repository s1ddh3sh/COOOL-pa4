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

class Test {
    public static void main(String[] args) {
        A a = new A(); // O19
        B b = new B(); // O20
        a.foo(b);
        b.foo(b);
    }
}