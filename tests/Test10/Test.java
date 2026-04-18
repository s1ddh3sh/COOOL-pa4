package tests.Test10;

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
