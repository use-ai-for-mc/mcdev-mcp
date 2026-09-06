package dev.mcdevmcp.storage.h2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;

@SuppressWarnings("SqlNoDataSourceInspection")
public final class AtomicH2Database {
    public static final Duration WRITE_LOCK_TIMEOUT = Duration.ofSeconds(30);
    private static final String CHECKPOINT_SQL = "CHECKPOINT SYNC";

    private final H2DatabasePromotion promotion;

    public AtomicH2Database() {
        this(Files::move);
    }

    AtomicH2Database(DatabaseFileOperations files) {
        promotion = new H2DatabasePromotion(Objects.requireNonNull(files, "files"));
    }

    private static <T> T buildTemporaryDatabase(Path temporary, DatabaseBuilder<T> builder, DatabaseValidator validator) throws Exception {
        try (Connection connection = DriverManager.getConnection(H2DatabaseUrls.writer(temporary))) {
            connection.setAutoCommit(false);
            try {
                T result = builder.build(connection);
                validator.validate(connection);
                connection.commit();
                try (Statement statement = connection.createStatement()) {
                    statement.execute(CHECKPOINT_SQL);
                }
                return result;
            } catch (Exception exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        }
    }

    public <T> T rebuild(Path target, Duration lockTimeout, DatabaseBuilder<T> builder, DatabaseValidator validator) throws IOException, SQLException {
        return rebuild(target, lockTimeout, builder, validator, validator);
    }

    public <T> T rebuild(Path target, Duration lockTimeout, DatabaseBuilder<T> builder, DatabaseValidator existingTargetValidator, DatabaseValidator candidateValidator) throws IOException, SQLException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(lockTimeout, "lockTimeout");
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(existingTargetValidator, "existingTargetValidator");
        Objects.requireNonNull(candidateValidator, "candidateValidator");
        H2DatabaseArtifacts artifacts = new H2DatabaseArtifacts(target);
        artifacts.createTargetDirectory();
        try (var databaseLock = DatabaseLock.write(artifacts.target, lockTimeout)) {
            if (!databaseLock.isHeld()) {
                throw new IOException("Failed to acquire exclusive database lock");
            }
            promotion.resolveBackup(artifacts, existingTargetValidator);
            artifacts.rejectActiveTargetCompanion();
            artifacts.clearStaleTargetCompanions();
            Path temporary = artifacts.temporary;
            artifacts.deleteTemporaryArtifacts();
            try {
                T result = buildTemporaryDatabase(temporary, builder, candidateValidator);
                artifacts.verifyClosedTemporaryDatabase(candidateValidator);
                promotion.promote(artifacts, candidateValidator);
                return result;
            } catch (Exception exception) {
                try {
                    artifacts.deleteTemporaryArtifacts();
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                if (exception instanceof IOException ioException) {
                    throw ioException;
                }
                if (exception instanceof SQLException sqlException) {
                    throw sqlException;
                }
                throw new SQLException("H2 database rebuild failed", exception);
            }
        }
    }

}