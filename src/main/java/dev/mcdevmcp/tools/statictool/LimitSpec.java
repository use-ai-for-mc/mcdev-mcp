package dev.mcdevmcp.tools.statictool;

record LimitSpec(int defaultValue, int maximum) {
    LimitSpec {
        if (defaultValue < 1 || maximum < defaultValue) {
            throw new IllegalArgumentException("Invalid limit specification");
        }
    }

    NormalizedLimit normalize(Integer requestedLimit) {
        if (requestedLimit == null) {
            return new NormalizedLimit(defaultValue, false, true);
        }
        if (requestedLimit <= 0) {
            throw new IllegalArgumentException("'limit' must not be below 1");
        }
        if (requestedLimit > maximum) {
            return new NormalizedLimit(maximum, true, false);
        }
        return new NormalizedLimit(requestedLimit, false, false);
    }
}
