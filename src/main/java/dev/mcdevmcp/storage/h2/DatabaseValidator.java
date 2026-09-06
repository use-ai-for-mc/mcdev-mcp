package dev.mcdevmcp.storage.h2;

import java.sql.Connection;

@FunctionalInterface
public interface DatabaseValidator {
    void validate(Connection connection) throws Exception;
}
