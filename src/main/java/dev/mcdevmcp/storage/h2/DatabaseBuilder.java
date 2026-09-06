package dev.mcdevmcp.storage.h2;

import java.sql.Connection;

@FunctionalInterface
public interface DatabaseBuilder<T> {
    T build(Connection connection) throws Exception;
}
