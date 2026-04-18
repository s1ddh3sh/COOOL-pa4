package tests.Test5;

// Scenario: warm up one cold implementation, then run a hot task in a loop.
// Expected: task virtual calls remain non-inline.

abstract class Task {
    abstract int run(int x);
}

class Hot extends Task {
    @Override
    int run(int x) {
        return (x * 9) + 1;
    }
}

class Cold extends Task {
    @Override
    int run(int x) {
        return (x * 13) - 5;
    }
}

class Boot {
    static int touchColdPath() {
        Task t = new Cold();
        // Should NOT inline: virtual call through Task reference.
        return t.run(3);
    }
}

class Bench {
    int execute(Task t, int rounds) {
        int acc = 1;
        for (int i = 0; i < rounds; i++) {
            // Should NOT inline: virtual dispatch on Task.
            acc ^= t.run(i);
            acc += acc >>> 1;
        }
        return acc;
    }
}

public class Test {
    private static volatile int sink;

    private static int repeat(Bench bench, Task task, int times, int rounds) {
        int out = 0;
        for (int i = 0; i < times; i++) {
            out ^= bench.execute(task, rounds);
        }
        return out;
    }

    public static void main(String[] args) {
        int warm = Boot.touchColdPath();
        Bench bench = new Bench();
        Task hot = new Hot();
        sink = warm ^ repeat(bench, hot, 8, 300000);
        System.out.println(sink);
    }
}
