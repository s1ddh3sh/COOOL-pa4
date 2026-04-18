package tests.Test1;

// Scenario: hot loop calls one abstract method while an alternate impl is also allocated.
// Expected here: mostly non-inlining callsites.

abstract class Op {
    abstract int apply(int x);
}

class Add extends Op {
    @Override
    int apply(int x) {
        return x + 3;
    }
}

class Mul extends Op {
    @Override
    int apply(int x) {
        return x * 3 + 1;
    }
}

class Engine {
    private final Op op;

    Engine(Op op) {
        this.op = op;
    }

    int run(int rounds) {
        int acc = 1;
        for (int i = 0; i < rounds; i++) {
            // Should NOT inline: receiver type can be Add or Mul in this test setup.
            acc = op.apply(acc + i);
            acc ^= acc << 5;
        }
        return acc;
    }
}

public class Test {
    private static volatile int sink;

    private static int repeat(Engine engine, int times, int rounds) {
        int out = 0;
        for (int i = 0; i < times; i++) {
            // Should NOT inline in our pass: caller/callee are different classes.
            out ^= engine.run(rounds);
        }
        return out;
    }

    public static void main(String[] args) {
        Op hot = new Add();
        Op noise = new Mul();
        int warm = noise.apply(7);

        Engine engine = new Engine(hot);
        sink = warm ^ repeat(engine, 20, 150000);
        System.out.println(sink);
    }
}
