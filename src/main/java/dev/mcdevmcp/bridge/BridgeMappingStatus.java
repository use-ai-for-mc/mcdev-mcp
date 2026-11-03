package dev.mcdevmcp.bridge;

public enum BridgeMappingStatus {
    MOJANG, PASSTHROUGH;

    public static BridgeMappingStatus fromWire(String value) {
        return switch (value) {
            case "mojang" -> MOJANG;
            case "passthrough" -> PASSTHROUGH;
            default ->
                    throw new IllegalArgumentException("DebugBridge status mappingStatus is invalid: " + BridgePayloadValidator.safeDisplay(value));
        };
    }
}
