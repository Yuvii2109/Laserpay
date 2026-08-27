package com.laserpay.pdei.simulator.controller;

import com.laserpay.pdei.persistence.entity.ChaosInjectionEntity;

import java.time.Instant;
import java.util.Map;

/**
 * One row of the chaos history, {@code GET /sim/v1/chaos}.
 *
 * <p>Each entry answers three questions an observer of a recovery demo has to be able to check:
 * what was injected, when, and what the injection itself reported afterwards.
 */
public record ChaosViewDto(String injectionId,
                           String runId,
                           String merchantId,
                           String type,
                           String category,
                           String status,
                           Map<String, Object> target,
                           Long delayMs,
                           Integer count,
                           String actor,
                           Instant injectedAt,
                           Instant completedAt,
                           Map<String, Object> result,
                           String errorMessage) {

    public static ChaosViewDto from(ChaosInjectionEntity entity) {
        return new ChaosViewDto(
                entity.getId(),
                entity.getRunId(),
                entity.getMerchantId(),
                entity.getType() == null ? null : entity.getType().name(),
                entity.getType() == null ? null : entity.getType().category().name(),
                entity.getStatus(),
                entity.getTarget(),
                entity.getDelayMs(),
                entity.getEventCount(),
                entity.getActor(),
                entity.getInjectedAt(),
                entity.getCompletedAt(),
                entity.getResult(),
                entity.getErrorMessage());
    }
}
