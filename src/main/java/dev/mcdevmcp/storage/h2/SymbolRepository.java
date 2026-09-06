package dev.mcdevmcp.storage.h2;

import dev.mcdevmcp.storage.model.*;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * Short-lived, deterministic, read-only access to an indexed symbol database.
 */
@SuppressWarnings("SqlNoDataSourceInspection")
public final class SymbolRepository {
    private static final String CLASSES_ORDER = " ORDER BY CASE t.source_namespace WHEN 'minecraft' THEN 0 ELSE 1 END, p.id, t.id";
    private final Path database;

    public SymbolRepository(Path database) {
        this.database = Objects.requireNonNull(database, "database").toAbsolutePath().normalize();
    }

    private static String classesSelect() {
        return "SELECT t.id, t.source_namespace, t.fabric_api_version, t.binary_name, " + "p.name, t.simple_name, t.kind, t.superclass_binary_name, t.source_path, " + "t.start_offset, t.end_offset, t.start_line, t.end_line " + "FROM types t JOIN packages p ON p.id=t.package_id";
    }

    private static String classesSelectWithCounts() {
        String base = classesSelect();
        int from = base.indexOf(" FROM ");
        return base.substring(0, from) + ", (SELECT COUNT(*) FROM fields f WHERE f.type_id=t.id)" + ", (SELECT COUNT(*) FROM methods m WHERE m.type_id=t.id)" + base.substring(from);
    }

    private static int countPackages(Connection connection, String namespace, String where) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM packages" + where)) {
            if (namespace != null) statement.setString(1, namespace);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static List<ClassSymbol> classes(Connection connection, String sql, StatementArguments arguments) throws Exception {
        List<ClassSymbol> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            arguments.set(statement);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(classSymbol(connection, result));
            }
        }
        return List.copyOf(values);
    }

    private static ClassSymbol classSymbol(Connection connection, ResultSet result) throws SQLException {
        long id = result.getLong(1);
        String fabric = result.getString(3);
        SourceNamespace namespace = SourceNamespace.fromWireName(result.getString(2));
        Optional<FabricApiVersion> fabricVersion = fabric == null ? Optional.empty() : Optional.of(new FabricApiVersion(fabric));
        return new ClassSymbol(id, namespace, fabricVersion, result.getString(4), result.getString(5), result.getString(6), ElementKindCodec.fromWireName(result.getString(7)), Optional.ofNullable(result.getString(8)), interfaces(connection, id), Path.of(result.getString(9)), result.getInt(10), result.getInt(11), result.getInt(12), result.getInt(13));
    }

    private static List<String> interfaces(Connection connection, long typeId) throws SQLException {
        List<String> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT interface_binary_name FROM type_interfaces WHERE type_id=? ORDER BY ordinal")) {
            statement.setLong(1, typeId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(result.getString(1));
            }
        }
        return List.copyOf(values);
    }

    private static List<FieldSymbol> fields(Connection connection, long typeId, String match) throws SQLException {
        String sql = "SELECT id,type_id,ordinal,name,type,modifiers," + "start_offset,end_offset,start_line,end_line FROM fields WHERE type_id=?" + (match == null ? "" : " AND LOWER(name) LIKE ? ESCAPE '\\'") + " ORDER BY ordinal,id";
        List<FieldSymbol> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, typeId);
            if (match != null) statement.setString(2, match);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(field(result));
            }
        }
        return List.copyOf(values);
    }

    private static List<MethodSymbol> methods(Connection connection, long typeId, String match) throws SQLException {
        String sql = "SELECT id,type_id,ordinal,name,descriptor,return_type,modifiers,constructor," + "start_offset,end_offset,start_line,end_line FROM methods WHERE type_id=?" + (match == null ? "" : " AND LOWER(name) LIKE ? ESCAPE '\\'") + " ORDER BY ordinal,id";
        List<MethodSymbol> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, typeId);
            if (match != null) statement.setString(2, match);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(method(result));
            }
        }
        return List.copyOf(values);
    }

    private static FieldSymbol field(ResultSet r) throws SQLException {
        return new FieldSymbol(r.getLong(1), r.getLong(2), r.getInt(3), r.getString(4), r.getString(5), modifiers(r.getString(6)), r.getInt(7), r.getInt(8), r.getInt(9), r.getInt(10));
    }

    private static MethodSymbol method(ResultSet r) throws SQLException {
        return new MethodSymbol(r.getLong(1), r.getLong(2), r.getInt(3), r.getString(4), r.getString(5), Optional.ofNullable(r.getString(6)), modifiers(r.getString(7)), r.getBoolean(8), r.getInt(9), r.getInt(10), r.getInt(11), r.getInt(12));
    }

    private static List<ParameterSymbol> parameterValues(Connection connection, long methodId) throws SQLException {
        List<ParameterSymbol> values = new ArrayList<>();
        String sql = "SELECT id, method_id, ordinal, name, type, varargs, " + "start_offset, end_offset, start_line, end_line " + "FROM parameters WHERE method_id=? ORDER BY ordinal,id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, methodId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(parameter(result));
            }
        }
        return List.copyOf(values);
    }

    private static ParameterSymbol parameter(ResultSet r) throws SQLException {
        return new ParameterSymbol(r.getLong(1), r.getLong(2), r.getInt(3), r.getString(4), r.getString(5), r.getBoolean(6), r.getInt(7), r.getInt(8), r.getInt(9), r.getInt(10));
    }

    private static EnumSet<Modifier> modifiers(String text) {
        EnumSet<Modifier> values = EnumSet.noneOf(Modifier.class);
        if (text != null && !text.isEmpty()) {
            for (String value : text.split(",")) values.add(Modifier.valueOf(value.toUpperCase(Locale.ROOT)));
        }
        return values;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public <T> T query(DatabaseQuery<T> query) throws IOException, SQLException {
        Objects.requireNonNull(query, "query");
        try (var databaseLock = DatabaseLock.read(database, AtomicH2Database.WRITE_LOCK_TIMEOUT);
             Connection connection = DriverManager.getConnection(H2DatabaseUrls.reader(database))) {
            if (!databaseLock.isHeld()) throw new IOException("Failed to acquire shared database lock");
            try {
                return query.query(connection);
            } catch (SQLException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new SQLException("Read-only symbol query failed", exception);
            }
        }
    }

    public List<ClassSymbol> classesUnder(String packageName, int limitPlusOne) throws IOException, SQLException {
        String sql = classesSelect() + " WHERE LOWER(p.name) = LOWER(?) OR LOWER(p.name) LIKE LOWER(?) ESCAPE '\\'" + CLASSES_ORDER + " LIMIT ?";
        return query(connection -> classes(connection, sql, statement -> {
            statement.setString(1, packageName);
            statement.setString(2, escapeLike(packageName) + ".%");
            statement.setInt(3, limitPlusOne);
        }));
    }

    public PackageListing packages(String namespace, int limitPlusOne) throws IOException, SQLException {
        String where = namespace == null ? "" : " WHERE source_namespace = ?";
        return query(connection -> {
            int total = countPackages(connection, namespace, where);
            List<String> values = new ArrayList<>();
            String sql = "SELECT name FROM packages" + where + " ORDER BY CASE source_namespace WHEN 'minecraft' THEN 0 ELSE 1 END, id LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int next = 1;
                if (namespace != null) statement.setString(next++, namespace);
                statement.setInt(next, limitPlusOne);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) values.add(result.getString(1));
                }
            }
            return new PackageListing(values, total);
        });
    }

    public ClassSymbol classByName(String binaryName) throws IOException, SQLException {
        String sql = classesSelect() + " WHERE t.binary_name = ? ORDER BY t.id LIMIT 1";
        List<ClassSymbol> values = query(connection -> classes(connection, sql, statement -> statement.setString(1, binaryName)));
        return values.isEmpty() ? null : values.getFirst();
    }

    public List<FieldSymbol> fields(long typeId) throws IOException, SQLException {
        return query(connection -> fields(connection, typeId, null));
    }

    public List<MethodSymbol> methods(long typeId) throws IOException, SQLException {
        return query(connection -> methods(connection, typeId, null));
    }

    public MethodSymbol methodNamed(long typeId, String name) throws IOException, SQLException {
        return query(connection -> {
            String sql = "SELECT id, type_id, ordinal, name, descriptor, return_type, modifiers, constructor, " + "start_offset, end_offset, start_line, end_line FROM methods " + "WHERE type_id = ? AND (name = ? OR LOWER(name) = LOWER(?)) " + "ORDER BY CASE WHEN name = ? THEN 0 ELSE 1 END, ordinal, id LIMIT 1";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, typeId);
                statement.setString(2, name);
                statement.setString(3, name);
                statement.setString(4, name);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? method(result) : null;
                }
            }
        });
    }

    public List<ParameterSymbol> parameters(long methodId) throws IOException, SQLException {
        return query(connection -> {
            List<ParameterSymbol> values = new ArrayList<>();
            String sql = "SELECT id, method_id, ordinal, name, type, varargs, " + "start_offset, end_offset, start_line, end_line " + "FROM parameters WHERE method_id = ? ORDER BY ordinal, id";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, methodId);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) values.add(parameter(result));
                }
            }
            return List.copyOf(values);
        });
    }

    public List<ClassSymbol> hierarchy(String binaryName, boolean subclasses, int limitPlusOne) throws IOException, SQLException {
        String join = subclasses ? "" : " JOIN type_interfaces i ON i.type_id = t.id";
        String predicate = subclasses ? "t.superclass_binary_name = ?" : "i.interface_binary_name = ?";
        return query(connection -> classes(connection, classesSelect() + join + " WHERE " + predicate + CLASSES_ORDER + " LIMIT ?", statement -> {
            statement.setString(1, binaryName);
            statement.setInt(2, limitPlusOne);
        }));
    }

    /**
     * Literal, case-insensitive matching in Node's namespace/package/type/member order.
     */
    public List<SearchHit> search(String query, String kind, int limitPlusOne) throws IOException, SQLException {
        if (kind != null && !kind.equals("class") && !kind.equals("field") && !kind.equals("method")) {
            return List.of();
        }
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        String match = "%" + escapeLike(normalizedQuery) + "%";
        return query(connection -> {
            String predicate = switch (kind == null ? "all" : kind) {
                case "class" -> "LOWER(t.simple_name) LIKE ? ESCAPE '\\'";
                case "field" ->
                        "EXISTS (SELECT 1 FROM fields f WHERE f.type_id=t.id AND LOWER(f.name) LIKE ? ESCAPE '\\')";
                case "method" ->
                        "EXISTS (SELECT 1 FROM methods m WHERE m.type_id=t.id AND LOWER(m.name) LIKE ? ESCAPE '\\')";
                default ->
                        "LOWER(t.simple_name) LIKE ? ESCAPE '\\'" + " OR EXISTS (SELECT 1 FROM fields f WHERE f.type_id=t.id" + " AND LOWER(f.name) LIKE ? ESCAPE '\\')" + " OR EXISTS (SELECT 1 FROM methods m WHERE m.type_id=t.id" + " AND LOWER(m.name) LIKE ? ESCAPE '\\')";
            };
            boolean needsCounts = kind == null || kind.equals("class");
            String sql = (needsCounts ? classesSelectWithCounts() : classesSelect()) + " WHERE " + predicate + CLASSES_ORDER + " LIMIT ?";
            List<SearchHit> hits = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int next = 1;
                statement.setString(next++, match);
                if (kind == null) {
                    statement.setString(next++, match);
                    statement.setString(next++, match);
                }
                statement.setInt(next, limitPlusOne);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next() && hits.size() < limitPlusOne) {
                        ClassSymbol owner = classSymbol(connection, result);
                        int fieldCount = needsCounts ? result.getInt(14) : 0;
                        int methodCount = needsCounts ? result.getInt(15) : 0;
                        if ((kind == null || kind.equals("class")) && owner.simpleName().toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                            hits.add(new SearchHit(SearchHitKind.CLASS, owner, Optional.empty(), Optional.empty(), List.of(), fieldCount, methodCount));
                        }
                        if ((kind == null || kind.equals("field")) && hits.size() < limitPlusOne) {
                            for (FieldSymbol field : fields(connection, owner.id(), match)) {
                                hits.add(new SearchHit(SearchHitKind.FIELD, owner, Optional.of(field), Optional.empty(), List.of(), fieldCount, methodCount));
                                if (hits.size() == limitPlusOne) {
                                    break;
                                }
                            }
                        }
                        if ((kind == null || kind.equals("method")) && hits.size() < limitPlusOne) {
                            for (MethodSymbol method : methods(connection, owner.id(), match)) {
                                hits.add(new SearchHit(SearchHitKind.METHOD, owner, Optional.empty(), Optional.of(method), parameterValues(connection, method.id()), fieldCount, methodCount));
                                if (hits.size() == limitPlusOne) {
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            return List.copyOf(hits);
        });
    }
}
