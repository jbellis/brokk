import java.util.function.Function;

public class A {
    public void method1() {
        System.out.println("hello");
    }

    public String method2(String input) {
        return "prefix_" + input;
    }

    public Function<Integer, Integer> method3() {
        return x -> x + 1;
    }

    public static int method4(double foo, Integer bar) {
        return 0;
    }
}
