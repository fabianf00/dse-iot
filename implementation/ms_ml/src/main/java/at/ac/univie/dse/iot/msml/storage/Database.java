package at.ac.univie.dse.iot.msml.storage;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The object-relational mapping of MS ML: a JPA {@link EntityManagerFactory} (Hibernate ORM) built
 * in code from the {@link StorageConfig}, without {@code persistence.xml}.
 *
 * <p>The schema is created and updated from the entities by Hibernate, so no SQL or DDL is
 * written by hand. Every unit of work gets its own {@link EntityManager} and transaction.
 */
public final class Database implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Database.class);

    private final EntityManagerFactory factory;
    private final String jdbcUrl;

    private Database(EntityManagerFactory factory, String jdbcUrl) {
        this.factory = factory;
        this.jdbcUrl = jdbcUrl;
    }

    /**
     * Opens the database and creates or updates its schema.
     *
     * @throws StorageException if it cannot be opened, e.g. because another process uses the file
     */
    public static Database open(StorageConfig config) {
        try {
            EntityManagerFactory factory = new PersistenceConfiguration("ms-ml")
                    .managedClass(MlResultEntity.class)
                    .managedClass(AnomalyEntity.class)
                    .managedClass(PredictionEntity.class)
                    .managedClass(CommentEntity.class)
                    .managedClass(PhaseObservationEmbeddable.class)
                    .property(PersistenceConfiguration.JDBC_URL, config.jdbcUrl())
                    .property(PersistenceConfiguration.JDBC_USER, config.user())
                    .property(PersistenceConfiguration.JDBC_PASSWORD, config.password())
                    .property("hibernate.hbm2ddl.auto", "update")
                    .property("hibernate.physical_naming_strategy",
                            "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                    .property("hibernate.jdbc.time_zone", "UTC")
                    .property("hibernate.hikari.maximumPoolSize", Integer.toString(config.poolSize()))
                    .property("hibernate.hikari.poolName", "ms-ml-db")
                    .createEntityManagerFactory();
            LOG.info("Database {} opened", config.jdbcUrl());
            return new Database(factory, config.jdbcUrl());
        } catch (PersistenceException | IllegalStateException e) {
            throw new StorageException("Cannot open database " + config.jdbcUrl() + ": " + rootMessage(e), e);
        }
    }

    /** Runs {@code work} in a transaction and returns its result; rolls back on an exception. */
    public <T> T inTransaction(Function<EntityManager, T> work) {
        requireOpen();
        try {
            return factory.callInTransaction(work);
        } catch (PersistenceException e) {
            throw new StorageException("Database operation failed: " + rootMessage(e), e);
        }
    }

    /** Runs {@code work} in a transaction; rolls back on an exception. */
    public void runInTransaction(Consumer<EntityManager> work) {
        requireOpen();
        try {
            factory.runInTransaction(work);
        } catch (PersistenceException e) {
            throw new StorageException("Database operation failed: " + rootMessage(e), e);
        }
    }

    private void requireOpen() {
        if (!factory.isOpen()) {
            throw new StorageException("Database " + jdbcUrl + " is closed", null);
        }
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    @Override
    public void close() {
        if (factory.isOpen()) {
            factory.close();
            LOG.info("Database {} closed", jdbcUrl);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        return message.lines().findFirst().orElse(message);
    }
}
