package tests.Test8;

abstract class Worker {
    abstract int work(int x);
}

class SquareWorker extends Worker {
    @Override
    int work(int x) {
        return x * x + 1;
    }
}

class NegWorker extends Worker {
    @Override
    int work(int x) {
        return -x;
    }
}

class Logger {
    static int warmup() {
        Worker w = new NegWorker();
        return w.work(7);
    }
}

class Runner {
    Worker buildHotWorker() {
        return new SquareWorker();
    }

    int hotLoop(int rounds) {
        Worker w = buildHotWorker();
        int acc = 0;
        for (int i = 0; i < rounds; i++) {
            acc += w.work(i);
            acc ^= (acc << 1);
        }
        return acc;
    }
}

public class Test {
    private static volatile int sink;

    public static void main(String[] args) {
        int warm = Logger.warmup();
        Runner r = new Runner();
        int total = warm;

        for (int i = 0; i < 10; i++) {
            total ^= r.hotLoop(220000);
        }

        sink = total;
        System.out.println(sink);
    }
}
