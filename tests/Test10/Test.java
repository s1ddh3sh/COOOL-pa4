package tests.Test10;

// Scenario: one mono-looking loop and one clearly polymorphic loop over three stages.
// Expected: apply call in mono loop is still non-inline in this pass; poly loop is non-inline too.

abstract class Stage {
    abstract int apply(int x);
}

class Stage1 extends Stage {
    @Override
    int apply(int x) {
        return x + 2;
    }
}

class Stage2 extends Stage {
    @Override
    int apply(int x) {
        return x * 3;
    }
}

class Stage3 extends Stage {
    @Override
    int apply(int x) {
        return x ^ 0x77;
    }
}

class Program {
    int runMono(Stage s, int rounds) {
        int acc = 0;
        for (int i = 0; i < rounds; i++) {
            // Should NOT inline: call goes through Stage reference.
            acc += s.apply(i);
            acc ^= acc << 2;
        }
        return acc;
    }

    int runPoly(Stage a, Stage b, Stage c, int rounds) {
        int acc = 0;
        for (int i = 0; i < rounds; i++) {
            Stage s;
            int mod = i % 3;
            if (mod == 0) {
                s = a;
            } else if (mod == 1) {
                s = b;
            } else {
                s = c;
            }
            // Should NOT inline: this site is intentionally polymorphic.
            acc += s.apply(i);
            acc ^= acc >>> 1;
        }
        return acc;
    }
}

public class Test {
    private static volatile int sink;

    public static void main(String[] args) {
        Stage s1 = new Stage1();
        Stage s2 = new Stage2();
        Stage s3 = new Stage3();

        Program p = new Program();
        int a = p.runMono(s1, 1300000);
        int b = p.runPoly(s1, s2, s3, 1300000);

        sink = a ^ b;
        System.out.println(sink);
    }
}
