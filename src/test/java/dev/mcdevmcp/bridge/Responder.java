package dev.mcdevmcp.bridge;

@FunctionalInterface
public interface Responder {
    BridgeResponse respond(int connection, BridgeRequest request) throws Exception;
}
