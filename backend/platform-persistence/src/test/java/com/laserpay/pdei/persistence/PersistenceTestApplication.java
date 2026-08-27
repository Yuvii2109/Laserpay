package com.laserpay.pdei.persistence;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot application used only to bootstrap an application context for the
 * integration tests. {@code platform-persistence} is a library and ships no application class of
 * its own; this one exists so the tests exercise exactly what a real service module gets:
 * the autoconfiguration, the Flyway migrations and the JPA mappings, nothing else.
 */
@SpringBootApplication
public class PersistenceTestApplication {
}
