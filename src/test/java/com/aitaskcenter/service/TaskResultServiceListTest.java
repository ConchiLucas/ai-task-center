package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aitaskcenter.dto.BatchProcessTaskResultRequest;
import com.aitaskcenter.model.TaskRecordType;
import com.aitaskcenter.model.TaskResult;
import com.aitaskcenter.repository.ProjectConfigRepository;
import com.aitaskcenter.repository.TaskConfigRepository;
import com.aitaskcenter.repository.TaskExecutionLogRepository;
import com.aitaskcenter.repository.TaskResultRepository;
import com.aitaskcenter.repository.TaskRunRepository;
import com.aitaskcenter.repository.TaskRunResultRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

class TaskResultServiceListTest {
    @Test
    void appliesCurrentValidationFilterToResultList() {
        TaskResultRepository repository = mock(TaskResultRepository.class);
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        TaskResultService service = service(repository);

        service.list(1, 10, null, null, 1L, null, TaskRecordType.VALIDATION_CURRENT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Specification<TaskResult>> specificationCaptor =
                ArgumentCaptor.forClass(Specification.class);
        verify(repository).findAll(specificationCaptor.capture(), any(Pageable.class));

        @SuppressWarnings("unchecked")
        Root<TaskResult> root = mock(Root.class);
        @SuppressWarnings("unchecked")
        Path<String> recordTypePath = mock(Path.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder builder = mock(CriteriaBuilder.class);
        when(root.<String>get("recordType")).thenReturn(recordTypePath);

        specificationCaptor.getValue().toPredicate(root, query, builder);

        verify(builder).equal(recordTypePath, TaskRecordType.VALIDATION_CURRENT);
    }

    @Test
    void allowsReadingValidationResultDetail() {
        TaskResultRepository repository = mock(TaskResultRepository.class);
        TaskResult validationResult = new TaskResult();
        validationResult.setRecordType(TaskRecordType.VALIDATION_CURRENT);
        when(repository.findById(7L)).thenReturn(Optional.of(validationResult));
        TaskResultService service = service(repository);

        assertSame(validationResult, service.get(7L));
    }

    @Test
    void allowsExecutingCurrentValidationResult() {
        TaskResultRepository repository = mock(TaskResultRepository.class);
        PythonWorkerClient pythonWorkerClient = mock(PythonWorkerClient.class);
        TaskResult validationResult = result(7L, TaskRecordType.VALIDATION_CURRENT, "PENDING");
        validationResult.setCliId("codex");
        when(repository.findById(7L)).thenReturn(Optional.of(validationResult));
        when(pythonWorkerClient.processTaskResult(7L, "codex"))
                .thenReturn(Map.of("accepted", true));

        Map<String, Object> response = service(repository, pythonWorkerClient, mock(TaskRunResultRepository.class))
                .process(7L, null);

        assertEquals(true, response.get("accepted"));
        verify(pythonWorkerClient).processTaskResult(7L, "codex");
    }

    @Test
    void rejectsExecutingHistoricalValidationResult() {
        TaskResultRepository repository = mock(TaskResultRepository.class);
        PythonWorkerClient pythonWorkerClient = mock(PythonWorkerClient.class);
        TaskResult validationResult = result(8L, TaskRecordType.VALIDATION_HISTORY, "PENDING");
        validationResult.setCliId("codex");
        when(repository.findById(8L)).thenReturn(Optional.of(validationResult));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service(repository, pythonWorkerClient, mock(TaskRunResultRepository.class)).process(8L, null));

        assertEquals("历史验证结果仅供查看，不能执行", exception.getMessage());
        verify(pythonWorkerClient, never()).processTaskResult(any(), any());
    }

    @Test
    void executesCurrentValidationBatchDirectlyWithoutCreatingTaskRun() {
        TaskResultRepository repository = mock(TaskResultRepository.class);
        PythonWorkerClient pythonWorkerClient = mock(PythonWorkerClient.class);
        TaskRunResultRepository taskRunResultRepository = mock(TaskRunResultRepository.class);
        TaskResult first = result(7L, TaskRecordType.VALIDATION_CURRENT, "PENDING");
        TaskResult second = result(8L, TaskRecordType.VALIDATION_CURRENT, "FAILED");
        when(repository.findAllById(List.of(7L, 8L))).thenReturn(List.of(first, second));
        when(taskRunResultRepository.findByTaskResultIdInOrderByIdDesc(List.of(7L, 8L)))
                .thenReturn(List.of());
        when(pythonWorkerClient.processTaskResults(List.of(7L, 8L), "codex", 2))
                .thenReturn(Map.of("mode", "validation-direct-batch"));
        BatchProcessTaskResultRequest request = new BatchProcessTaskResultRequest();
        request.setTaskResultIds(List.of(7L, 8L));
        request.setCliId("codex");
        request.setWorkerCount(2);

        Map<String, Object> response = service(repository, pythonWorkerClient, taskRunResultRepository)
                .processBatch(request);

        assertEquals("validation-direct-batch", response.get("mode"));
        verify(pythonWorkerClient).processTaskResults(List.of(7L, 8L), "codex", 2);
    }

    @Test
    void allowsDeletingCurrentValidationButProtectsHistory() {
        TaskResultRepository repository = mock(TaskResultRepository.class);
        TaskResult current = result(7L, TaskRecordType.VALIDATION_CURRENT, "PENDING");
        TaskResult history = result(8L, TaskRecordType.VALIDATION_HISTORY, "PENDING");
        when(repository.findById(7L)).thenReturn(Optional.of(current));
        when(repository.findById(8L)).thenReturn(Optional.of(history));
        TaskResultService service = service(repository);

        service.delete(7L);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.delete(8L));

        verify(repository).delete(current);
        verify(repository, never()).delete(history);
        assertEquals("历史验证结果仅供查看，不能删除", exception.getMessage());
    }

    private static TaskResultService service(TaskResultRepository repository) {
        return service(repository, mock(PythonWorkerClient.class), mock(TaskRunResultRepository.class));
    }

    private static TaskResultService service(
            TaskResultRepository repository,
            PythonWorkerClient pythonWorkerClient,
            TaskRunResultRepository taskRunResultRepository) {
        return new TaskResultService(
                repository,
                mock(ProjectConfigRepository.class),
                taskRunResultRepository,
                mock(TaskRunRepository.class),
                mock(TaskConfigRepository.class),
                mock(TaskExecutionLogRepository.class),
                pythonWorkerClient,
                mock(TaskRunPromptBuilder.class));
    }

    private static TaskResult result(Long id, String recordType, String status) {
        TaskResult result = new TaskResult();
        result.setId(id);
        result.setRecordType(recordType);
        result.setStatus(status);
        return result;
    }
}
