class A {
    int v = 0;
    void doWork() { 
        v++; 
    }
}

class B extends A {
    @Override
    void doWork() { 
        v += 10; 
    }
}

class C extends A {
    @Override
    void doWork() { 
        v -= 5; 
    }
}

public class Test {
    public static void main(String[] args) {
        A a1 = new B();
        A a2 = new C();
        A a3 = new B();

        // Loop preserving monomorphism.
        for (int i = 0; i < 50000000; i++) {
            a1.doWork(); // Monomorphic B
            a3.doWork(); // Monomorphic B
        }

        // Loop degrading to polymorphism.
        A poly = new B();
        for (int i = 0; i < 50000000; i++) {
            poly.doWork(); // Polymorphic because poly could be B or C
            if (i == 50) {
                poly = new C();
            }
        }
    }
}
