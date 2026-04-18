package tests.Test6;

abstract class Kernel {
    abstract int eval(int x);
}

class K1 extends Kernel {
    @Override
    int eval(int x) {
        return x + 31;
    }
}

class K2 extends Kernel {
    @Override
    int eval(int x) {
        return x * 2 - 7;
    }
}

class K3 extends Kernel {
    @Override
    int eval(int x) {
        return x ^ 0x1234;
    }
}

class Loop {
    int run(Kernel[] kernels, int rounds) {
        int acc = 0;
        for (int i = 0; i < rounds; i++) {
            Kernel k = kernels[i % kernels.length];
            acc += k.eval(i);
            acc ^= acc << 4;
        }
        return acc;
    }
}

public class Test {
    private static volatile int sink;

    public static void main(String[] args) {
        Kernel[] kernels = new Kernel[] {new K1(), new K2(), new K3()};
        Loop loop = new Loop();
        sink = loop.run(kernels, 2400000);
        System.out.println(sink);
    }
}
