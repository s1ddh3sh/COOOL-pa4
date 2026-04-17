class Base {
    long value = 0;
    void compute() { 
        value += 2; 
    }
}

class Derived extends Base {
    @Override
    void compute() { 
        value += 5; 
    }
}

class Wrapper {
    Base obj;
}

public class Test {
    public static void main(String[] args) {
        Wrapper w1 = new Wrapper();
        w1.obj = new Derived(); // Field points to Derived
        
        Wrapper w2 = new Wrapper();
        w2.obj = new Base();

        Wrapper w3 = new Wrapper();
        if (args.length > 5) {
            w3.obj = new Derived();
        } else {
            w3.obj = new Base();
        }

        for (long i = 0; i < 50000000L; i++) {
            w1.obj.compute(); // Monomorphic
            w2.obj.compute(); // Monomorphic
            w3.obj.compute(); // Polymorphic
        }
    }
}
