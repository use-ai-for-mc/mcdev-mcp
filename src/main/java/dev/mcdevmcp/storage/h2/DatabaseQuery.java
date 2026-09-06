package dev.mcdevmcp.storage.h2;

import java.sql.Connection;

@FunctionalInterface
public interface DatabaseQuery<T> {
    T query(Connection connection) throws Exception;
}
