import {A} from "../A";

export class B {
    constructor() {
        console.log("B constructor");
    }

    public callsIntoA(): void {
        const a = new A();
        a.method1();
        console.log(a.method2("test"));
    }
}
