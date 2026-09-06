package dev.mcdevmcp.bridge;

public record BridgeWireResponse(String id, Boolean success, Object result, String output, String error) {
}