package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.repository.TaskConfigRepository;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TaskOnboardingResetService {
    private final TaskConfigRepository taskConfigRepository;
    private final TaskOnboardingCleanupService cleanupService;
    private final TaskOnboardingChildTableLock childTableLock;
    private final JdbcTemplate jdbcTemplate;
    private final TaskOnboardingContextCodec contextCodec;

    public TaskOnboardingResetService(
            TaskConfigRepository taskConfigRepository,
            TaskOnboardingCleanupService cleanupService,
            TaskOnboardingChildTableLock childTableLock,
            JdbcTemplate jdbcTemplate,
            TaskOnboardingContextCodec contextCodec) {
        this.taskConfigRepository = taskConfigRepository;
        this.cleanupService = cleanupService;
        this.childTableLock = childTableLock;
        this.jdbcTemplate = jdbcTemplate;
        this.contextCodec = contextCodec;
    }

    @Transactional
    public TaskOnboardingContext prepareSemanticReset(Long taskConfigId) {
        TaskConfig task = taskConfigRepository.findByIdForUpdate(taskConfigId)
                .orElseThrow(() -> new IllegalArgumentException("Task configuration does not exist"));
        TaskOnboardingContext existing = contextCodec.read(task);
        childTableLock.lockForCleanup();
        discoverUnreportedValidation(task, existing);
        cleanupOutstandingValidation(task, existing);

        Long formalCount = jdbcTemplate.queryForObject(
                "select count(*) from tb_task_result where task_config_id = ?", Long.class, taskConfigId);
        deleteExactTaskFormalData(taskConfigId);

        TaskOnboardingContext reset = new TaskOnboardingContext();
        reset.setOverwriteExistingFormalResults(formalCount != null && formalCount > 0);
        return reset;
    }

    private void discoverUnreportedValidation(TaskConfig task, TaskOnboardingContext context) {
        if (StringUtils.hasText(context.getResultValidationRunId())
                && context.getResultValidationIds().isEmpty()) {
            List<Long> ids = jdbcTemplate.queryForList("""
                    select id from tb_task_result
                    where task_config_id = ? and source_description = ?
                    order by id
                    """, Long.class, task.getId(),
                    "RESULT_VALIDATION:" + context.getResultValidationRunId());
            if (!ids.isEmpty()) {
                context.setResultValidationIds(ids);
                persistDiscoveredContext(task, context);
            }
        }
        if (StringUtils.hasText(context.getBatchValidationMarker())
                && context.getBatchValidationTaskRunId() == null) {
            String marker = "BATCH_VALIDATION:" + context.getBatchValidationMarker();
            List<Long> runIds = jdbcTemplate.queryForList("""
                    select id from tb_task_run
                    where task_config_id = ? and reason = ?
                    order by id
                    """, Long.class, task.getId(), marker);
            if (runIds.size() > 1) {
                throw new IllegalStateException("Multiple exact batch validation runs prevent safe reset");
            }
            if (runIds.size() == 1) {
                Long runId = runIds.get(0);
                List<Long> resultIds = jdbcTemplate.queryForList("""
                        select result.id
                        from tb_task_run_result link
                        join tb_task_result result on result.id = link.task_result_id
                        where link.task_run_id = ? and result.task_config_id = ?
                        order by result.id
                        """, Long.class, runId, task.getId());
                if (resultIds.isEmpty()) {
                    throw new IllegalStateException("Unreported batch validation run has no exact task results");
                }
                context.setBatchValidationTaskRunId(runId);
                context.setBatchValidationResultIds(resultIds);
                persistDiscoveredContext(task, context);
            }
        }
    }

    private void persistDiscoveredContext(TaskConfig task, TaskOnboardingContext context) {
        task.setOnboardingContext(contextCodec.write(context));
        taskConfigRepository.save(task);
    }

    private void cleanupOutstandingValidation(TaskConfig task, TaskOnboardingContext context) {
        if (!context.getResultValidationIds().isEmpty()) {
            cleanupService.deleteResultValidation(task.getId(), context.getResultValidationRunId());
        }
        if (context.getBatchValidationTaskRunId() != null) {
            cleanupService.deleteBatchValidation(
                    task.getId(), context.getBatchValidationTaskRunId(), context.getBatchValidationMarker());
        }
    }

    private void deleteExactTaskFormalData(Long taskConfigId) {
        jdbcTemplate.update("""
                delete from tb_task_execution_log log using tb_task_run run
                where log.task_run_id = run.id and run.task_config_id = ?
                """, taskConfigId);
        jdbcTemplate.update("""
                delete from tb_task_run_result link using tb_task_run run
                where link.task_run_id = run.id and run.task_config_id = ?
                """, taskConfigId);
        jdbcTemplate.update("delete from tb_task_run where task_config_id = ?", taskConfigId);
        jdbcTemplate.update("delete from tb_task_result where task_config_id = ?", taskConfigId);
    }
}
