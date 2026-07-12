# 任务接入引导工作流设计

## 目标

将任务配置列表中的“生成结果”和“生成批次”收进任务详情抽屉，并提供一套固定、可审计、逐步解锁的接入流程。用户始终能看见完整流程，但只有上一步成功后才能执行下一步。

本设计支持任意来源表和多表任务。Codex 负责生成任务专属代码并用少量真实数据验证；平台负责状态门禁、正式生成、验证数据清理和任务列表编排。

## 已确认约束

- 不新增验证数据库、验证表或临时缓存。
- Codex 验证数据直接写入现有 `tb_task_result`、`tb_task_run` 和 `tb_task_run_result`。
- 结果验证与批次验证是两个独立闭环。
- 结果验证不得创建任务批次。
- 批次验证不得新增、修改或删除任务结果。
- 验证阶段不得启动任务、调用 AI/TTS 或回写来源业务表。
- 正式生成前，平台按任务配置和唯一验证标记精确删除验证数据。
- Web 不监听 Codex 执行过程。Codex 完成后调用回填接口；页面在重新聚焦、重新打开或手动刷新时读取最新状态。

## 用户界面

### 入口

任务配置列表移除“生成结果”和“生成批次”操作，仅保留“详情”“编辑”“删除”。点击“详情”打开右侧大抽屉，桌面宽度约为视口的 75% 至 82%，移动端全屏。

### 抽屉结构

1. 标题和任务名称。
2. 所属项目、来源表、执行工具和数据库摘要。
3. 始终可见的七节点流程图。
4. 当前步骤内容区。

七个节点依次为：

1. 定制结果代码
2. 验证任务结果
3. 生成全部结果
4. 定制批次代码
5. 验证任务批次
6. 生成全部批次
7. 任务列表可用

### 节点状态

- `COMPLETED`：绿色勾选，可点击查看只读记录，不允许重新执行。
- `ACTIVE`：紫色高亮，内容区展示当前唯一可执行操作。
- `LOCKED`：灰色锁定。点击只提示“请先完成上一步”，不执行请求。
- `FAILED`：红色错误，仍属于当前节点；内容区展示错误和重新操作入口。
- `STALE`：任务关键配置变化后，当前及下游节点失效，必须从受影响的第一个节点重新开始。

未来节点始终可见，但其按钮、表单和详情不会提前展示。

### 页面刷新

抽屉打开时读取工作流状态。浏览器窗口重新获得焦点时再次读取；页面提供手动刷新按钮。不展示“等待 Codex”页面，也不读取 Codex 日志或进度。

## 工作流状态

在 `tb_task_config` 增加三个字段，不新增工作流表：

- `onboarding_step`：当前七步枚举。
- `onboarding_status`：当前节点状态。
- `onboarding_context`：JSON 文本，保存验证标记、一次性回填令牌、代码文件、代码指纹、Codex 回填摘要和验证数据 ID。

任务配置默认状态：

```text
onboarding_step = RESULT_CODE
onboarding_status = ACTIVE
```

步骤枚举：

```text
RESULT_CODE
RESULT_VALIDATION
RESULT_GENERATION
BATCH_CODE
BATCH_VALIDATION
BATCH_GENERATION
READY
```

唯一合法迁移如下：

| 当前步骤 | 成功事件 | 下一步骤 |
| --- | --- | --- |
| `RESULT_CODE` | Codex 合法回填结果验证数据 | `RESULT_VALIDATION` |
| `RESULT_VALIDATION` | 用户确认验证结果 | `RESULT_GENERATION` |
| `RESULT_GENERATION` | 验证结果清理完成且正式结果生成成功 | `BATCH_CODE` |
| `BATCH_CODE` | Codex 合法回填验证批次 | `BATCH_VALIDATION` |
| `BATCH_VALIDATION` | 用户确认验证批次 | `BATCH_GENERATION` |
| `BATCH_GENERATION` | 验证批次清理完成且正式批次生成成功 | `READY` |

失败不会推进步骤，只把 `onboarding_status` 更新为 `FAILED` 并保存错误摘要。重试开始时恢复为 `ACTIVE`。配置变化触发 `STALE` 后，后端计算最早受影响步骤并重置该步骤及全部下游步骤；前端不能直接提交目标步骤。

## 阶段一：任务结果

### 1. 定制结果代码

后端根据任务名称、项目、数据库、已选表、表结构和任务描述生成提示词。提示词包含：

- 结果生成器代码契约。
- 当前任务配置 ID。
- 唯一 `resultValidationRunId`。
- 最多创建三条验证结果的限制。
- 验证结果必须写入 `tb_task_result`。
- `source_description` 必须精确等于 `RESULT_VALIDATION:<resultValidationRunId>`。
- `result_content._meta.validationRunId` 必须保存相同标记。
- 禁止创建 `tb_task_run` 或 `tb_task_run_result`。
- 禁止修改来源业务表。
- 完成代码、自测和验证数据插入后调用回填命令。

Codex 回填时提交代码文件、代码指纹、验证结果 ID、测试摘要和一次性令牌。后端验证：

- 当前步骤确实是 `RESULT_CODE`。
- 回填令牌匹配且未使用。
- 结果 ID 全部属于当前任务配置。
- 结果数量为一至三条。
- 每条结果的两个验证标记一致。
- 当前验证标记没有关联任何 `tb_task_run_result`。

通过后进入 `RESULT_VALIDATION`。

### 2. 验证任务结果

详情内容区直接展示验证结果名称、业务摘要、状态和 JSON 正文。用户只能：

- 确认任务结果正确。
- 返回 Codex 修复，并等待新的合法回填覆盖当前验证结果。

用户确认后进入 `RESULT_GENERATION`。此步骤不创建批次。

### 3. 生成全部结果

点击“清理并生成全部结果”后：

1. 在任务中心库事务中按 `task_config_id` 和精确 `RESULT_VALIDATION:<resultValidationRunId>` 标记删除验证结果。
2. 删除前确认验证结果没有批次关联；若存在关联则拒绝清理并停留在当前步骤。
3. 删除后再次查询并确认剩余数量为零。
4. 提交清理事务。
5. 调用现有结果生成链路生成正式结果。

正式生成成功且新增结果数大于零后进入 `BATCH_CODE`。生成失败时保留当前步骤和错误，允许重试；已经确认的结果代码不需要重新验证。

## 阶段二：任务批次

### 4. 定制批次代码

只有正式任务结果生成成功后才展示批次提示词。提示词包含：

- 批次 Payload、响应契约和回写映射代码要求。
- 当前任务配置 ID。
- 唯一 `batchValidationRunId`。
- 只能从当前任务配置的正式结果中选择少量记录。
- 只能创建一个验证 `tb_task_run` 及其 `tb_task_run_result` 关联。
- 验证批次的 `reason` 必须精确等于 `BATCH_VALIDATION:<batchValidationRunId>`。
- 批次 Prompt 的 `_meta.validationRunId` 保存相同标记。
- 禁止新增、修改或删除任何 `tb_task_result`。
- 禁止启动验证批次、调用 AI/TTS 或回写来源业务表。
- 完成代码、自测和验证批次插入后调用回填命令。

Codex 回填时提交代码文件、代码指纹、验证批次 ID、关联结果 ID、测试摘要和一次性令牌。后端验证：

- 当前步骤确实是 `BATCH_CODE`。
- 回填令牌匹配且未使用。
- 恰好有一个验证批次。
- 批次属于当前任务配置。
- 批次及 Prompt 中的验证标记一致。
- 所有关联结果都是当前任务配置的正式结果。
- 验证批次未开始执行。
- 回填前后的正式任务结果 ID 集合和数量没有变化。

通过后进入 `BATCH_VALIDATION`。

### 5. 验证任务批次

详情内容区展示验证批次名称、CLI、Prompt、关联结果和状态。用户只能：

- 确认任务批次正确。
- 返回 Codex 修复，并等待新的合法回填覆盖验证批次。

用户确认后进入 `BATCH_GENERATION`。此步骤不执行批次。

### 6. 生成全部批次

用户设置批量大小、是否包含失败结果、CLI 和任务名前缀。点击“清理验证批次并生成”后：

1. 在同一事务中按 `task_config_id`、验证批次 ID 和精确 `BATCH_VALIDATION:<batchValidationRunId>` 标记清理验证数据。
2. 删除顺序为 `tb_task_execution_log`、`tb_task_run_result`、`tb_task_run`。
3. 不删除或修改任何 `tb_task_result`。
4. 删除后再次查询并确认验证批次及关联数量为零。
5. 提交清理事务。
6. 调用现有批次生成链路创建正式批次。

正式批次生成成功且结果关联覆盖率正确后进入 `READY`。

### 7. 任务列表可用

展示正式任务结果数、正式批次数和关联结果数。主操作为“查看任务列表”，跳转后自动携带当前任务配置筛选条件。

## Codex 回填协议

新增一个脚本作为 Codex 的稳定入口：

```text
./scripts/task-workflow report \
  --task-config-id <id> \
  --stage result|batch \
  --token <one-time-token> \
  --artifact <path> \
  --artifact-hash <sha256> \
  --entity-ids <comma-separated-ids>
```

脚本调用后端回填 API。API 不接受任意步骤跳转，只允许当前状态定义的合法迁移。一次性令牌成功使用后立即失效。

## 后端 API

```text
GET  /api/task/{id}/onboarding
POST /api/task/{id}/onboarding/report
POST /api/task/{id}/onboarding/result-validation/confirm
POST /api/task/{id}/onboarding/results/generate
POST /api/task/{id}/onboarding/batch-validation/confirm
POST /api/task/{id}/onboarding/batches/generate
```

`GET onboarding` 返回任务摘要、七个节点状态、当前步骤内容、提示词、验证数据和合法操作。前端不自行推导状态迁移。

## 代码边界

- 新增 `TaskOnboardingService` 统一处理状态迁移、提示词、回填校验和清理。
- 现有 `TaskConfigService.generateResults` 和 `generateRunBatches` 继续承担正式生成；由 onboarding 服务在门禁和清理完成后调用。
- Codex 生成的业务代码继续使用现有正式表，不增加 `validation` 分支。
- 所有清理 SQL 使用平台固定语句和精确参数，Codex 不能提交或执行自定义删除 SQL。
- 验证标记不是状态真相；`onboarding_step` 和 `onboarding_status` 才是状态真相，标记仅用于验证和安全清理。

## 配置变化与失效

任务名称变化不重置工作流。以下字段变化会使定制代码或数据语义失效：

- 项目
- 数据库配置
- 已选表
- 默认 CLI
- 任务描述

保存这些字段后，工作流回到 `RESULT_CODE`，生成新的回填令牌，并把旧验证数据标记为待清理。平台只自动清理带验证标记的数据，不自动删除正式任务结果或正式任务批次。

## 错误处理

- Codex 回填不合法：拒绝状态迁移，保留当前步骤，并返回具体不一致项。
- 结果清理失败：不调用正式结果生成。
- 正式结果生成失败：停留在 `RESULT_GENERATION`，允许重试。
- 批次清理失败：不调用正式批次生成。
- 正式批次生成失败：停留在 `BATCH_GENERATION`，允许重试。
- 前端请求失败：保留当前页面内容，提供重试，不乐观更新节点状态。

## 测试策略

### 后端

- 七步合法迁移和所有非法跳转。
- 一次性回填令牌校验与重放拒绝。
- 结果回填最多三条、标记一致、无批次关联。
- 批次回填恰好一条、只关联正式结果、未改变结果集合。
- 结果验证清理只删除精确标记数据。
- 批次验证清理按外键顺序执行且不删除任务结果。
- 清理失败时不触发正式生成。
- 正式生成失败后的可重试状态。

### 前端

- 七个节点始终可见。
- 仅当前节点可操作，锁定节点不可执行。
- 已完成节点只读可回看。
- 窗口重新聚焦时刷新状态。
- 结果阶段不出现批次操作。
- 批次阶段不出现结果删除或生成操作。
- 移动端流程图可横向滚动，正文和按钮不重叠。

### 端到端

1. 模拟 Codex 回填三条结果并完成结果确认。
2. 正式生成前精确清理三条验证结果。
3. 正式生成结果后模拟 Codex 回填一个验证批次。
4. 正式生成批次前清理验证批次但保留全部正式结果。
5. 最终在任务列表看到正式批次。

## 不在本次范围

- 自动调用 Codex API。
- 实时展示 Codex 日志或进度。
- 验证阶段执行 AI、TTS 或来源库回写。
- 新建验证数据库、验证表、临时表或验证缓存。
- 自动删除正式任务结果或正式任务批次。
