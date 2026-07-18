package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void trimsAndSavesRequiredTaskDescription() {
        TaskConfigRepository repository = mock(TaskConfigRepository.class);
        when(repository.save(any(TaskConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));
        TaskConfigService service = service(repository);
        TaskConfig input = basicTask("新任务");
        input.setTaskDesc("  从来源表生成语音任务  ");

        TaskConfig created = service.create(input);

        assertEquals("从来源表生成语音任务", created.getTaskDesc());
    }

    @Test
    void rejectsBlankTaskDescription() {
        TaskConfigService service = service(mock(TaskConfigRepository.class));
        TaskConfig input = basicTask("新任务");
        input.setTaskDesc("   ");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(input));

        assertEquals("请填写任务描述", error.getMessage());
    }

    @Test
    void rejectsTaskDescriptionLongerThanTwoThousandCharacters() {
        TaskConfigService service = service(mock(TaskConfigRepository.class));
        TaskConfig input = basicTask("新任务");
        input.setTaskDesc("x".repeat(2001));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(input));

        assertEquals("任务描述不能超过 2000 个字符", error.getMessage());
    }

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
