package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.repository.ConnectionConfigRepository;
import com.aitaskcenter.repository.ProjectConfigRepository;
import com.aitaskcenter.repository.TaskConfigRepository;
import com.aitaskcenter.repository.TaskResultRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TaskConfigNewLifecycleTest {
    @Test
    void createsBasicTaskAwaitingTargetSelection() {
        TaskConfigRepository repository = mock(TaskConfigRepository.class);
        when(repository.save(any(TaskConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));
        TaskConfigService service = service(repository);

        TaskConfig created = service.create(basicTask("新任务"));

        assertNull(created.getHandlerKey());
        assertNull(created.getExecutorType());
        assertNull(created.getExecutorId());
        assertEquals("TARGET_SELECTION", created.getOnboardingStep());
        assertEquals("ACTIVE", created.getOnboardingStatus());
    }

    @Test
    void updatingBasicFieldsPreservesRegisteredRuntimeMetadata() {
        TaskConfigRepository repository = mock(TaskConfigRepository.class);
        TaskConfig existing = basicTask("旧名称");
        existing.setId(42L);
        existing.setHandlerKey("task_config_42");
        existing.setExecutorType("CLI");
        existing.setExecutorId("codex");
        when(repository.findById(42L)).thenReturn(Optional.of(existing));
        when(repository.save(any(TaskConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));
        TaskConfigService service = service(repository);

        TaskConfig updated = service.update(42L, basicTask("新名称"));

        assertEquals("新名称", updated.getTaskName());
        assertEquals("task_config_42", updated.getHandlerKey());
        assertEquals("CLI", updated.getExecutorType());
        assertEquals("codex", updated.getExecutorId());
    }

    private static TaskConfigService service(TaskConfigRepository repository) {
        ProjectConfigRepository projectRepository = mock(ProjectConfigRepository.class);
        when(projectRepository.existsById(1L)).thenReturn(true);
        return new TaskConfigService(
                repository,
                projectRepository,
                mock(ConnectionConfigRepository.class),
                mock(TaskResultRepository.class),
                mock(PythonWorkerClient.class),
                mock(JdbcTemplate.class));
    }

    private static TaskConfig basicTask(String name) {
        TaskConfig task = new TaskConfig();
        task.setTaskName(name);
        task.setProjectId(1L);
        task.setTaskDesc("读取来源表并生成任务结果");
        return task;
    }
}
