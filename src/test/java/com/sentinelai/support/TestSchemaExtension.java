package com.sentinelai.support;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Binds each test class to its own schema.
 *
 * <p>Runs in the before-all phase, which is early enough: Spring cannot build a context
 * until a test instance exists, and no test instance is created until every before-all
 * callback has returned. So by the time
 * {@link PostgresIntegrationTest#configureDatasource} has its property values
 * resolved, {@link TestSchema} knows which class is running — without this extension
 * having to be ordered relative to {@code SpringExtension}.
 */
public class TestSchemaExtension implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        TestSchema.bind(context.getRequiredTestClass());
    }
}