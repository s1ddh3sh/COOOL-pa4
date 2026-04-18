package tests.Test2;

// Scenario: one helper call is monomorphic, but loop body uses a virtual step call.
// Expected: inline helper call, keep step call as non-inline.

abstract class Stepper {
    abstract int step(int x);
}

class Inc extends Stepper {
    @Override
    int step(int x) {
        return x + 1;
    }
}

class Dec extends Stepper {
    @Override
    int step(int x) {
        return x - 1;
    }
}

class Noise {
    static Stepper side = new Dec();

    static int warmup() {
        // Should NOT inline: virtual call on Stepper and not a same-class helper call.
        return side.step(1);
    }
}

class Runner {
    Stepper newHotStepper() {
        return new Inc();
    }

    int run(int n) {
        // Should inline: same-class helper call with one concrete target here.
        Stepper s = newHotStepper();
        int acc = 0;
        for (int i = 0; i < n; i++) {
            // Should NOT inline: virtual dispatch on Stepper in the hot loop.
            acc += s.step(i);
            acc ^= acc << 2;
        }
        return acc;
    }
}

public class Test {
    private static volatile int sink;

    private static int repeat(Runner runner, int times, int rounds) {
        int out = 0;
        for (int i = 0; i < times; i++) {
            out ^= runner.run(rounds);
        }
        return out;
    }

    public static void main(String[] args) {
        int warm = Noise.warmup();
        Runner runner = new Runner();
        sink = warm ^ repeat(runner, 14, 200000);
        System.out.println(sink);
    }
}
