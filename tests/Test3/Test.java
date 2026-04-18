package tests.Test3;

abstract class Filter {
    abstract int map(int x);
}

class Low extends Filter {
    @Override
    int map(int x) {
        return x + 7;
    }
}

class High extends Filter {
    @Override
    int map(int x) {
        return x * 5;
    }
}

class Mixer {
    int processMono(Filter f, int n) {
        int acc = 0;
        for (int i = 0; i < n; i++) {
            acc += f.map(i);
            acc ^= acc >>> 3;
        }
        return acc;
    }

    int processPoly(Filter a, Filter b, int n) {
        int acc = 0;
        for (int i = 0; i < n; i++) {
            Filter f = ((i & 1) == 0) ? a : b;
            acc += f.map(i);
            acc ^= acc << 1;
        }
        return acc;
    }
}

public class Test {
    private static volatile int sink;

    public static void main(String[] args) {
        Filter low = new Low();
        Filter high = new High();
        Mixer mixer = new Mixer();

        int x = mixer.processMono(low, 1600000);
        int y = mixer.processPoly(low, high, 1600000);
        sink = x ^ y;
        System.out.println(sink);
    }
}
