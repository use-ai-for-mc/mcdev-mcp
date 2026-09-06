package callgraph.fixture;

import java.io.Serializable;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class Fixture extends Base {
    static void staticTarget(int value) {
    }

    public void ordinary(Contract contract, Target target) {
        target.virtualTarget();
        contract.interfaceTarget();
        staticTarget(1);
        super.specialTarget();
        new Target();
        target.overloaded();
        target.overloaded(2);
    }

    public Runnable lambda() {
        return () -> staticTarget(3);
    }

    public Supplier<Target> constructorReference() {
        return Target::new;
    }

    public Consumer<Target> unboundReference() {
        return Target::virtualTarget;
    }

    public Runnable boundReference(Target target) {
        return target::virtualTarget;
    }

    public Consumer<Contract> interfaceReference() {
        return Contract::interfaceTarget;
    }

    public Runnable serializableLambda() {
        return (Runnable & Serializable) () -> staticTarget(4);
    }

    public String concatenation(String value) {
        return "prefix-" + value;
    }

    public void duplicateSameLine(Target target) {
        target.chainingTarget().chainingTarget();
    }

    public void duplicateDifferentLines(Target target) {
        target.virtualTarget();
        target.virtualTarget();
    }
}
