package tests.Test4;

abstract class Node {
    abstract int eval(int x);
}

class ChainNode extends Node {
    @Override
    int eval(int x) {
        return step1(x);
    }

    private int step1(int x) {
        return step2(x + 1);
    }

    private int step2(int x) {
        return step3(x ^ 0x55);
    }

    private int step3(int x) {
        return step4(x * 3);
    }

    private int step4(int x) {
        return step5(x - 7);
    }

    private int step5(int x) {
        return x + (x >>> 2);
    }
}

class AltNode extends Node {
    @Override
    int eval(int x) {
        return (x * 11) - 3;
    }
}

class Loop {
    int run(Node n, int rounds) {
        int acc = 0;
        for (int i = 0; i < rounds; i++) {
            acc ^= n.eval(i);
            acc += acc << 1;
        }
        return acc;
    }
}

public class Test {
    private static volatile int sink;

    public static void main(String[] args) {
        Node noise = new AltNode();
        int warm = noise.eval(5);

        Loop loop = new Loop();
        Node hot = new ChainNode();
        sink = warm ^ loop.run(hot, 2600000);
        System.out.println(sink);
    }
}
