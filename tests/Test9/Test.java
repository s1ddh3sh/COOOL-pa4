package tests.Test9;

// Scenario: same-class helper chain builds a primary converter, then loop calls virtual cvt.
// Expected: helper calls inline, cvt call non-inline.

abstract class Converter {
    abstract int cvt(int x);
}

class PlusOne extends Converter {
    @Override
    int cvt(int x) {
        return x + 1;
    }
}

class TimesTwo extends Converter {
    @Override
    int cvt(int x) {
        return x * 2;
    }
}

class Noise {
    static Converter side;

    static void init() {
        side = new TimesTwo();
    }
}

class Pipeline {
    Converter id(Converter c) {
        return c;
    }

    Converter buildPrimary() {
        Converter c = new PlusOne();
        // Should inline: same-class helper call.
        return id(c);
    }

    int run(int rounds) {
        // Should inline: same-class helper call.
        Converter c = buildPrimary();
        int acc = 0;
        for (int i = 0; i < rounds; i++) {
            // Should NOT inline: virtual call on Converter.
            acc += c.cvt(i);
            acc ^= acc >>> 3;
        }
        return acc;
    }
}

public class Test {
    private static volatile int sink;

    public static void main(String[] args) {
        Noise.init();
        Pipeline p = new Pipeline();

        int total = 0;
        for (int i = 0; i < 14; i++) {
            total ^= p.run(170000);
        }

        sink = total;
        System.out.println(sink);
    }
}
