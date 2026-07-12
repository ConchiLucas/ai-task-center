import axios from 'axios';

export interface ApiResponse<T> {
  code: number;
  data: T;
  msg: string;
}

export interface PageResult<T> {
  list: T[];
  total: number;
  page: number;
  pageSize: number;
}

export interface ProjectConfig {
  ID: number;
  projectName: string;
  projectDesc?: string;
  userName?: string;
  CreatedAt?: string;
}

export interface ConnectionConfig {
  ID?: number;
  connectionName: string;
  connectionType: string;
  connectionUrl: string;
  connectionGroup: string;
  databaseName: string;
  port: number;
  dbLoginName: string;
  dbLoginPassword?: string;
  userName?: string;
  envName?: string;
  CreatedAt?: string;
  UpdatedAt?: string;
}

export interface AIProviderConfigItem {
  id: string;
  label: string;
  type: 'openai-compatible' | 'anthropic-compatible';
  base_url: string;
  api_key: string;
  model: string;
  max_tokens: number;
  active?: boolean;
}

export interface AIConfigResponse {
  active: string;
  providers: AIProviderConfigItem[];
}

export interface LocalCliConfigItem {
  enabled: boolean;
  id: string;
  label: string;
  command: string;
  defaultArgs: string[];
  model?: string;
  reasoningEffort?: string;
  workingDirectory: string;
  timeoutSeconds: number;
  active?: boolean;
}

export interface LocalCliConfig {
  active: string;
  configs: LocalCliConfigItem[];
}

export interface TaskConfig {
  ID?: number;
  taskName: string;
  projectId: number;
  cliId: string;
  databaseConfigId?: number | null;
  selectedTables?: string;
  taskDesc?: string;
  CreatedAt?: string;
  UpdatedAt?: string;
}

export type TaskRunStatus = 'PENDING' | 'QUEUED' | 'RUNNING' | 'RETRY_WAIT' | 'SUCCESS' | 'FAILED' | 'CANCELLED';
export type TaskResultStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED';

export interface TaskRun {
  ID?: number;
  taskName: string;
  taskConfigId?: number;
  projectId: number;
  cliId: string;
  databaseConfigId?: number | null;
  selectedTables?: string;
  status: TaskRunStatus;
  startTime?: string;
  endTime?: string;
  durationSeconds?: number;
  reason?: string;
  logPath?: string;
  runLog?: string;
  aiPromptJson?: string;
  aiResponseJson?: string;
  executionMode?: string;
  requestedWorkerCount?: number;
  dispatchGroupId?: string;
  attemptNo?: number;
  maxAttempts?: number;
  nextRetryAt?: string;
  leaseUntil?: string;
  workerId?: string;
  heartbeatAt?: string;
  expectedResultCount?: number;
  CreatedAt?: string;
  UpdatedAt?: string;
}

export interface TaskRunReference {
  ID: number;
  taskName: string;
  status: TaskRunStatus;
}

export interface TaskResult {
  ID?: number;
  resultName: string;
  taskRunId?: number;
  taskConfigId?: number;
  projectId: number;
  cliId?: string;
  databaseConfigId?: number | null;
  sourceTables?: string;
  sourceDescription?: string;
  status: TaskResultStatus;
  summary?: string;
  resultContent?: string;
  errorMessage?: string;
  parsedAt?: string;
  completedAt?: string;
  relatedTaskRuns?: TaskRunReference[];
  CreatedAt?: string;
  UpdatedAt?: string;
}

export interface TaskRunResultLink {
  ID?: number;
  taskRunId: number;
  taskResultId: number;
  status: TaskResultStatus | TaskRunStatus | string;
  errorMessage?: string;
  CreatedAt?: string;
  UpdatedAt?: string;
}

export interface TaskExecutionLog {
  ID?: number;
  taskRunId: number;
  attemptNo: number;
  cliId: string;
  executionMode: string;
  workerCount: number;
  status: TaskRunStatus;
  startTime?: string;
  endTime?: string;
  durationSeconds?: number;
  reason?: string;
  runLog?: string;
  aiPromptJson?: string;
  aiResponseJson?: string;
  CreatedAt?: string;
  UpdatedAt?: string;
}

export interface TaskRunDetail {
  taskRun: TaskRun;
  links: TaskRunResultLink[];
  taskResults: TaskResult[];
  executions: TaskExecutionLog[];
}

export interface GenerateTaskResultsResponse {
  accepted: boolean;
  mode: string;
  taskConfigId: number;
  sourceTable: string;
  scriptPath: string;
  totalGroups: number;
  insertedCount: number;
  skippedCount: number;
  deletedCount: number;
  overwrite: boolean;
}

export interface GenerateTaskRunBatchesResponse {
  createdRunCount: number;
  linkedResultCount: number;
  batchSize: number;
  statusCount?: number;
  message?: string;
}

const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || 'http://127.0.0.1:18743/api',
  timeout: 120000,
});

request.interceptors.response.use((response) => {
  const result = response.data as ApiResponse<unknown>;
  if (typeof result?.code === 'number' && result.code !== 0) {
    throw new Error(result.msg || '请求失败');
  }
  return response;
});

// 函数：getProjects
export async function getProjects() {
  const res = await request.get<ApiResponse<ProjectConfig[]>>('/project/getTbInterfaceProjectList');
  return res.data.data || [];
}

// 函数：createProject
export async function createProject(data: Partial<ProjectConfig>) {
  const res = await request.post<ApiResponse<ProjectConfig>>('/project/createTbInterfaceProject', data);
  return res.data.data;
}

// 函数：updateProject
export async function updateProject(data: Partial<ProjectConfig> & { ID: number }) {
  const res = await request.put<ApiResponse<ProjectConfig>>('/project/updateTbInterfaceProject', data);
  return res.data.data;
}

// 函数：deleteProject
export async function deleteProject(id: number) {
  await request.delete<ApiResponse<void>>('/project/deleteTbInterfaceProject', { data: { ID: id } });
}

// 函数：getConnections
export async function getConnections(params: {
  page?: number;
  pageSize?: number;
  connectionGroup?: string;
  envName?: string;
}) {
  const res = await request.get<ApiResponse<PageResult<ConnectionConfig>>>('/connection/getTbConnectionList', {
    params: { page: 1, pageSize: 999, ...params },
  });
  return res.data.data;
}

// 函数：createConnection
export async function createConnection(data: Partial<ConnectionConfig>) {
  const res = await request.post<ApiResponse<ConnectionConfig>>('/connection/createTbConnection', data);
  return res.data.data;
}

// 函数：updateConnection
export async function updateConnection(data: Partial<ConnectionConfig> & { ID: number }) {
  const res = await request.put<ApiResponse<ConnectionConfig>>('/connection/updateTbConnection', data);
  return res.data.data;
}

// 函数：deleteConnection
export async function deleteConnection(id: number) {
  await request.delete<ApiResponse<void>>('/connection/deleteTbConnection', { data: { ID: id } });
}

// 函数：testConnectionPayload
export async function testConnectionPayload(data: Partial<ConnectionConfig>) {
  await request.post<ApiResponse<void>>('/connection/testConnectionPayload', data);
}

// 函数：listConnectionTables
export async function listConnectionTables(id: number) {
  const res = await request.get<ApiResponse<string[]>>('/connection/listTables', { params: { ID: id } });
  return res.data.data || [];
}

// 函数：getAIConfig
export async function getAIConfig() {
  const res = await request.get<ApiResponse<AIConfigResponse>>('/ai/config');
  return res.data.data || { active: '', providers: [] };
}

// 函数：saveAIConfig
export async function saveAIConfig(data: AIConfigResponse) {
  const res = await request.post<ApiResponse<AIProviderConfigItem[]>>('/ai/config', data);
  return res.data.data || [];
}

// 函数：saveAIActiveProvider
export async function saveAIActiveProvider(active: string) {
  const res = await request.post<ApiResponse<AIProviderConfigItem[]>>('/ai/config/active', { active });
  return res.data.data || [];
}

// 函数：getLocalCliConfig
export async function getLocalCliConfig() {
  const res = await request.get<ApiResponse<LocalCliConfig>>('/ai/cli/config');
  return res.data.data;
}

// 函数：saveLocalCliConfig
export async function saveLocalCliConfig(data: LocalCliConfig) {
  const res = await request.post<ApiResponse<LocalCliConfig>>('/ai/cli/config', data);
  return res.data.data;
}

// 函数：getTaskConfigs
export async function getTaskConfigs(projectId?: number | null) {
  const res = await request.get<ApiResponse<TaskConfig[]>>('/task/getTaskConfigList', {
    params: projectId ? { projectId } : undefined,
  });
  return res.data.data || [];
}

// 函数：createTaskConfig
export async function createTaskConfig(data: Partial<TaskConfig>) {
  const res = await request.post<ApiResponse<TaskConfig>>('/task/createTaskConfig', data);
  return res.data.data;
}

// 函数：updateTaskConfig
export async function updateTaskConfig(data: Partial<TaskConfig> & { ID: number }) {
  const res = await request.put<ApiResponse<TaskConfig>>('/task/updateTaskConfig', data);
  return res.data.data;
}

// 函数：deleteTaskConfig
export async function deleteTaskConfig(id: number) {
  await request.delete<ApiResponse<void>>('/task/deleteTaskConfig', { data: { ID: id } });
}

// 函数：generateTaskResults
export async function generateTaskResults(id: number, overwrite = false) {
  const res = await request.post<ApiResponse<GenerateTaskResultsResponse>>(`/task/${id}/generate-results`, null, {
    params: { overwrite },
  });
  return res.data.data;
}

// 函数：generateTaskRunBatches
export async function generateTaskRunBatches(id: number, data: {
  batchSize: number;
  cliId: string;
  taskNamePrefix: string;
  includeFailed: boolean;
}) {
  const res = await request.post<ApiResponse<GenerateTaskRunBatchesResponse>>(`/task/${id}/generate-run-batches`, data);
  return res.data.data;
}

// 函数：getTaskRuns
export async function getTaskRuns(params?: {
  taskName?: string;
  projectId?: number;
  cliId?: string;
  status?: string;
}) {
  const res = await request.get<ApiResponse<TaskRun[]>>('/task-run/list', { params });
  return res.data.data || [];
}

// 函数：createTaskRun
export async function createTaskRun(data: { taskConfigId: number; taskName?: string }) {
  const res = await request.post<ApiResponse<TaskRun>>('/task-run/create', data);
  return res.data.data;
}

// 函数：startTaskRuns
export async function startTaskRuns(data: {
  taskRunIds: number[];
  cliId: string;
  executionMode: string;
  workerCount: number;
}) {
  const res = await request.post<ApiResponse<Record<string, unknown>>>('/task-run/start', data);
  return res.data.data;
}

// 函数：retryTaskRun
export async function retryTaskRun(id: number) {
  const res = await request.post<ApiResponse<TaskRun>>(`/task-run/${id}/retry`);
  return res.data.data;
}

// 函数：cancelTaskRun
export async function cancelTaskRun(id: number) {
  const res = await request.post<ApiResponse<TaskRun>>(`/task-run/${id}/cancel`);
  return res.data.data;
}

// 函数：getTaskRunLog
export async function getTaskRunLog(id: number) {
  const res = await request.get<ApiResponse<TaskRun>>(`/task-run/${id}/log`);
  return res.data.data;
}

// 函数：getTaskRunDetail
export async function getTaskRunDetail(id: number) {
  const res = await request.get<ApiResponse<TaskRunDetail>>(`/task-run/${id}/detail`);
  return res.data.data;
}

// 函数：deleteTaskRun
export async function deleteTaskRun(id: number) {
  await request.delete<ApiResponse<void>>('/task-run/delete', { data: { ID: id } });
}

// 函数：batchDeleteTaskRuns
export async function batchDeleteTaskRuns(ids: number[]) {
  await request.delete<ApiResponse<void>>('/task-run/batchDelete', { data: { ids } });
}

// 函数：getTaskResults
export async function getTaskResults(params?: {
  page?: number;
  pageSize?: number;
  resultName?: string;
  projectId?: number;
  taskConfigId?: number;
  status?: string;
}) {
  const res = await request.get<ApiResponse<PageResult<TaskResult>>>('/task-result/list', { params });
  return res.data.data || { list: [], total: 0, page: 1, pageSize: params?.pageSize || 10 };
}

// 函数：getTaskResult
export async function getTaskResult(id: number) {
  const res = await request.get<ApiResponse<TaskResult>>(`/task-result/${id}`);
  return res.data.data;
}

// 函数：processTaskResult
export async function processTaskResult(id: number, cliId?: string) {
  const res = await request.post<ApiResponse<Record<string, unknown>>>(`/task-result/${id}/process`, null, {
    params: { cliId },
  });
  return res.data.data;
}

// 函数：batchProcessTaskResults
export async function batchProcessTaskResults(data: {
  taskResultIds: number[];
  cliId: string;
  workerCount: number;
}) {
  const res = await request.post<ApiResponse<Record<string, unknown>>>('/task-result/batch-process', data);
  return res.data.data;
}

// 函数：createTaskResult
export async function createTaskResult(data: Partial<TaskResult>) {
  const res = await request.post<ApiResponse<TaskResult>>('/task-result/create', data);
  return res.data.data;
}

// 函数：updateTaskResult
export async function updateTaskResult(data: Partial<TaskResult> & { ID: number }) {
  const res = await request.put<ApiResponse<TaskResult>>('/task-result/update', data);
  return res.data.data;
}

// 函数：deleteTaskResult
export async function deleteTaskResult(id: number) {
  await request.delete<ApiResponse<void>>('/task-result/delete', { data: { ID: id } });
}
