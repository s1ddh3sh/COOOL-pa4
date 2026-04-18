package tests.Test2;

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
        return side.step(1);
    }
}

class Runner {
    Stepper newHotStepper() {
        return new Inc();
    }

    int run(int n) {
        Stepper s = newHotStepper();
        int acc = 0;
        for (int i = 0; i < n; i++) {
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
