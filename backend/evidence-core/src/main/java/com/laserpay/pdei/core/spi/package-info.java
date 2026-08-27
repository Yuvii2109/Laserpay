/**
 * Data-access ports of the domain engine.
 *
 * <p>evidence-core never talks to JPA entities directly. Every service in this module depends on a
 * narrow port interface declared here, so the deterministic logic (readiness, gaps, contradictions,
 * safety, admission) is unit testable with plain in-memory fakes and no Spring context.</p>
 *
 * <p>The default adapters live in {@code com.laserpay.pdei.core.spi.jdbc} and read/write the
 * {@code pdei} schema owned by {@code platform-persistence} (platform contract 5). A service module
 * that prefers Spring Data repositories can supply its own implementation of any port and the
 * autoconfiguration will back off.</p>
 */
package com.laserpay.pdei.core.spi;
