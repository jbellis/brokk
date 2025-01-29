public class B {
    public void callsIntoA() {
        A a = new A();
        a.method1();
        String result = a.method2("test");
        System.out.println(result);
    }
}
