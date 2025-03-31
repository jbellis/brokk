import {B} from "./sub/B";


export class A {
    public fieldB: B;

    private fieldA: A;

    private fieldCArray: Array<A>;

    private fieldDArray: B[];

    constructor(private bar?: B) {
    }

    public foo = () => { return "Hello World"; }

    public method1(): void {
        console.log("hello");
    }

    public method2(input: string, otherInput?: number): string {
        return otherInput !== undefined
            ? `prefix_${input} ${otherInput}`
            : `prefix_${input}`;
    }

    public method3(): (x: number) => number {
        return x => x + 1;
    }

    public static method4(foo: number, bar: number | null): number {
        return 0;
    }

    public method5(b: B): B {
        // self-reference
        console.log(new A());
        return b;
    }

    public method6(): void {
        // nested self-reference
        const runnable = {
            run(): void {
                console.log(new A());
            }
        };
        runnable.run();
    }
}
