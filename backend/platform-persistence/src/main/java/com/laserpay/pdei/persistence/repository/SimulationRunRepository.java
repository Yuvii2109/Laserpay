package com.laserpay.pdei.persistence.repository;

import com.laserpay.pdei.persistence.entity.SimulationRunEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Simulation runs ({@code GET /sim/v1/runs}); (seed, params) make a run reproducible. */
@Repository
public interface SimulationRunRepository extends JpaRepository<SimulationRunEntity, String> {

    List<SimulationRunEntity> findByStatus(String status);

    List<SimulationRunEntity> findByStatusIn(Collection<String> statuses);

    Page<SimulationRunEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<SimulationRunEntity> findBySeed(long seed);

    List<SimulationRunEntity> findByScenarioKeyOrderByCreatedAtDesc(String scenarioKey);
}
