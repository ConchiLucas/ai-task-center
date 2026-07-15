package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.dto.GenerateTaskRunBatchRequest;
import com.aitaskcenter.dto.TaskOnboardingNodeResponse;
import com.aitaskcenter.dto.TaskOnboardingReportRequest;
import com.aitaskcenter.dto.TaskOnboardingResponse;
import com.aitaskcenter.dto.TaskOnboardingResultSummary;
import com.aitaskcenter.dto.TaskOnboardingRunSummary;
import com.aitaskcenter.dto.TaskOnboardingTaskSummary;
import com.aitaskcenter.model.TaskConfig;
import com.aitaskcenter.model.TaskRecordType;
import com.aitaskcenter.model.TaskResult;
import com.aitaskcenter.model.TaskRun;
import com.aitaskcenter.model.TaskRunResult;
import com.aitaskcenter.repository.TaskConfigRepository;
import com.aitaskcenter.repository.TaskResultRepository;
import com.aitaskcenter.repository.TaskRunRepository;
import com.aitaskcenter.repository.TaskRunResultRepository;
import com.aitaskcenter.service.TaskConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TaskOnboardingService {
    private static final Map<OnboardingStep, String> LABELS = Map.of(
            OnboardingStep.RESULT_CODE, "结果代码准备",
            OnboardingStep.RESULT_VALIDATION, "结果手动验证",
            OnboardingStep.RESULT_GENERATION, "正式生成结果",
            OnboardingStep.BATCH_CODE, "批次代码准备",
            OnboardingStep.BATCH_VALIDATION, "批次手动验证",
            OnboardingStep.BATCH_GENERATION, "正式生成批次",
            OnboardingStep.READY, "任务就绪");

    private final TaskConfigRepository taskConfigRepository;
    private final TaskResultRepository taskResultRepository;
    private final TaskRunRepository taskRunRepository;
    private final TaskRunResultRepository taskRunResultRepository;
    private final TaskConfigService taskConfigService;
    private final TaskOnboardingPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;

    public TaskOnboardingService(
            TaskConfigRepository taskConfigRepository,
            TaskResultRepository taskResultRepository,
            TaskRunRepository taskRunRepository,
            TaskRunResultRepository taskRunResultRepository,
            TaskConfigService taskConfigService,
            TaskOnboardingPromptBuilder promptBuilder,
            ObjectMapper objectMapper) {
        this.taskConfigRepository = taskConfigRepository;
        this.taskResultRepository = taskResultRepository;
        this.taskRunRepository = taskRunRepository;
        this.taskRunResultRepository = taskRunResultRepository;
        this.taskConfigService = taskConfigService;
        this.promptBuilder = promptBuilder;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TaskOnboardingResponse get(Long taskConfigId) {
        TaskConfig task = loadForUpdate(taskConfigId);
        Map<String, Object> context = context(task);
        ensureCodeToken(task, context);
        saveContext(task, context);
        return assemble(task, context);
    }

    @Transactional
    public TaskOnboardingResponse report(Long taskConfigId, TaskOnboardingReportRequest request) {
        TaskConfig task = loadForUpdate(taskConfigId);
        Map<String, Object> context = context(task);
        if (request == null || !"CODE_READY".equals(request.getStatus())) {
            throw new IllegalArgumentException("回填状态必须是 CODE_READY");
        }
        OnboardingStep current = step(task);
        if (current == OnboardingStep.RESULT_CODE) {
            requireStageAndToken(request, "result", text(context.get("resultCodeToken")));
            task.setOnboardingStep(OnboardingStep.RESULT_VALIDATION.name());
            context.remove("resultCodeToken");
            context.put("resultCodeReadyAt", OffsetDateTime.now().toString());
        } else if (current == OnboardingStep.BATCH_CODE) {
            requireStageAndToken(request, "batch", text(context.get("batchCodeToken")));
            task.setOnboardingStep(OnboardingStep.BATCH_VALIDATION.name());
            context.remove("batchCodeToken");
            context.put("batchCodeReadyAt", OffsetDateTime.now().toString());
        } else if ((current == OnboardingStep.RESULT_VALIDATION && "result".equals(request.getStage()))
                || (current == OnboardingStep.BATCH_VALIDATION && "batch".equals(request.getStage()))) {
            return assemble(task, context);
        } else {
            throw new IllegalArgumentException("当前步骤不接受代码完成回填");
        }
        clearError(context);
        saveContext(task, context);
        return assemble(task, context);
    }

    @Transactional
    public TaskOnboardingResponse generateResultValidation(Long taskConfigId) {
        TaskConfig task = requireStep(taskConfigId, OnboardingStep.RESULT_VALIDATION);
        Map<String, Object> result = taskConfigService.generateResults(
                taskConfigId, false, TaskRecordType.VALIDATION_CURRENT, 3);
        Map<String, Object> context = context(task);
        context.put("lastResultValidationCount", number(result.get("insertedCount")));
        clearError(context);
        saveContext(task, context);
        return assemble(task, context);
    }

    @Transactional
    public TaskOnboardingResponse confirmResultValidation(Long taskConfigId) {
        TaskConfig task = requireStep(taskConfigId, OnboardingStep.RESULT_VALIDATION);
        if (taskResultRepository.countByTaskConfigIdAndRecordType(
                taskConfigId, TaskRecordType.VALIDATION_CURRENT) == 0) {
            throw new IllegalArgumentException("请先生成小批量验证结果");
        }
        task.setOnboardingStep(OnboardingStep.RESULT_GENERATION.name());
        taskConfigRepository.save(task);
        return assemble(task, context(task));
    }

    @Transactional
    public TaskOnboardingResponse generateResults(Long taskConfigId) {
        TaskConfig task = requireStep(taskConfigId, OnboardingStep.RESULT_GENERATION);
        Map<String, Object> result = taskConfigService.generateResults(
                taskConfigId, false, TaskRecordType.FORMAL, null);
        task.setOnboardingStep(OnboardingStep.BATCH_CODE.name());
        Map<String, Object> context = context(task);
        context.put("formalResultCount", number(result.get("insertedCount")));
        ensureCodeToken(task, context);
        clearError(context);
        saveContext(task, context);
        return assemble(task, context);
    }

    @Transactional
    public TaskOnboardingResponse generateBatchValidation(
            Long taskConfigId, GenerateTaskRunBatchRequest request) {
        TaskConfig task = requireStep(taskConfigId, OnboardingStep.BATCH_VALIDATION);
        GenerateTaskRunBatchRequest effective = request == null ? validationBatchRequest(task) : request;
        if (effective.getBatchSize() == null) {
            effective.setBatchSize(3);
        }
        Map<String, Object> result = taskConfigService.generateValidationRunBatch(taskConfigId, effective);
        Map<String, Object> context = context(task);
        context.put("lastBatchValidationCount", number(result.get("createdRunCount")));
        clearError(context);
        saveContext(task, context);
        return assemble(task, context);
    }

    @Transactional
    public TaskOnboardingResponse confirmBatchValidation(Long taskConfigId) {
        TaskConfig task = requireStep(taskConfigId, OnboardingStep.BATCH_VALIDATION);
        if (taskRunRepository.countByTaskConfigIdAndRecordType(
                taskConfigId, TaskRecordType.VALIDATION_CURRENT) == 0) {
            throw new IllegalArgumentException("请先生成验证批次");
        }
        task.setOnboardingStep(OnboardingStep.BATCH_GENERATION.name());
        taskConfigRepository.save(task);
        return assemble(task, context(task));
    }

    @Transactional
    public TaskOnboardingResponse generateBatches(
            Long taskConfigId, GenerateTaskRunBatchRequest request) {
        TaskConfig task = requireStep(taskConfigId, OnboardingStep.BATCH_GENERATION);
        Map<String, Object> result = taskConfigService.generateRunBatches(taskConfigId, request);
        task.setOnboardingStep(OnboardingStep.READY.name());
        task.setOnboardingStatus("COMPLETED");
        Map<String, Object> context = context(task);
        context.put("formalRunCount", number(result.get("createdRunCount")));
        context.put("formalLinkedResultCount", number(result.get("linkedResultCount")));
        clearError(context);
        saveContext(task, context);
        return assemble(task, context);
    }

    private TaskOnboardingResponse assemble(TaskConfig task, Map<String, Object> context) {
        OnboardingStep current = step(task);
        List<TaskResult> validationResults = taskResultRepository
                .findByTaskConfigIdAndRecordTypeOrderByIdAsc(task.getId(), TaskRecordType.VALIDATION_CURRENT);
        List<TaskRun> validationRuns = taskRunRepository
                .findByTaskConfigIdAndRecordTypeOrderByIdDesc(task.getId(), TaskRecordType.VALIDATION_CURRENT);
        TaskRun validationRun = validationRuns.isEmpty() ? null : validationRuns.get(0);
        List<TaskResult> validationRunResults = validationRun == null
                ? List.of()
                : linkedResults(validationRun.getId());

        TaskOnboardingResponse response = new TaskOnboardingResponse();
        response.setTask(TaskOnboardingTaskSummary.from(task));
        response.setCurrentStep(current.name());
        response.setCurrentStatus(task.getOnboardingStatus());
        response.setNodes(nodes(current));
        response.setValidationResults(validationResults.stream().map(TaskOnboardingResultSummary::from).toList());
        response.setValidationRun(validationRun == null ? null : TaskOnboardingRunSummary.from(validationRun));
        response.setValidationRunResults(validationRunResults.stream().map(TaskOnboardingResultSummary::from).toList());
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("validationResultCount", (long) validationResults.size());
        counts.put("validationRunCount", (long) validationRuns.size());
        counts.put("formalResultCount", taskResultRepository.countByTaskConfigIdAndRecordType(task.getId(), TaskRecordType.FORMAL));
        counts.put("formalRunCount", taskRunRepository.countByTaskConfigIdAndRecordType(task.getId(), TaskRecordType.FORMAL));
        counts.put("insertedCount", number(context.get("formalResultCount")));
        counts.put("createdRunCount", number(context.get("formalRunCount")));
        counts.put("linkedResultCount", number(context.get("formalLinkedResultCount")));
        response.setCounts(counts);
        response.setAllowedActions(allowedActions(current, !validationResults.isEmpty(), validationRun != null));
        response.setErrorMessage(text(context.get("errorMessage")));
        if (current == OnboardingStep.RESULT_CODE) {
            response.setPrompt(promptBuilder.build(task, current, text(context.get("resultCodeToken"))));
        } else if (current == OnboardingStep.BATCH_CODE) {
            response.setPrompt(promptBuilder.build(task, current, text(context.get("batchCodeToken"))));
        }
        return response;
    }

    private List<TaskResult> linkedResults(Long taskRunId) {
        List<Long> ids = taskRunResultRepository.findByTaskRunIdOrderByIdAsc(taskRunId).stream()
                .map(TaskRunResult::getTaskResultId)
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, TaskResult> byId = new LinkedHashMap<>();
        taskResultRepository.findAllById(ids).forEach(result -> byId.put(result.getId(), result));
        return ids.stream().map(byId::get).filter(item -> item != null).toList();
    }

    private List<TaskOnboardingNodeResponse> nodes(OnboardingStep current) {
        List<TaskOnboardingNodeResponse> nodes = new ArrayList<>();
        for (OnboardingStep candidate : OnboardingStep.values()) {
            String state;
            if (candidate.ordinal() < current.ordinal()) {
                state = "COMPLETED";
            } else if (candidate == current) {
                state = candidate == OnboardingStep.READY ? "COMPLETED" : "ACTIVE";
            } else {
                state = "LOCKED";
            }
            nodes.add(new TaskOnboardingNodeResponse(candidate.name(), LABELS.get(candidate), state));
        }
        return nodes;
    }

    private List<String> allowedActions(OnboardingStep step, boolean hasResults, boolean hasRun) {
        return switch (step) {
            case RESULT_CODE, BATCH_CODE -> List.of("COPY_PROMPT");
            case RESULT_VALIDATION -> hasResults
                    ? List.of("GENERATE_RESULT_VALIDATION", "CONFIRM_RESULT_VALIDATION")
                    : List.of("GENERATE_RESULT_VALIDATION");
            case RESULT_GENERATION -> List.of("GENERATE_RESULTS");
            case BATCH_VALIDATION -> hasRun
                    ? List.of("GENERATE_BATCH_VALIDATION", "CONFIRM_BATCH_VALIDATION")
                    : List.of("GENERATE_BATCH_VALIDATION");
            case BATCH_GENERATION -> List.of("GENERATE_BATCHES");
            case READY -> List.of();
        };
    }

    private void ensureCodeToken(TaskConfig task, Map<String, Object> context) {
        OnboardingStep current = step(task);
        String key = current == OnboardingStep.RESULT_CODE
                ? "resultCodeToken"
                : current == OnboardingStep.BATCH_CODE ? "batchCodeToken" : null;
        if (key != null && !StringUtils.hasText(text(context.get(key)))) {
            context.put(key, UUID.randomUUID().toString());
        }
    }

    private void requireStageAndToken(TaskOnboardingReportRequest request, String stage, String expectedToken) {
        if (!stage.equals(request.getStage())) {
            throw new IllegalArgumentException("回填阶段不正确");
        }
        if (!StringUtils.hasText(expectedToken) || !expectedToken.equals(request.getToken())) {
            throw new IllegalArgumentException("回填 token 无效或已使用");
        }
    }

    private TaskConfig requireStep(Long taskConfigId, OnboardingStep expected) {
        TaskConfig task = loadForUpdate(taskConfigId);
        if (step(task) != expected) {
            throw new IllegalArgumentException("当前步骤不能执行该操作");
        }
        return task;
    }

    private TaskConfig loadForUpdate(Long taskConfigId) {
        if (taskConfigId == null) {
            throw new IllegalArgumentException("缺少任务配置 ID");
        }
        return taskConfigRepository.findByIdForUpdate(taskConfigId)
                .orElseThrow(() -> new IllegalArgumentException("任务配置不存在"));
    }

    private OnboardingStep step(TaskConfig task) {
        try {
            return OnboardingStep.valueOf(task.getOnboardingStep());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("任务引导步骤无效: " + task.getOnboardingStep(), ex);
        }
    }

    private Map<String, Object> context(TaskConfig task) {
        if (!StringUtils.hasText(task.getOnboardingContext())) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(objectMapper.readValue(
                    task.getOnboardingContext(), new TypeReference<Map<String, Object>>() { }));
        } catch (Exception ex) {
            throw new IllegalArgumentException("任务引导上下文 JSON 无效", ex);
        }
    }

    private void saveContext(TaskConfig task, Map<String, Object> context) {
        try {
            task.setOnboardingContext(objectMapper.writeValueAsString(context));
            taskConfigRepository.save(task);
        } catch (Exception ex) {
            throw new IllegalArgumentException("保存任务引导上下文失败", ex);
        }
    }

    private static GenerateTaskRunBatchRequest validationBatchRequest(TaskConfig task) {
        GenerateTaskRunBatchRequest request = new GenerateTaskRunBatchRequest();
        request.setBatchSize(3);
        request.setCliId(task.getCliId());
        request.setTaskNamePrefix(task.getTaskName() + " - 验证");
        request.setIncludeFailed(false);
        return request;
    }

    private static void clearError(Map<String, Object> context) {
        context.remove("errorMessage");
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
