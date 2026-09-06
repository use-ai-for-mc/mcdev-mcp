package callgraph.fixture;

public class Target {
    public Target() {
    }

    public void virtualTarget() {
    }

    public Target chainingTarget() {
        return this;
    }

    public void overloaded() {
    }

    public void overloaded(int value) {
    }
}
