package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.dto.GenerateTaskRunBatchRequest;
import com.aitaskcenter.dto.TaskOnboardingReportRequest;
import com.aitaskcenter.dto.TaskOnboardingResponse;
import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.repository.TaskConfigRepository;
import com.aitaskcenter.service.TaskConfigService;
import java.security.SecureRandom;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TaskOnboardingService {
    private static final List<OnboardingStep> STEPS = List.of(OnboardingStep.values());
    private static final Map<OnboardingStep, OnboardingStep> TRANSITIONS = transitions();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final TaskConfigRepository taskConfigRepository;
    private final TaskOnboardingContextCodec contextCodec;
    private final TaskOnboardingSnapshotService snapshotService;
    private final TaskOnboardingCallbackValidator callbackValidator;
    private final TaskOnboardingResponseAssembler responseAssembler;
    private final TaskOnboardingChildTableLock childTableLock;
    private final ObjectFactory<TaskOnboardingGenerationPhaseService> generationPhaseServiceProvider;
    private final ObjectFactory<TaskConfigService> taskConfigServiceProvider;

    @Autowired
    public TaskOnboardingService(
            TaskConfigRepository taskConfigRepository,
            TaskOnboardingContextCodec contextCodec,
            TaskOnboardingSnapshotService snapshotService,
            TaskOnboardingCallbackValidator callbackValidator,
            TaskOnboardingResponseAssembler responseAssembler,
            TaskOnboardingChildTableLock childTableLock,
            ObjectFactory<TaskOnboardingGenerationPhaseService> generationPhaseServiceProvider,
            ObjectFactory<TaskConfigService> taskConfigServiceProvider) {
        this.taskConfigRepository = taskConfigRepository;
        this.contextCodec = contextCodec;
        this.snapshotService = snapshotService;
        this.callbackValidator = callbackValidator;
        this.responseAssembler = responseAssembler;
        this.childTableLock = childTableLock;
        this.generationPhaseServiceProvider = generationPhaseServiceProvider;
        this.taskConfigServiceProvider = taskConfigServiceProvider;
    }

    TaskOnboardingService(
            TaskConfigRepository taskConfigRepository,
            TaskOnboardingContextCodec contextCodec,
            TaskOnboardingSnapshotService snapshotService,
            TaskOnboardingCallbackValidator callbackValidator,
            TaskOnboardingResponseAssembler responseAssembler,
            TaskOnboardingChildTableLock childTableLock,
            TaskOnboardingCleanupService cleanupService,
            TaskConfigService taskConfigService) {
        this(
                taskConfigRepository,
                contextCodec,
                snapshotService,
                callbackValidator,
                responseAssembler,
                childTableLock,
                () -> new TaskOnboardingGenerationPhaseService(
                        taskConfigRepository,
                        contextCodec,
                        cleanupService,
                        snapshotService,
                        responseAssembler),
                () -> taskConfigService);
    }

    @Transactional
    public TaskOnboardingResponse get(Long taskConfigId) {
        TaskConfig task = loadTaskForUpdate(taskConfigId);
        TaskOnboardingContext context = contextCodec.read(task);
        if (status(task) == OnboardingStatus.ACTIVE && initializeCodeStep(task, context)) {
            saveContext(task, context);
        }
        return responseAssembler.assemble(task, context);
    }

    @Transactional
    public TaskOnboardingResponse report(Long taskConfigId, TaskOnboardingReportRequest request) {
        TaskConfig task = loadTaskForUpdate(taskConfigId);
        requireActive(task);
        TaskOnboardingContext context = contextCodec.read(task);
        OnboardingStep step = step(task);

        childTableLock.lockForCallbackValidation();

        if (step == OnboardingStep.RESULT_CODE) {
            callbackValidator.validateResult(task, context, request);
            advance(task, OnboardingStep.RESULT_CODE, OnboardingStep.RESULT_VALIDATION);
        } else if (step == OnboardingStep.BATCH_CODE) {
            callbackValidator.validateBatch(task, context, request);
            advance(task, OnboardingStep.BATCH_CODE, OnboardingStep.BATCH_VALIDATION);
        } else {
            throw new IllegalArgumentException("The current onboarding step does not accept callbacks");
        }

        context.setErrorMessage("");
        saveContext(task, context);
        return responseAssembler.assemble(task, context);
    }

    @Transactional
    public TaskOnboardingResponse confirmResultValidation(Long taskConfigId) {
        return confirm(taskConfigId, OnboardingStep.RESULT_VALIDATION, OnboardingStep.RESULT_GENERATION);
    }

    @Transactional
    public TaskOnboardingResponse confirmBatchValidation(Long taskConfigId) {
        return confirm(taskConfigId, OnboardingStep.BATCH_VALIDATION, OnboardingStep.BATCH_GENERATION);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TaskOnboardingResponse generateResults(Long taskConfigId) {
        TaskOnboardingGenerationPhaseService phases = generationPhaseServiceProvider.getObject();
        TaskOnboardingResponse completed = phases.completedResultResponse(taskConfigId);
        if (completed != null) {
            return completed;
        }
        phases.reactivateGenerationRetry(taskConfigId, OnboardingStep.RESULT_GENERATION);
        TaskOnboardingGenerationPhaseService.GenerationAttempt attempt = phases.prepareResult(taskConfigId);

        try {
            Map<String, Object> generationResult = taskConfigServiceProvider.getObject().generateResults(
                    taskConfigId,
                    attempt.overwriteExistingFormalResults(),
                    attempt.generationId());
            return phases.completeResult(
                    taskConfigId, attempt.generationId(), count(generationResult, "insertedCount"));
        } catch (RuntimeException ex) {
            TaskOnboardingResponse winner = phases.recordGenerationFailure(
                    taskConfigId,
                    OnboardingStep.RESULT_GENERATION,
                    attempt.generationId(),
                    rootMessage(ex));
            if (winner != null) {
                return winner;
            }
            throw new TaskOnboardingStateException("Formal result generation failed", ex);
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TaskOnboardingResponse generateBatches(
            Long taskConfigId, GenerateTaskRunBatchRequest request) {
        TaskOnboardingGenerationPhaseService phases = generationPhaseServiceProvider.getObject();
        TaskOnboardingResponse completed = phases.completedBatchResponse(taskConfigId);
        if (completed != null) {
            return completed;
        }
        phases.reactivateGenerationRetry(taskConfigId, OnboardingStep.BATCH_GENERATION);
        TaskOnboardingGenerationPhaseService.GenerationAttempt attempt = phases.prepareBatch(taskConfigId);

        try {
            Map<String, Object> generationResult = taskConfigServiceProvider.getObject()
                    .generateRunBatches(
                            taskConfigId, request, attempt.generationId(), attempt.expectedResultIds());
            return phases.completeBatch(
                    taskConfigId,
                    attempt.generationId(),
                    count(generationResult, "createdRunCount"),
                    count(generationResult, "linkedResultCount"));
        } catch (RuntimeException ex) {
            TaskOnboardingResponse winner = phases.recordGenerationFailure(
                    taskConfigId,
                    OnboardingStep.BATCH_GENERATION,
                    attempt.generationId(),
                    rootMessage(ex));
            if (winner != null) {
                return winner;
            }
            throw new TaskOnboardingStateException("Formal batch generation failed", ex);
        }
    }

    private TaskOnboardingResponse confirm(
            Long taskConfigId, OnboardingStep expected, OnboardingStep target) {
        TaskConfig task = loadTaskForUpdate(taskConfigId);
        requireActive(task);
        TaskOnboardingContext context = contextCodec.read(task);
        advance(task, expected, target);
        context.setErrorMessage("");
        saveContext(task, context);
        return responseAssembler.assemble(task, context);
    }

    private boolean initializeCodeStep(TaskConfig task, TaskOnboardingContext context) {
        OnboardingStep step = step(task);
        if (step == OnboardingStep.RESULT_CODE
                && !StringUtils.hasText(context.getResultValidationRunId())) {
            snapshotService.capture(task.getId(), "RESULT", context);
            context.setResultValidationRunId(newOpaqueValue());
            context.setResultReportToken(newOpaqueValue());
            return true;
        }
        if (step == OnboardingStep.BATCH_CODE
                && !StringUtils.hasText(context.getBatchValidationMarker())) {
            snapshotService.capture(task.getId(), "BATCH", context);
            context.setBatchValidationMarker(newOpaqueValue());
            context.setBatchReportToken(newOpaqueValue());
            return true;
        }
        return false;
    }

    private void saveContext(TaskConfig task, TaskOnboardingContext context) {
        task.setOnboardingContext(contextCodec.write(context));
        taskConfigRepository.save(task);
    }

    private TaskConfig loadTaskForUpdate(Long taskConfigId) {
        if (taskConfigId == null) {
            throw new IllegalArgumentException("Missing task configuration ID");
        }
        return taskConfigRepository.findByIdForUpdate(taskConfigId)
                .orElseThrow(() -> new IllegalArgumentException("Task configuration does not exist"));
    }

    private void requireActive(TaskConfig task) {
        if (status(task) != OnboardingStatus.ACTIVE) {
            throw new TaskOnboardingStateException(
                    "Task onboarding status must be ACTIVE for this operation");
        }
    }

    private void advance(TaskConfig task, OnboardingStep expected, OnboardingStep target) {
        OnboardingStep current = step(task);
        if (current != expected || TRANSITIONS.get(current) != target) {
            throw new IllegalArgumentException("Invalid onboarding transition from " + current + " to " + target);
        }
        task.setOnboardingStep(target.name());
    }

    private static OnboardingStep step(TaskConfig task) {
        try {
            return OnboardingStep.valueOf(task.getOnboardingStep());
        } catch (RuntimeException ex) {
            throw new TaskOnboardingStateException("Invalid onboarding step: " + task.getOnboardingStep(), ex);
        }
    }

    private static OnboardingStatus status(TaskConfig task) {
        try {
            return OnboardingStatus.valueOf(task.getOnboardingStatus());
        } catch (RuntimeException ex) {
            throw new TaskOnboardingStateException("Invalid onboarding status: " + task.getOnboardingStatus(), ex);
        }
    }

    private static String newOpaqueValue() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static long count(Map<String, Object> result, String key) {
        Object value = result == null ? null : result.get(key);
        if (!(value instanceof Number number)) {
            throw new TaskOnboardingStateException("Generation response is missing numeric " + key);
        }
        return number.longValue();
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    private static Map<OnboardingStep, OnboardingStep> transitions() {
        Map<OnboardingStep, OnboardingStep> transitions = new EnumMap<>(OnboardingStep.class);
        for (int index = 0; index < STEPS.size() - 1; index++) {
            transitions.put(STEPS.get(index), STEPS.get(index + 1));
        }
        return Map.copyOf(transitions);
    }
}
