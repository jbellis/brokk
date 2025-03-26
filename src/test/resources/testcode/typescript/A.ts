export class A {
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

    public method5(): void {
        // self-reference
        console.log(new A());
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

    // In TypeScript, nested classes are defined differently
    public AInner = class {
        public AInnerInner = class {
            public method7(): void {
                console.log("hello");
            }
        }
    }

    public static AInnerStatic = class {}
}
