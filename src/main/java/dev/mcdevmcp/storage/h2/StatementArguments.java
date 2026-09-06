package dev.mcdevmcp.storage.h2;

import java.sql.PreparedStatement;
import java.sql.SQLException;

@FunctionalInterface
interface StatementArguments {
    void set(PreparedStatement statement) throws SQLException;
}
