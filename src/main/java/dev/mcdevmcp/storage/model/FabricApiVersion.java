package dev.mcdevmcp.storage.model;

public record FabricApiVersion(String value) {
    public FabricApiVersion {
        PortablePathComponent.requireValid(value, "Fabric API version must be a safe single path component: ");
    }
}