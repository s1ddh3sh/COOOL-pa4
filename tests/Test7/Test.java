package tests.Test7;

// Scenario: holder keeps strategy in a field and calls it in a hot loop.
// Expected: strategy call stays non-inline.

abstract class Strategy {
    abstract int act(int x);
}

class StrategyA extends Strategy {
    @Override
    int act(int x) {
        return x + 5;
    }
}

class StrategyB extends Strategy {
    @Override
    int act(int x) {
        return x * 4;
    }
}

class Holder {
    private Strategy strategy;

    Holder() {
        strategy = new StrategyA();
    }

    int run(int rounds) {
        int acc = 0;
        for (int i = 0; i < rounds; i++) {
            // Should NOT inline: virtual call through Strategy field.
            acc += strategy.act(i);
            acc ^= acc >>> 2;
        }
        return acc;
    }
}

public class Test {
    private static volatile int sink;

    public static void main(String[] args) {
        Holder h = new Holder();
        int total = 0;
        for (int i = 0; i < 12; i++) {
            total ^= h.run(180000);
        }
        sink = total;
        System.out.println(sink);
    }
}
