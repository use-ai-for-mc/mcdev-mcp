package index.fixture;

import dependency.External;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

enum Shade {
    RED, BLUE;

    int code;
}

interface Defaults {
    int CONSTANT = 1;

    default int value() {
        return CONSTANT;
    }
}

@interface Marker {
    String value() default "marker";
}

public sealed class FeatureSet<T extends Number & Comparable<T>> extends SourceBase implements Serializable permits Child {
    public static final int FIRST = 1, SECOND = 2;
    External external;
    private List<? super T[]> values;

    public FeatureSet() {
    }

    public <U extends CharSequence> List<? extends U> transform(U value, String... rest) {
        return List.of(value);
    }

    public void execute() {
        class Local {
            int ignored;
        }
        new Local();
    }

    static class Nested {
        int hidden;

        void hiddenMethod() {
        }
    }
}

final class Child extends FeatureSet<Integer> {
}

final class NestedChild extends FeatureSet.Nested {
}

record Pair<T>(T left, T right) {
    Pair {
        Objects.requireNonNull(left);
    }
}
