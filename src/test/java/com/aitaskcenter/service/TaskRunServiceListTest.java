package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aitaskcenter.model.TaskRecordType;
import com.aitaskcenter.model.TaskRun;
import com.aitaskcenter.repository.ProjectConfigRepository;
import com.aitaskcenter.repository.TaskConfigRepository;
import com.aitaskcenter.repository.TaskExecutionLogRepository;
import com.aitaskcenter.repository.TaskResultRepository;
import com.aitaskcenter.repository.TaskRunRepository;
import com.aitaskcenter.repository.TaskRunResultRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskRunServiceListTest {
    @Test
    void listsCurrentValidationRunsByTaskConfig() {
        TaskRunRepository repository = mock(TaskRunRepository.class);
        TaskRun formal = taskRun(1L, TaskRecordType.FORMAL);
        TaskRun validationForTask = taskRun(1L, TaskRecordType.VALIDATION_CURRENT);
        TaskRun validationForOtherTask = taskRun(2L, TaskRecordType.VALIDATION_CURRENT);
        when(repository.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(formal, validationForTask, validationForOtherTask));

        TaskRunService service = new TaskRunService(
                repository,
                mock(TaskConfigRepository.class),
                mock(ProjectConfigRepository.class),
                mock(TaskResultRepository.class),
                mock(TaskRunResultRepository.class),
                mock(TaskExecutionLogRepository.class),
                mock(PythonWorkerClient.class),
                mock(TaskRunPromptBuilder.class));

        List<TaskRun> results = service.list(
                null, null, 1L, null, null, TaskRecordType.VALIDATION_CURRENT);

        assertEquals(List.of(validationForTask), results);
    }

    private static TaskRun taskRun(Long taskConfigId, String recordType) {
        TaskRun run = new TaskRun();
        run.setTaskName("测试批次");
        run.setTaskConfigId(taskConfigId);
        run.setProjectId(1L);
        run.setCliId("codex");
        run.setStatus("PENDING");
        run.setRecordType(recordType);
        return run;
    }
}
