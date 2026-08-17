package com.connectx.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This project has no Flyway/Liquibase migration tool -- schema changes are applied by
 * Hibernate's {@code ddl-auto: update} at {@code EntityManagerFactory} bootstrap. That means
 * a newly added {@code @UniqueConstraint} (see {@link com.connectx.message.entity.MessageStar},
 * added for M-06) has no migration step to clean up pre-existing duplicate rows first --
 * Hibernate would just try to ALTER TABLE ADD CONSTRAINT directly, which silently fails
 * (logged, not fatal) on any database that already has duplicate (message_id, user_id) rows
 * from before the race-condition fix, leaving the table permanently unprotected.
 * <p>
 * This runs the de-duplication as a {@link BeanPostProcessor} hook on the {@code DataSource}
 * bean, since that is the earliest point with a JDBC connection available and is guaranteed
 * (by Spring's dependency resolution) to complete before the JPA {@code EntityManagerFactory}
 * -- which depends on the DataSource bean -- triggers Hibernate's schema update.
 */
@Component
public class MessageStarSchemaPreparer implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(MessageStarSchemaPreparer.class);

    private static final String DEDUPLICATE_SQL =
            "DELETE FROM message_stars WHERE id NOT IN (" +
            "  SELECT keep_id FROM (" +
            "    SELECT MIN(id) AS keep_id FROM message_stars GROUP BY message_id, user_id" +
            "  ) AS keepers" +
            ")";

    private final AtomicBoolean alreadyRan = new AtomicBoolean(false);

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DataSource dataSource && alreadyRan.compareAndSet(false, true)) {
            deduplicateMessageStars(dataSource);
        }
        return bean;
    }

    private void deduplicateMessageStars(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            int removed = statement.executeUpdate(DEDUPLICATE_SQL);
            if (removed > 0) {
                log.warn("Removed {} duplicate message_stars row(s) before adding uk_message_star unique constraint", removed);
            }
        } catch (SQLException ex) {
            // Most common cause: message_stars doesn't exist yet (brand-new database) --
            // Hibernate will create the table with the unique constraint from scratch, so
            // there's nothing to clean up. Any other failure is logged but non-fatal: worst
            // case, Hibernate's own ALTER TABLE also fails and logs, same as before this fix.
            log.debug("Skipped message_stars de-duplication (table may not exist yet): {}", ex.getMessage());
        }
    }
}
