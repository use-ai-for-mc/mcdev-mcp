package dev.mcdevmcp.bridge;

public value record BridgeWireResponse(String id, Boolean success, Object result, String output, String error) {
}