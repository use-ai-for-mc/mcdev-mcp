package dev.mcdevmcp.storage.h2;

import java.nio.file.Path;
import java.util.Objects;

final class H2DatabaseUrls {
    private static final String MV_STORE_SUFFIX = ".mv.db";

    private H2DatabaseUrls() {
    }

    static String writer(Path database) {
        return url(database, ";DB_CLOSE_ON_EXIT=FALSE;FILE_LOCK=FS;WRITE_DELAY=0;LOCK_TIMEOUT=30000;TRACE_LEVEL_FILE=0");
    }

    static String reader(Path database) {
        return url(database, ";DB_CLOSE_ON_EXIT=FALSE;FILE_LOCK=FS;WRITE_DELAY=0;LOCK_TIMEOUT=30000;TRACE_LEVEL_FILE=0;ACCESS_MODE_DATA=r;IFEXISTS=TRUE");
    }

    static Path basePath(Path database) {
        Path normalized = Objects.requireNonNull(database, "database").toAbsolutePath().normalize();
        String fileName = normalized.getFileName().toString();
        if (!fileName.endsWith(MV_STORE_SUFFIX)) {
            throw new IllegalArgumentException("H2 database path must end in .mv.db: " + normalized);
        }
        String baseName = fileName.substring(0, fileName.length() - MV_STORE_SUFFIX.length());
        if (baseName.isBlank() || baseName.contains(";")) {
            throw new IllegalArgumentException("H2 database base path must not be blank or contain ';': " + normalized);
        }
        return normalized.resolveSibling(baseName);
    }

    private static String url(Path database, String settings) {
        Path base = basePath(database);
        if (base.toString().contains(";")) {
            throw new IllegalArgumentException("H2 database base path must not contain ';': " + base);
        }
        return "jdbc:h2:file:" + base + settings;
    }
}