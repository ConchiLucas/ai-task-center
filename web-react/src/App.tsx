import {
  ApiOutlined,
  CheckCircleFilled,
  CloudServerOutlined,
  CodeOutlined,
  DatabaseOutlined,
  DeleteOutlined,
  EditOutlined,
  EyeInvisibleOutlined,
  EyeOutlined,
  FileTextOutlined,
  PlusOutlined,
  ReloadOutlined,
  RobotOutlined,
  SaveOutlined,
  SearchOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import {
  App as AntApp,
  Button,
  Card,
  Col,
  Empty,
  Form,
  Input,
  InputNumber,
  Layout,
  Menu,
  Modal,
  Row,
  Select,
  Space,
  Spin,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useEffect, useMemo, useState } from 'react';
import {
  AIProviderConfigItem,
  ConnectionConfig,
  LocalCliConfig,
  LocalCliConfigItem,
  ProjectConfig,
  TaskConfig,
  TaskResult,
  TaskResultStatus,
  TaskRun,
  TaskRunStatus,
  batchDeleteTaskRuns,
  cancelTaskRun,
  createConnection,
  createProject,
  createTaskRun,
  createTaskConfig,
  deleteConnection,
  deleteProject,
  deleteTaskConfig,
  deleteTaskResult,
  deleteTaskRun,
  getAIConfig,
  getConnections,
  getLocalCliConfig,
  getProjects,
  getTaskResult,
  getTaskResults,
  getTaskRunLog,
  getTaskRuns,
  getTaskConfigs,
  listConnectionTables,
  saveAIActiveProvider,
  saveAIConfig,
  saveLocalCliConfig,
  startTaskRuns,
  testConnectionPayload,
  retryTaskRun,
  updateConnection,
  updateProject,
  updateTaskConfig,
} from './api';

const { Header, Sider, Content } = Layout;
const { Title, Text, Paragraph } = Typography;

type ActiveTab = 'project' | 'database' | 'ai' | 'cli';
type PrimaryModule = 'config' | 'task' | 'taskRun' | 'taskResult';

const defaultPorts: Record<string, number> = {
  mysql: 3306,
  pgsql: 5432,
  mssql: 1433,
  oracle: 1521,
  sqlite: 0,
};

const taskRunStatusOptions: { value: TaskRunStatus; label: string; color: string }[] = [
  { value: 'PENDING', label: '待执行', color: 'default' },
  { value: 'RUNNING', label: '执行中', color: 'processing' },
  { value: 'SUCCESS', label: '处理成功', color: 'blue' },
  { value: 'FAILED', label: '处理失败', color: 'red' },
  { value: 'CANCELLED', label: '已取消', color: 'orange' },
];

const taskResultStatusOptions: { value: TaskResultStatus; label: string; color: string }[] = [
  { value: 'PENDING', label: '待处理', color: 'default' },
  { value: 'RUNNING', label: '处理中', color: 'processing' },
  { value: 'SUCCESS', label: '处理成功', color: 'blue' },
  { value: 'FAILED', label: '处理失败', color: 'red' },
];

const createEmptyProvider = (index: number): AIProviderConfigItem => ({
  id: `provider-${index}`,
  label: '',
  type: 'openai-compatible',
  base_url: '',
  api_key: '',
  model: '',
  max_tokens: 4096,
});

const createEmptyCliConfig = (index: number): LocalCliConfigItem => ({
  enabled: true,
  id: `cli-${index}`,
  label: '',
  command: '',
  defaultArgs: [],
  workingDirectory: '/Users/conchi/workforce/python_workforce/ai-task-center',
  timeoutSeconds: 300,
});

export default function App() {
  const { message, modal } = AntApp.useApp();
  const [activeModule, setActiveModule] = useState<PrimaryModule>('config');
  const [activeTab, setActiveTab] = useState<ActiveTab>('project');
  const [projects, setProjects] = useState<ProjectConfig[]>([]);
  const [projectLoading, setProjectLoading] = useState(false);
  const [activeProjectId, setActiveProjectId] = useState<number | null>(() => Number(localStorage.getItem('activeProjectId')) || null);
  const [activeProjectName, setActiveProjectName] = useState(() => localStorage.getItem('activeProjectName') || '');
  const [projectModalOpen, setProjectModalOpen] = useState(false);
  const [editingProject, setEditingProject] = useState<ProjectConfig | null>(null);
  const [projectForm] = Form.useForm();

  const [connections, setConnections] = useState<ConnectionConfig[]>([]);
  const [connectionLoading, setConnectionLoading] = useState(false);
  const [activeConnectionId, setActiveConnectionId] = useState<number | null>(() => Number(localStorage.getItem('activeConnectionId')) || null);
  const [filterEnv, setFilterEnv] = useState('');
  const [connectionModalOpen, setConnectionModalOpen] = useState(false);
  const [editingConnection, setEditingConnection] = useState<ConnectionConfig | null>(null);
  const [connectionForm] = Form.useForm();
  const [testingConnection, setTestingConnection] = useState(false);
  const [showDbPassword, setShowDbPassword] = useState(false);

  const [aiLoading, setAiLoading] = useState(false);
  const [savingAI, setSavingAI] = useState(false);
  const [providers, setProviders] = useState<AIProviderConfigItem[]>([]);
  const [activeProviderId, setActiveProviderId] = useState('');
  const [selectedProviderId, setSelectedProviderId] = useState('');
  const [aiForm] = Form.useForm();
  const [cliForm] = Form.useForm();
  const [savingCli, setSavingCli] = useState(false);
  const [cliConfigs, setCliConfigs] = useState<LocalCliConfigItem[]>([]);
  const [activeCliId, setActiveCliId] = useState('');
  const [selectedCliId, setSelectedCliId] = useState('');

  const [taskLoading, setTaskLoading] = useState(false);
  const [tasks, setTasks] = useState<TaskConfig[]>([]);
  const [taskModalOpen, setTaskModalOpen] = useState(false);
  const [editingTask, setEditingTask] = useState<TaskConfig | null>(null);
  const [taskConnections, setTaskConnections] = useState<ConnectionConfig[]>([]);
  const [taskForm] = Form.useForm();
  const [taskSearchForm] = Form.useForm();
  const [selectedTaskId, setSelectedTaskId] = useState<number | null>(null);
  const [tableModalOpen, setTableModalOpen] = useState(false);
  const [availableTables, setAvailableTables] = useState<string[]>([]);
  const [selectedTables, setSelectedTables] = useState<string[]>([]);
  const [tableLoading, setTableLoading] = useState(false);
  const [savingTables, setSavingTables] = useState(false);
  const [taskFilters, setTaskFilters] = useState<{
    taskName?: string;
    projectId?: number;
    cliId?: string;
  }>({});

  const [taskRunLoading, setTaskRunLoading] = useState(false);
  const [taskRuns, setTaskRuns] = useState<TaskRun[]>([]);
  const [taskRunSearchForm] = Form.useForm();
  const [taskRunCreateForm] = Form.useForm();
  const [taskRunCreateOpen, setTaskRunCreateOpen] = useState(false);
  const [taskRunStartOpen, setTaskRunStartOpen] = useState(false);
  const [selectedTaskRunIds, setSelectedTaskRunIds] = useState<number[]>([]);
  const [logModalOpen, setLogModalOpen] = useState(false);
  const [currentLogRun, setCurrentLogRun] = useState<TaskRun | null>(null);
  const [taskRunStartForm] = Form.useForm();
  const [startingTaskRuns, setStartingTaskRuns] = useState(false);

  const [taskResultLoading, setTaskResultLoading] = useState(false);
  const [taskResults, setTaskResults] = useState<TaskResult[]>([]);
  const [taskResultSearchForm] = Form.useForm();
  const [resultDetailOpen, setResultDetailOpen] = useState(false);
  const [currentResult, setCurrentResult] = useState<TaskResult | null>(null);

  const activeProject = useMemo(
    () => projects.find((project) => project.ID === activeProjectId) || null,
    [projects, activeProjectId],
  );
  const envOptions = useMemo(
    () => Array.from(new Set(connections.map((item) => item.envName).filter(Boolean))).sort((a, b) => String(a).localeCompare(String(b), 'zh-CN')),
    [connections],
  );
  const selectedProvider = useMemo(
    () => providers.find((provider) => provider.id === selectedProviderId) || providers[0] || null,
    [providers, selectedProviderId],
  );
  const selectedCliConfig = useMemo(
    () => cliConfigs.find((config) => config.id === selectedCliId) || cliConfigs[0] || null,
    [cliConfigs, selectedCliId],
  );
  const projectMap = useMemo(
    () => new Map(projects.map((project) => [project.ID, project.projectName])),
    [projects],
  );
  const connectionMap = useMemo(
    () => new Map(taskConnections.map((connection) => [connection.ID, connection.connectionName])),
    [taskConnections],
  );
  const cliMap = useMemo(
    () => new Map(cliConfigs.map((config) => [config.id, config.label || config.id])),
    [cliConfigs],
  );
  const filteredTasks = useMemo(
    () => tasks.filter((task) => {
      const keyword = (taskFilters.taskName || '').trim().toLowerCase();
      if (keyword && !task.taskName.toLowerCase().includes(keyword)) return false;
      if (taskFilters.projectId && task.projectId !== taskFilters.projectId) return false;
      if (taskFilters.cliId && task.cliId !== taskFilters.cliId) return false;
      return true;
    }),
    [taskFilters, tasks],
  );
  const selectedTask = useMemo(
    () => tasks.find((task) => task.ID === selectedTaskId) || null,
    [selectedTaskId, tasks],
  );
  const taskConfigMap = useMemo(
    () => new Map(tasks.map((task) => [task.ID, task.taskName])),
    [tasks],
  );

  useEffect(() => {
    void loadProjects();
  }, []);

  useEffect(() => {
    if (activeModule === 'task') {
      void loadTaskPageData();
    }
    if (activeModule === 'taskRun') {
      void loadTaskRunPageData();
    }
    if (activeModule === 'taskResult') {
      void loadTaskResultPageData();
    }
  }, [activeModule]);

  useEffect(() => {
    if (activeTab === 'database') {
      void loadConnections();
    }
    if (activeTab === 'ai') {
      void loadAI();
    }
    if (activeTab === 'cli') {
      void loadCliConfig();
    }
  }, [activeTab, activeProjectId]);

  useEffect(() => {
    if (selectedProvider) {
      aiForm.setFieldsValue(selectedProvider);
    }
  }, [aiForm, selectedProvider]);

  useEffect(() => {
    if (selectedCliConfig) {
      cliForm.setFieldsValue({
        ...selectedCliConfig,
        defaultArgsText: (selectedCliConfig.defaultArgs || []).join(' '),
      });
    }
  }, [cliForm, selectedCliConfig]);

  // 函数：setActiveProject
  const setActiveProject = (project: ProjectConfig | null) => {
    setActiveProjectId(project?.ID || null);
    setActiveProjectName(project?.projectName || '');
    localStorage.setItem('activeProjectId', project?.ID ? String(project.ID) : '');
    localStorage.setItem('activeProjectName', project?.projectName || '');
  };

  // 函数：loadProjects
  const loadProjects = async () => {
    setProjectLoading(true);
    try {
      const list = await getProjects();
      setProjects(list);
      if (!activeProjectId && list.length > 0) {
        setActiveProject(list[0]);
      } else if (activeProjectId && !list.some((item) => item.ID === activeProjectId)) {
        setActiveProject(null);
      }
    } catch (error) {
      message.error(errorMessage(error, '加载项目失败'));
    } finally {
      setProjectLoading(false);
    }
  };

  // 函数：openProjectModal
  const openProjectModal = (project?: ProjectConfig) => {
    setEditingProject(project || null);
    projectForm.setFieldsValue({
      projectName: project?.projectName || '',
      projectDesc: project?.projectDesc || '',
    });
    setProjectModalOpen(true);
  };

  // 函数：saveProject
  const saveProject = async () => {
    const values = await projectForm.validateFields();
    try {
      if (editingProject) {
        const updated = await updateProject({ ID: editingProject.ID, ...values });
        if (activeProjectId === editingProject.ID) setActiveProject(updated);
        message.success('项目更新成功');
      } else {
        const created = await createProject(values);
        setActiveProject(created);
        message.success('项目创建成功');
      }
      setProjectModalOpen(false);
      await loadProjects();
    } catch (error) {
      message.error(errorMessage(error, '项目保存失败'));
    }
  };

  // 函数：removeProject
  const removeProject = (project: ProjectConfig) => {
    modal.confirm({
      title: '删除项目',
      content: `确定删除项目「${project.projectName}」吗？`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      async onOk() {
        await deleteProject(project.ID);
        if (activeProjectId === project.ID) setActiveProject(null);
        message.success('项目删除成功');
        await loadProjects();
      },
    });
  };

  // 函数：loadConnections
  const loadConnections = async () => {
    if (!activeProjectId) {
      setConnections([]);
      return;
    }
    setConnectionLoading(true);
    try {
      const data = await getConnections({
        connectionGroup: String(activeProjectId),
        envName: filterEnv || undefined,
      });
      const list = data.list || [];
      setConnections(list);
      if (list.length === 0) {
        setActiveConnectionId(null);
      } else if (!list.some((item) => item.ID === activeConnectionId)) {
        setActiveConnectionId(list[0].ID || null);
        localStorage.setItem('activeConnectionId', String(list[0].ID || ''));
      }
    } catch (error) {
      message.error(errorMessage(error, '加载数据库配置失败'));
    } finally {
      setConnectionLoading(false);
    }
  };

  useEffect(() => {
    if (activeTab === 'database') {
      void loadConnections();
    }
  }, [filterEnv]);

  // 函数：openConnectionModal
  const openConnectionModal = (connection?: ConnectionConfig) => {
    if (!activeProjectId) {
      message.warning('请先选择一个项目');
      return;
    }
    setEditingConnection(connection || null);
    setShowDbPassword(false);
    connectionForm.setFieldsValue({
      connectionName: connection?.connectionName || '',
      envName: connection?.envName || '',
      connectionType: connection?.connectionType || 'mysql',
      connectionUrl: connection?.connectionUrl || '',
      port: connection?.port ?? 3306,
      databaseName: connection?.databaseName || '',
      dbLoginName: connection?.dbLoginName || '',
      dbLoginPassword: connection?.dbLoginPassword || '',
    });
    setConnectionModalOpen(true);
  };

  // 函数：saveConnection
  const saveConnection = async () => {
    const values = await connectionForm.validateFields();
    const payload = {
      ...values,
      connectionGroup: String(activeProjectId),
    };
    try {
      if (editingConnection?.ID) {
        await updateConnection({ ...payload, ID: editingConnection.ID });
        message.success('数据库配置修改成功');
      } else {
        await createConnection(payload);
        message.success('数据库配置添加成功');
      }
      setConnectionModalOpen(false);
      await loadConnections();
    } catch (error) {
      message.error(errorMessage(error, '数据库配置保存失败'));
    }
  };

  // 函数：testCurrentConnection
  const testCurrentConnection = async () => {
    const values = await connectionForm.validateFields();
    setTestingConnection(true);
    try {
      await testConnectionPayload({
        ...values,
        connectionGroup: String(activeProjectId),
      });
      message.success('连接成功');
    } catch (error) {
      message.error(errorMessage(error, '连接失败'));
    } finally {
      setTestingConnection(false);
    }
  };

  // 函数：removeConnection
  const removeConnection = (connection: ConnectionConfig) => {
    if (!connection.ID) return;
    modal.confirm({
      title: '删除数据库配置',
      content: `确定删除「${connection.connectionName}」吗？`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      async onOk() {
        await deleteConnection(connection.ID!);
        message.success('删除成功');
        await loadConnections();
      },
    });
  };

  // 函数：loadAI
  const loadAI = async () => {
    setAiLoading(true);
    try {
      const data = await getAIConfig();
      const nextProviders = data.providers || [];
      const nextActive = data.active || nextProviders.find((item) => item.active)?.id || nextProviders[0]?.id || '';
      setProviders(nextProviders);
      setActiveProviderId(nextActive);
      setSelectedProviderId((current) => (current && nextProviders.some((item) => item.id === current) ? current : nextActive || nextProviders[0]?.id || ''));
    } catch (error) {
      message.error(errorMessage(error, 'AI 配置加载失败'));
    } finally {
      setAiLoading(false);
    }
  };

  // 函数：loadCliConfig
  const loadCliConfig = async () => {
    try {
      const cliConfig = await getLocalCliConfig();
      const nextConfigs = cliConfig.configs || [];
      const nextActive = cliConfig.active || nextConfigs.find((item) => item.active)?.id || nextConfigs[0]?.id || '';
      setCliConfigs(nextConfigs);
      setActiveCliId(nextActive);
      setSelectedCliId((current) => (current && nextConfigs.some((item) => item.id === current) ? current : nextActive || nextConfigs[0]?.id || ''));
    } catch (error) {
      message.error(errorMessage(error, '本地 CLI 配置加载失败'));
    }
  };

  // 函数：syncProviderFromForm
  const syncProviderFromForm = () => {
    if (!selectedProvider) return providers;
    const values = aiForm.getFieldsValue();
    const next = providers.map((provider) =>
      provider.id === selectedProvider.id
        ? {
            ...provider,
            ...values,
            max_tokens: Number(values.max_tokens) || 4096,
          }
        : provider,
    );
    setProviders(next);
    if (values.id && selectedProviderId === selectedProvider.id) setSelectedProviderId(values.id);
    if (values.id && activeProviderId === selectedProvider.id) setActiveProviderId(values.id);
    return next;
  };

  // 函数：addProvider
  const addProvider = () => {
    const current = syncProviderFromForm();
    let next = createEmptyProvider(current.length + 1);
    while (current.some((item) => item.id === next.id)) {
      next = createEmptyProvider(current.length + Math.floor(Math.random() * 1000));
    }
    setProviders([...current, next]);
    setSelectedProviderId(next.id);
    if (!activeProviderId) setActiveProviderId(next.id);
  };

  // 函数：removeProvider
  const removeProvider = () => {
    if (!selectedProvider) return;
    if (providers.length <= 1) {
      message.error('至少保留一个 AI 配置');
      return;
    }
    modal.confirm({
      title: '删除 AI 配置',
      content: '确定删除该 AI 配置吗？',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk() {
        const next = providers.filter((item) => item.id !== selectedProvider.id);
        setProviders(next);
        setSelectedProviderId(next[0]?.id || '');
        if (activeProviderId === selectedProvider.id) setActiveProviderId(next[0]?.id || '');
      },
    });
  };

  // 函数：setDefaultProvider
  const setDefaultProvider = async () => {
    const next = syncProviderFromForm();
    const provider = next.find((item) => item.id === selectedProviderId) || next[0];
    if (!provider) return;
    try {
      await saveAIActiveProvider(provider.id);
      setActiveProviderId(provider.id);
      message.success('默认 AI 已保存');
    } catch (error) {
      message.error(errorMessage(error, '设置默认 AI 失败'));
    }
  };

  // 函数：saveAI
  const saveAI = async () => {
    const next = syncProviderFromForm();
    const error = validateAI(next, activeProviderId);
    if (error) {
      message.error(error);
      return;
    }
    setSavingAI(true);
    try {
      await saveAIConfig({
        active: activeProviderId,
        providers: next.map((provider) => ({
          ...provider,
          id: provider.id.trim(),
          label: provider.label.trim(),
          base_url: provider.base_url.trim(),
          api_key: provider.api_key.trim(),
          model: provider.model.trim(),
          max_tokens: Number(provider.max_tokens) || 4096,
        })),
      });
      message.success('AI 配置已保存');
      await loadAI();
    } catch (error) {
      message.error(errorMessage(error, '保存 AI 配置失败'));
    } finally {
      setSavingAI(false);
    }
  };

  // 函数：saveCliConfig
  const saveCliConfig = async () => {
    const nextConfigs = await syncCliConfigFromForm(true);
    const error = validateCliConfigs(nextConfigs, activeCliId);
    if (error) {
      message.error(error);
      return;
    }
    const payload: LocalCliConfig = {
      active: activeCliId,
      configs: nextConfigs.map((config) => ({
        ...config,
        id: config.id.trim(),
        label: config.label.trim(),
        command: config.command.trim(),
        workingDirectory: config.workingDirectory.trim(),
        timeoutSeconds: Number(config.timeoutSeconds) || 300,
      })),
    };
    setSavingCli(true);
    try {
      const saved = await saveLocalCliConfig(payload);
      setCliConfigs(saved.configs || []);
      setActiveCliId(saved.active || '');
      setSelectedCliId(saved.active || saved.configs?.[0]?.id || '');
      message.success('本地 CLI 配置已保存');
    } catch (error) {
      message.error(errorMessage(error, '保存本地 CLI 配置失败'));
    } finally {
      setSavingCli(false);
    }
  };

  // 函数：syncCliConfigFromForm
  const syncCliConfigFromForm = async (validate = false) => {
    if (!selectedCliConfig) return cliConfigs;
    const values = validate ? await cliForm.validateFields() : cliForm.getFieldsValue();
    const next = cliConfigs.map((config) =>
      config.id === selectedCliConfig.id
        ? {
            ...config,
            ...values,
            defaultArgs: splitArgs(values.defaultArgsText),
            timeoutSeconds: Number(values.timeoutSeconds) || 300,
          }
        : config,
    );
    setCliConfigs(next);
    if (values.id && selectedCliId === selectedCliConfig.id) setSelectedCliId(values.id);
    if (values.id && activeCliId === selectedCliConfig.id) setActiveCliId(values.id);
    return next;
  };

  // 函数：addCliConfig
  const addCliConfig = () => {
    const current = cliConfigs.length > 0 ? cliConfigs : [];
    let next = createEmptyCliConfig(current.length + 1);
    while (current.some((item) => item.id === next.id)) {
      next = createEmptyCliConfig(current.length + Math.floor(Math.random() * 1000));
    }
    const updated = [...current, next];
    setCliConfigs(updated);
    setSelectedCliId(next.id);
    if (!activeCliId) setActiveCliId(next.id);
  };

  // 函数：removeCliConfig
  const removeCliConfig = () => {
    if (!selectedCliConfig) return;
    if (cliConfigs.length <= 1) {
      message.error('至少保留一个 CLI 配置');
      return;
    }
    modal.confirm({
      title: '删除 CLI 配置',
      content: '确定删除该 CLI 配置吗？',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk() {
        const next = cliConfigs.filter((item) => item.id !== selectedCliConfig.id);
        setCliConfigs(next);
        setSelectedCliId(next[0]?.id || '');
        if (activeCliId === selectedCliConfig.id) setActiveCliId(next[0]?.id || '');
      },
    });
  };

  // 函数：setDefaultCliConfig
  const setDefaultCliConfig = async () => {
    const next = await syncCliConfigFromForm(true);
    const selected = next.find((item) => item.id === selectedCliId) || next[0];
    if (!selected) return;
    setActiveCliId(selected.id);
    message.success('默认 CLI 已更新，保存后生效');
  };

  // 函数：loadTaskPageData
  const loadTaskPageData = async () => {
    setTaskLoading(true);
    try {
      const [taskList, connectionData, cliConfig, projectList] = await Promise.all([
        getTaskConfigs(),
        getConnections({}),
        getLocalCliConfig(),
        getProjects(),
      ]);
      setTasks(taskList);
      setSelectedTaskId((current) => (current && taskList.some((task) => task.ID === current) ? current : null));
      setTaskConnections(connectionData.list || []);
      setProjects(projectList);
      const nextConfigs = cliConfig.configs || [];
      const nextActive = cliConfig.active || nextConfigs.find((item) => item.active)?.id || nextConfigs[0]?.id || '';
      setCliConfigs(nextConfigs);
      setActiveCliId(nextActive);
      setSelectedCliId((current) => (current && nextConfigs.some((item) => item.id === current) ? current : nextActive || nextConfigs[0]?.id || ''));
    } catch (error) {
      message.error(errorMessage(error, '加载任务配置失败'));
    } finally {
      setTaskLoading(false);
    }
  };

  // 函数：openTaskModal
  const openTaskModal = (task?: TaskConfig) => {
    setEditingTask(task || null);
    taskForm.setFieldsValue({
      taskName: task?.taskName || '',
      projectId: task?.projectId || activeProjectId || projects[0]?.ID,
      cliId: task?.cliId || activeCliId || cliConfigs[0]?.id,
      databaseConfigId: task?.databaseConfigId || undefined,
      taskDesc: task?.taskDesc || '',
    });
    setTaskModalOpen(true);
  };

  // 函数：saveTask
  const saveTask = async () => {
    const values = await taskForm.validateFields();
    try {
      const payload = {
        ...values,
        databaseConfigId: values.databaseConfigId || null,
        selectedTables: editingTask?.selectedTables || '',
      };
      if (editingTask?.ID) {
        await updateTaskConfig({ ...payload, ID: editingTask.ID });
        message.success('任务配置更新成功');
      } else {
        await createTaskConfig(payload);
        message.success('任务配置创建成功');
      }
      setTaskModalOpen(false);
      await loadTaskPageData();
    } catch (error) {
      message.error(errorMessage(error, '任务配置保存失败'));
    }
  };

  // 函数：removeTask
  const removeTask = (task: TaskConfig) => {
    if (!task.ID) return;
    modal.confirm({
      title: '删除任务配置',
      content: `确定删除任务「${task.taskName}」吗？`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      async onOk() {
        await deleteTaskConfig(task.ID!);
        message.success('任务配置删除成功');
        await loadTaskPageData();
      },
    });
  };

  // 函数：openTableModal
  const openTableModal = async () => {
    if (!selectedTask) {
      message.warning('请先选择一条任务配置');
      return;
    }
    if (!selectedTask.databaseConfigId) {
      message.warning('该任务还没有关联数据库');
      return;
    }
    setTableModalOpen(true);
    setTableLoading(true);
    setSelectedTables(parseSelectedTables(selectedTask.selectedTables));
    try {
      const tables = await listConnectionTables(selectedTask.databaseConfigId);
      setAvailableTables(tables);
    } catch (error) {
      message.error(errorMessage(error, '读取数据表失败'));
      setAvailableTables([]);
    } finally {
      setTableLoading(false);
    }
  };

  // 函数：saveSelectedTables
  const saveSelectedTables = async () => {
    if (!selectedTask?.ID) return;
    setSavingTables(true);
    try {
      await updateTaskConfig({
        ...selectedTask,
        ID: selectedTask.ID,
        selectedTables: JSON.stringify(selectedTables),
      });
      message.success('已保存任务关联表');
      setTableModalOpen(false);
      await loadTaskPageData();
    } catch (error) {
      message.error(errorMessage(error, '保存关联表失败'));
    } finally {
      setSavingTables(false);
    }
  };

  const loadTaskRunPageData = async (filters?: {
    taskName?: string;
    projectId?: number;
    cliId?: string;
    status?: string;
  }) => {
    setTaskRunLoading(true);
    try {
      const [runList, taskList, connectionData, cliConfig, projectList] = await Promise.all([
        getTaskRuns(filters),
        getTaskConfigs(),
        getConnections({}),
        getLocalCliConfig(),
        getProjects(),
      ]);
      setTaskRuns(runList);
      setTasks(taskList);
      setTaskConnections(connectionData.list || []);
      setProjects(projectList);
      const nextConfigs = cliConfig.configs || [];
      setCliConfigs(nextConfigs);
      setSelectedTaskRunIds((current) => current.filter((id) => runList.some((run) => run.ID === id)));
    } catch (error) {
      message.error(errorMessage(error, '加载任务列表失败'));
    } finally {
      setTaskRunLoading(false);
    }
  };

  // 函数：openTaskRunCreateModal
  const openTaskRunCreateModal = () => {
    taskRunCreateForm.setFieldsValue({
      taskConfigId: tasks[0]?.ID,
      taskName: '',
    });
    setTaskRunCreateOpen(true);
  };

  // 函数：openTaskRunStartModal
  const openTaskRunStartModal = () => {
    if (selectedTaskRunIds.length === 0) {
      message.warning('请选择要开始执行的任务记录');
      return;
    }
    taskRunStartForm.setFieldsValue({
      cliId: activeCliId || cliConfigs[0]?.id,
      executionMode: 'thread',
      workerCount: 1,
    });
    setTaskRunStartOpen(true);
  };

  // 函数：saveTaskRun
  const saveTaskRun = async () => {
    const values = await taskRunCreateForm.validateFields();
    try {
      await createTaskRun(values);
      message.success('任务已创建');
      setTaskRunCreateOpen(false);
      await loadTaskRunPageData(taskRunSearchForm.getFieldsValue());
    } catch (error) {
      message.error(errorMessage(error, '创建任务失败'));
    }
  };

  // 函数：startSelectedTaskRuns
  const startSelectedTaskRuns = async () => {
    const values = await taskRunStartForm.validateFields();
    setStartingTaskRuns(true);
    try {
      await startTaskRuns({
        taskRunIds: selectedTaskRunIds,
        cliId: values.cliId,
        executionMode: values.executionMode,
        workerCount: Number(values.workerCount) || 1,
      });
      message.success('任务已提交 Python Worker');
      setTaskRunStartOpen(false);
      await loadTaskRunPageData(taskRunSearchForm.getFieldsValue());
    } catch (error) {
      message.error(errorMessage(error, '开始执行失败'));
    } finally {
      setStartingTaskRuns(false);
    }
  };

  // 函数：removeTaskRun
  const removeTaskRun = (run: TaskRun) => {
    if (!run.ID) return;
    modal.confirm({
      title: '删除任务记录',
      content: `确定删除任务「${run.taskName}」吗？`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      async onOk() {
        await deleteTaskRun(run.ID!);
        message.success('任务记录删除成功');
        await loadTaskRunPageData(taskRunSearchForm.getFieldsValue());
      },
    });
  };

  // 函数：removeSelectedTaskRuns
  const removeSelectedTaskRuns = () => {
    if (selectedTaskRunIds.length === 0) {
      message.warning('请选择要删除的任务记录');
      return;
    }
    modal.confirm({
      title: '批量删除任务记录',
      content: `确定删除选中的 ${selectedTaskRunIds.length} 条任务记录吗？`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      async onOk() {
        await batchDeleteTaskRuns(selectedTaskRunIds);
        setSelectedTaskRunIds([]);
        message.success('批量删除成功');
        await loadTaskRunPageData(taskRunSearchForm.getFieldsValue());
      },
    });
  };

  // 函数：retryRun
  const retryRun = async (run: TaskRun) => {
    if (!run.ID) return;
    try {
      await retryTaskRun(run.ID);
      message.success('已创建重试任务');
      await loadTaskRunPageData(taskRunSearchForm.getFieldsValue());
    } catch (error) {
      message.error(errorMessage(error, '重试任务失败'));
    }
  };

  // 函数：cancelRun
  const cancelRun = async (run: TaskRun) => {
    if (!run.ID) return;
    try {
      await cancelTaskRun(run.ID);
      message.success('任务已取消');
      await loadTaskRunPageData(taskRunSearchForm.getFieldsValue());
    } catch (error) {
      message.error(errorMessage(error, '取消任务失败'));
    }
  };

  // 函数：showRunLog
  const showRunLog = async (run: TaskRun) => {
    if (!run.ID) return;
    try {
      const data = await getTaskRunLog(run.ID);
      setCurrentLogRun(data);
      setLogModalOpen(true);
    } catch (error) {
      message.error(errorMessage(error, '读取日志失败'));
    }
  };

  const loadTaskResultPageData = async (filters?: {
    resultName?: string;
    projectId?: number;
    status?: string;
  }) => {
    setTaskResultLoading(true);
    try {
      const [resultList, runList, taskList, connectionData, cliConfig, projectList] = await Promise.all([
        getTaskResults(filters),
        getTaskRuns(),
        getTaskConfigs(),
        getConnections({}),
        getLocalCliConfig(),
        getProjects(),
      ]);
      setTaskResults(resultList);
      setTaskRuns(runList);
      setTasks(taskList);
      setTaskConnections(connectionData.list || []);
      setProjects(projectList);
      setCliConfigs(cliConfig.configs || []);
    } catch (error) {
      message.error(errorMessage(error, '加载任务结果失败'));
    } finally {
      setTaskResultLoading(false);
    }
  };

  // 函数：showTaskResultDetail
  const showTaskResultDetail = async (result: TaskResult) => {
    if (!result.ID) return;
    try {
      const data = await getTaskResult(result.ID);
      setCurrentResult(data);
      setResultDetailOpen(true);
    } catch (error) {
      message.error(errorMessage(error, '读取任务结果失败'));
    }
  };

  // 函数：removeTaskResult
  const removeTaskResult = (result: TaskResult) => {
    if (!result.ID) return;
    modal.confirm({
      title: '删除任务结果',
      content: `确定删除结果「${result.resultName}」吗？`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      async onOk() {
        await deleteTaskResult(result.ID!);
        message.success('任务结果删除成功');
        await loadTaskResultPageData(taskResultSearchForm.getFieldsValue());
      },
    });
  };

  // 函数：renderProjectView
  const renderProjectView = () => (
    <section className="panel-page">
      <div className="page-head">
        <div>
          <Title level={3}>核心项目配置</Title>
          <Text type="secondary">创建并选择当前活跃项目，选中项将作为全局环境隔离标识。</Text>
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => openProjectModal()}>
          新增项目
        </Button>
      </div>
      <Spin spinning={projectLoading}>
        {projects.length === 0 ? (
          <Empty description="暂无项目配置" />
        ) : (
          <Row gutter={[16, 16]}>
            {projects.map((project) => {
              const active = activeProjectId === project.ID;
              return (
                <Col xs={24} md={12} xl={8} key={project.ID}>
                  <Card
                    hoverable
                    className={active ? 'selection-card active' : 'selection-card'}
                    onClick={() => setActiveProject(project)}
                    actions={[
                      <EditOutlined key="edit" onClick={(event) => { event.stopPropagation(); openProjectModal(project); }} />,
                      <DeleteOutlined key="delete" onClick={(event) => { event.stopPropagation(); removeProject(project); }} />,
                    ]}
                  >
                    <Space align="start" size={12}>
                      <div className="card-icon project"><CloudServerOutlined /></div>
                      <div className="card-copy">
                        <Space>
                          <Title level={5}>{project.projectName}</Title>
                          {active && <CheckCircleFilled className="active-mark" />}
                        </Space>
                        <Paragraph ellipsis={{ rows: 2 }} type="secondary">
                          {project.projectDesc || '暂无项目介绍'}
                        </Paragraph>
                      </div>
                    </Space>
                  </Card>
                </Col>
              );
            })}
          </Row>
        )}
      </Spin>
    </section>
  );

  // 函数：renderDatabaseView
  const renderDatabaseView = () => (
    <section className="panel-page">
      <div className="page-head">
        <div>
          <Title level={3}>环境数据库配置</Title>
          <Text type="secondary">{activeProjectName ? `当前项目：${activeProjectName}` : '请先选择项目'}</Text>
        </div>
        <Space>
          <Select
            value={filterEnv}
            onChange={setFilterEnv}
            style={{ width: 180 }}
            options={[{ value: '', label: '全部环境' }, ...envOptions.map((env) => ({ value: String(env), label: String(env) }))]}
          />
          <Button type="primary" icon={<PlusOutlined />} disabled={!activeProject} onClick={() => openConnectionModal()}>
            新增数据源
          </Button>
        </Space>
      </div>
      {!activeProject ? (
        <Empty description="请先在项目配置中选择一个活跃项目" />
      ) : (
        <Spin spinning={connectionLoading}>
          {connections.length === 0 ? (
            <Empty description={filterEnv ? '当前环境暂无数据库配置' : '暂无属于该项目的数据库配置'} />
          ) : (
            <Row gutter={[16, 16]}>
              {connections.map((connection) => {
                const active = activeConnectionId === connection.ID;
                return (
                  <Col xs={24} xl={12} key={connection.ID}>
                    <Card
                      hoverable
                      className={active ? 'selection-card active' : 'selection-card'}
                      onClick={() => {
                        setActiveConnectionId(connection.ID || null);
                        localStorage.setItem('activeConnectionId', String(connection.ID || ''));
                      }}
                    >
                      <div className="card-row">
                        <Space align="start" size={12}>
                          <div className="card-icon database"><DatabaseOutlined /></div>
                          <div>
                            <Space>
                              <Title level={5}>{connection.connectionName}</Title>
                              {connection.envName && <Tag color="green">{connection.envName}</Tag>}
                              {active && <CheckCircleFilled className="active-mark" />}
                            </Space>
                            <Text type="secondary" className="mono">{connection.connectionType}</Text>
                          </div>
                        </Space>
                        <Space>
                          <Button icon={<EditOutlined />} onClick={(event) => { event.stopPropagation(); openConnectionModal(connection); }} />
                          <Button danger icon={<DeleteOutlined />} onClick={(event) => { event.stopPropagation(); removeConnection(connection); }} />
                        </Space>
                      </div>
                      <div className="meta-grid">
                        <Text ellipsis><span>Host: </span>{connection.connectionUrl}</Text>
                        <Text ellipsis><span>Port: </span>{connection.port}</Text>
                        <Text ellipsis><span>DB: </span>{connection.databaseName}</Text>
                        <Text ellipsis><span>User: </span>{connection.dbLoginName}</Text>
                      </div>
                    </Card>
                  </Col>
                );
              })}
            </Row>
          )}
        </Spin>
      )}
    </section>
  );

  // 函数：renderAIView
  const renderAIView = () => (
    <section className="panel-page">
      <div className="page-head">
        <div>
          <Title level={3}>AI 配置</Title>
          <Text type="secondary">管理可切换的模型厂商与默认选项。</Text>
        </div>
        <Space>
          <Button icon={<PlusOutlined />} onClick={addProvider}>添加 AI</Button>
          <Button type="primary" icon={<SaveOutlined />} loading={savingAI} onClick={saveAI}>保存配置</Button>
        </Space>
      </div>
      <Spin spinning={aiLoading}>
        {providers.length === 0 ? (
          <Empty description="暂无 AI 配置">
            <Button type="primary" onClick={addProvider}>添加 AI</Button>
          </Empty>
        ) : (
          <div className="ai-layout">
            <div className="ai-list">
              {providers.map((provider) => (
                <button
                  type="button"
                  key={provider.id}
                  className={selectedProviderId === provider.id ? 'ai-item active' : 'ai-item'}
                  onClick={() => {
                    syncProviderFromForm();
                    setSelectedProviderId(provider.id);
                  }}
                >
                  <div>
                    <Text strong>{provider.label || provider.id || '未命名 AI'}</Text>
                    <Text type="secondary" ellipsis>{provider.model || '未设置模型'}</Text>
                  </div>
                  {activeProviderId === provider.id && <CheckCircleFilled className="active-mark" />}
                </button>
              ))}
            </div>
            <Card className="ai-editor">
              {selectedProvider && (
                <>
                  <div className="card-row editor-head">
                    <div>
                      <Title level={4}>{selectedProvider.label || selectedProvider.id || '未命名 AI'}</Title>
                      <Text type="secondary">保存后会同步写入后端数据库。</Text>
                    </div>
                    <Space>
                      <Button icon={<CheckCircleFilled />} onClick={setDefaultProvider}>设为默认</Button>
                      <Button danger icon={<DeleteOutlined />} onClick={removeProvider}>删除</Button>
                    </Space>
                  </div>
                  <Form
                    layout="vertical"
                    form={aiForm}
                    onValuesChange={syncProviderFromForm}
                    initialValues={selectedProvider}
                  >
                    <Row gutter={16}>
                      <Col xs={24} lg={12}>
                        <Form.Item label="配置 ID" name="id" rules={[{ required: true, message: '请填写配置 ID' }]}>
                          <Input className="mono" placeholder="omlx" />
                        </Form.Item>
                      </Col>
                      <Col xs={24} lg={12}>
                        <Form.Item label="显示名称" name="label">
                          <Input placeholder="oMLX 本地" />
                        </Form.Item>
                      </Col>
                      <Col xs={24} lg={12}>
                        <Form.Item label="接口类型" name="type" rules={[{ required: true }]}>
                          <Select
                            options={[
                              { value: 'openai-compatible', label: 'openai-compatible' },
                              { value: 'anthropic-compatible', label: 'anthropic-compatible' },
                            ]}
                          />
                        </Form.Item>
                      </Col>
                      <Col xs={24} lg={12}>
                        <Form.Item label="模型名称" name="model" rules={[{ required: true, message: '请填写模型名称' }]}>
                          <Input className="mono" placeholder="qwen3-coder-plus" />
                        </Form.Item>
                      </Col>
                      <Col xs={24}>
                        <Form.Item label="Base URL" name="base_url" rules={[{ required: true, message: '请填写 Base URL' }]}>
                          <Input className="mono" placeholder="https://api.example.com" />
                        </Form.Item>
                      </Col>
                      <Col xs={24} lg={12}>
                        <Form.Item label="API Key" name="api_key">
                          <Input.Password className="mono" placeholder="sk-..." autoComplete="off" />
                        </Form.Item>
                      </Col>
                      <Col xs={24} lg={12}>
                        <Form.Item label="最大 Tokens" name="max_tokens">
                          <InputNumber min={1} className="full-field mono" />
                        </Form.Item>
                      </Col>
                    </Row>
                  </Form>
                </>
              )}
            </Card>
          </div>
        )}
      </Spin>
    </section>
  );

  // 函数：renderCliView
  const renderCliView = () => (
    <section className="panel-page">
      <div className="page-head">
        <div>
          <Title level={3}>本地 CLI 配置</Title>
          <Text type="secondary">配置后端未来可调用的本地命令行工具，可维护多个 CLI 并设置默认项。</Text>
        </div>
        <Space>
          <Button icon={<PlusOutlined />} onClick={addCliConfig}>添加 CLI</Button>
          <Button type="primary" icon={<SaveOutlined />} loading={savingCli} onClick={() => void saveCliConfig()}>
            保存配置
          </Button>
        </Space>
      </div>
      {cliConfigs.length === 0 ? (
        <Empty description="暂无 CLI 配置">
          <Button type="primary" onClick={addCliConfig}>添加 CLI</Button>
        </Empty>
      ) : (
        <div className="ai-layout">
          <div className="ai-list">
            {cliConfigs.map((config) => (
              <button
                type="button"
                key={config.id}
                className={selectedCliId === config.id ? 'ai-item active' : 'ai-item'}
                onClick={() => {
                  void syncCliConfigFromForm(false);
                  setSelectedCliId(config.id);
                }}
              >
                <div>
                  <Text strong>{config.label || config.id || '未命名 CLI'}</Text>
                  <Text type="secondary" ellipsis>{config.command || '未设置命令路径'}</Text>
                </div>
                {activeCliId === config.id && <CheckCircleFilled className="active-mark" />}
              </button>
            ))}
          </div>
          <Card className="ai-editor">
            {selectedCliConfig && (
              <>
                <div className="card-row editor-head">
                  <div>
                    <Title level={4}>{selectedCliConfig.label || selectedCliConfig.id || '未命名 CLI'}</Title>
                    <Text type="secondary">保存后后端可读取此配置调用本地 CLI。</Text>
                  </div>
                  <Space>
                    <Button icon={<CheckCircleFilled />} onClick={() => void setDefaultCliConfig()}>设为默认</Button>
                    <Button danger icon={<DeleteOutlined />} onClick={removeCliConfig}>删除</Button>
                  </Space>
                </div>
                <Form layout="vertical" form={cliForm} onValuesChange={() => void syncCliConfigFromForm(false)}>
                  <Row gutter={16}>
                    <Col xs={24} lg={6}>
                      <Form.Item label="启用" name="enabled" valuePropName="checked">
                        <Switch checkedChildren="启用" unCheckedChildren="停用" />
                      </Form.Item>
                    </Col>
                    <Col xs={24} lg={9}>
                      <Form.Item label="配置 ID" name="id" rules={[{ required: true, message: '请填写配置 ID' }]}>
                        <Input className="mono" placeholder="codex" />
                      </Form.Item>
                    </Col>
                    <Col xs={24} lg={9}>
                      <Form.Item label="显示名称" name="label" rules={[{ required: true, message: '请填写显示名称' }]}>
                        <Input placeholder="Codex CLI" />
                      </Form.Item>
                    </Col>
                    <Col xs={24} lg={12}>
                      <Form.Item label="命令路径" name="command" rules={[{ required: true, message: '请填写 CLI 命令路径' }]}>
                        <Input className="mono" placeholder="/usr/local/bin/codex" />
                      </Form.Item>
                    </Col>
                    <Col xs={24} lg={12}>
                      <Form.Item label="默认参数" name="defaultArgsText">
                        <Input className="mono" placeholder="exec" />
                      </Form.Item>
                    </Col>
                    <Col xs={24} lg={16}>
                      <Form.Item label="默认工作目录" name="workingDirectory" rules={[{ required: true, message: '请填写默认工作目录' }]}>
                        <Input className="mono" placeholder="/Users/conchi/workforce/python_workforce/ai-task-center" />
                      </Form.Item>
                    </Col>
                    <Col xs={24} lg={8}>
                      <Form.Item label="超时时间（秒）" name="timeoutSeconds">
                        <InputNumber min={1} className="full-field mono" />
                      </Form.Item>
                    </Col>
                  </Row>
                </Form>
              </>
            )}
          </Card>
        </div>
      )}
    </section>
  );

  // 函数：renderTaskView
  const renderTaskView = () => (
    <section className="panel-page">
      <div className="task-search-panel">
        <Form
          form={taskSearchForm}
          layout="inline"
          onFinish={(values) => setTaskFilters(values)}
          className="task-search-form"
        >
          <Form.Item label="任务名称" name="taskName">
            <Input allowClear placeholder="请输入任务名称" />
          </Form.Item>
          <Form.Item label="所属项目" name="projectId">
            <Select
              allowClear
              showSearch
              placeholder="请选择项目"
              optionFilterProp="label"
              options={projects.map((project) => ({ value: project.ID, label: project.projectName }))}
            />
          </Form.Item>
          <Form.Item label="执行工具" name="cliId">
            <Select
              allowClear
              showSearch
              placeholder="请选择 CLI"
              optionFilterProp="label"
              options={cliConfigs.map((config) => ({ value: config.id, label: config.label || config.id }))}
            />
          </Form.Item>
          <Form.Item className="task-search-actions">
            <Space>
              <Button
                icon={<ReloadOutlined />}
                onClick={() => {
                  taskSearchForm.resetFields();
                  setTaskFilters({});
                }}
              >
                重置
              </Button>
              <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>
                搜索
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </div>
      <div className="task-table-head">
        <Space>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => openTaskModal()}>
            新增
          </Button>
          <Button icon={<DatabaseOutlined />} disabled={!selectedTask} onClick={() => void openTableModal()}>
            选择表
          </Button>
        </Space>
      </div>
      <Spin spinning={taskLoading}>
        <Table<TaskConfig>
          rowKey={(record) => String(record.ID)}
          dataSource={filteredTasks}
          className="task-table"
          rowSelection={{
            type: 'radio',
            selectedRowKeys: selectedTaskId ? [selectedTaskId] : [],
            onChange: (keys) => setSelectedTaskId(Number(keys[0]) || null),
          }}
          onRow={(record) => ({
            onClick: () => setSelectedTaskId(record.ID || null),
          })}
          pagination={{
            pageSize: 10,
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 条`,
          }}
          locale={{ emptyText: <Empty description="暂无任务配置" /> }}
          columns={[
            {
              title: '任务名称',
              dataIndex: 'taskName',
              width: 220,
              fixed: 'left',
              render: (value: string) => <Text strong className="task-name-link">{value}</Text>,
            },
            {
              title: '所属项目',
              dataIndex: 'projectId',
              width: 180,
              render: (value: number) => projectMap.get(value) || <Text type="secondary">未找到项目</Text>,
            },
            {
              title: '默认执行工具',
              dataIndex: 'cliId',
              width: 180,
              render: (value: string) => <Tag color="blue">{cliMap.get(value) || value}</Tag>,
            },
            {
              title: '关联数据库',
              dataIndex: 'databaseConfigId',
              width: 220,
              render: (value?: number | null) => (
                value ? <Tag color="green">{connectionMap.get(value) || '未找到数据库'}</Tag> : <Text type="secondary">未关联</Text>
              ),
            },
            {
              title: '已选表',
              dataIndex: 'selectedTables',
              width: 150,
              render: (value?: string) => {
                const tables = parseSelectedTables(value);
                return tables.length > 0 ? <Tag color="purple">{tables.length} 张表</Tag> : <Text type="secondary">未选择</Text>;
              },
            },
            {
              title: '任务描述',
              dataIndex: 'taskDesc',
              ellipsis: true,
              render: (value?: string) => value || <Text type="secondary">暂无描述</Text>,
            },
            {
              title: '创建时间',
              dataIndex: 'CreatedAt',
              width: 180,
              render: (value?: string) => formatDateTime(value),
            },
            {
              title: '操作',
              key: 'action',
              width: 150,
              fixed: 'right',
              render: (_, record) => (
                <Space split={<span className="table-action-split" />}>
                  <Button type="link" size="small" onClick={() => openTaskModal(record)}>
                    编辑
                  </Button>
                  <Button type="link" size="small" danger onClick={() => removeTask(record)}>
                    删除
                  </Button>
                </Space>
              ),
            },
          ]}
          scroll={{ x: 1180 }}
        />
      </Spin>
    </section>
  );

  // 函数：renderTaskRunView
  const renderTaskRunView = () => (
    <section className="panel-page">
      <div className="task-search-panel">
        <Form
          form={taskRunSearchForm}
          layout="inline"
          onFinish={(values) => void loadTaskRunPageData(values)}
          className="task-search-form"
        >
          <Form.Item label="任务名称" name="taskName">
            <Input allowClear placeholder="请输入任务名称" />
          </Form.Item>
          <Form.Item label="所属项目" name="projectId">
            <Select
              allowClear
              showSearch
              placeholder="请选择项目"
              optionFilterProp="label"
              options={projects.map((project) => ({ value: project.ID, label: project.projectName }))}
            />
          </Form.Item>
          <Form.Item label="执行工具" name="cliId">
            <Select
              allowClear
              showSearch
              placeholder="请选择 CLI"
              optionFilterProp="label"
              options={cliConfigs.map((config) => ({ value: config.id, label: config.label || config.id }))}
            />
          </Form.Item>
          <Form.Item label="状态" name="status">
            <Select
              allowClear
              placeholder="请选择执行状态"
              options={taskRunStatusOptions.map((item) => ({ value: item.value, label: item.label }))}
            />
          </Form.Item>
          <Form.Item className="task-search-actions">
            <Space>
              <Button
                icon={<ReloadOutlined />}
                onClick={() => {
                  taskRunSearchForm.resetFields();
                  void loadTaskRunPageData();
                }}
              >
                重置
              </Button>
              <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>
                搜索
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </div>
      <div className="task-table-head">
        <Space>
          <Button type="primary" icon={<PlusOutlined />} onClick={openTaskRunCreateModal}>
            新增任务
          </Button>
          <Button icon={<RobotOutlined />} disabled={selectedTaskRunIds.length === 0} onClick={openTaskRunStartModal}>
            开始执行
          </Button>
          <Button danger icon={<DeleteOutlined />} disabled={selectedTaskRunIds.length === 0} onClick={removeSelectedTaskRuns}>
            批量删除
          </Button>
          <Button icon={<ReloadOutlined />} onClick={() => void loadTaskRunPageData(taskRunSearchForm.getFieldsValue())}>
            刷新
          </Button>
        </Space>
      </div>
      <Spin spinning={taskRunLoading}>
        <Table<TaskRun>
          rowKey={(record) => String(record.ID)}
          dataSource={taskRuns}
          className="task-table"
          rowSelection={{
            selectedRowKeys: selectedTaskRunIds,
            onChange: (keys) => setSelectedTaskRunIds(keys.map((key) => Number(key)).filter(Boolean)),
          }}
          pagination={{
            pageSize: 10,
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 条`,
          }}
          locale={{ emptyText: <Empty description="暂无任务记录" /> }}
          columns={[
            {
              title: 'ID',
              dataIndex: 'ID',
              width: 110,
              fixed: 'left',
              render: (value: number) => <Text strong className="task-name-link">{value}</Text>,
            },
            {
              title: '任务名称',
              dataIndex: 'taskName',
              width: 220,
              render: (value: string) => <Text strong>{value}</Text>,
            },
            {
              title: '所属项目',
              dataIndex: 'projectId',
              width: 180,
              render: (value: number) => projectMap.get(value) || <Text type="secondary">未找到项目</Text>,
            },
            {
              title: '执行工具',
              dataIndex: 'cliId',
              width: 160,
              render: (value: string) => <Tag color="blue">{cliMap.get(value) || value}</Tag>,
            },
            {
              title: '数据源',
              dataIndex: 'databaseConfigId',
              width: 180,
              render: (value?: number | null) => (
                value ? connectionMap.get(value) || <Text type="secondary">未找到数据库</Text> : <Text type="secondary">未关联</Text>
              ),
            },
            {
              title: '已选表',
              dataIndex: 'selectedTables',
              width: 120,
              render: (value?: string) => {
                const tables = parseSelectedTables(value);
                return tables.length > 0 ? <Tag color="purple">{tables.length} 张表</Tag> : <Text type="secondary">未选择</Text>;
              },
            },
            {
              title: '状态',
              dataIndex: 'status',
              width: 130,
              render: (value: TaskRunStatus) => renderTaskRunStatus(value),
            },
            {
              title: '开始执行时间',
              dataIndex: 'startTime',
              width: 180,
              render: (value?: string) => formatDateTime(value),
            },
            {
              title: '执行时长(秒)',
              dataIndex: 'durationSeconds',
              width: 130,
              render: (value?: number) => value ?? <Text type="secondary">-</Text>,
            },
            {
              title: '操作原因',
              dataIndex: 'reason',
              width: 180,
              render: (value?: string) => value || <Text type="secondary">无</Text>,
            },
            {
              title: '创建时间',
              dataIndex: 'CreatedAt',
              width: 180,
              render: (value?: string) => formatDateTime(value),
            },
            {
              title: '操作',
              key: 'action',
              width: 220,
              fixed: 'right',
              render: (_, record) => (
                <Space split={<span className="table-action-split" />}>
                  <Button type="link" size="small" onClick={() => void showRunLog(record)}>
                    日志
                  </Button>
                  {(record.status === 'PENDING' || record.status === 'RUNNING') && (
                    <Button type="link" size="small" onClick={() => void cancelRun(record)}>
                      取消
                    </Button>
                  )}
                  <Button type="link" size="small" onClick={() => void retryRun(record)}>
                    重试
                  </Button>
                  <Button type="link" size="small" danger onClick={() => removeTaskRun(record)}>
                    删除
                  </Button>
                </Space>
              ),
            },
          ]}
          scroll={{ x: 1580 }}
        />
      </Spin>
    </section>
  );

  // 函数：renderTaskResultView
  const renderTaskResultView = () => (
    <section className="panel-page">
      <div className="task-search-panel">
        <Form
          form={taskResultSearchForm}
          layout="inline"
          onFinish={(values) => void loadTaskResultPageData(values)}
          className="task-search-form"
        >
          <Form.Item label="结果名称" name="resultName">
            <Input allowClear placeholder="请输入结果名称" />
          </Form.Item>
          <Form.Item label="所属项目" name="projectId">
            <Select
              allowClear
              showSearch
              placeholder="请选择项目"
              optionFilterProp="label"
              options={projects.map((project) => ({ value: project.ID, label: project.projectName }))}
            />
          </Form.Item>
          <Form.Item label="状态" name="status">
            <Select
              allowClear
              placeholder="请选择状态"
              options={taskResultStatusOptions.map((item) => ({ value: item.value, label: item.label }))}
            />
          </Form.Item>
          <Form.Item className="task-search-actions">
            <Space>
              <Button
                icon={<ReloadOutlined />}
                onClick={() => {
                  taskResultSearchForm.resetFields();
                  void loadTaskResultPageData();
                }}
              >
                重置
              </Button>
              <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>
                搜索
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </div>
      <Spin spinning={taskResultLoading}>
        <Table<TaskResult>
          rowKey={(record) => String(record.ID)}
          dataSource={taskResults}
          className="task-table"
          pagination={{
            pageSize: 10,
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 条`,
          }}
          locale={{ emptyText: <Empty description="暂无任务结果" /> }}
          columns={[
            {
              title: 'ID',
              dataIndex: 'ID',
              width: 110,
              fixed: 'left',
              render: (value: number) => <Text strong className="task-name-link">{value}</Text>,
            },
            {
              title: '结果名称',
              dataIndex: 'resultName',
              width: 220,
              render: (value: string) => <Text strong>{value}</Text>,
            },
            {
              title: '所属项目',
              dataIndex: 'projectId',
              width: 180,
              render: (value: number) => projectMap.get(value) || <Text type="secondary">未找到项目</Text>,
            },
            {
              title: '关联任务',
              dataIndex: 'taskRunId',
              width: 130,
              render: (value?: number) => value ? <Tag color="blue">#{value}</Tag> : <Text type="secondary">未关联</Text>,
            },
            {
              title: '数据源',
              dataIndex: 'databaseConfigId',
              width: 180,
              render: (value?: number | null) => (
                value ? connectionMap.get(value) || <Text type="secondary">未找到数据库</Text> : <Text type="secondary">未关联</Text>
              ),
            },
            {
              title: '来源表',
              dataIndex: 'sourceTables',
              width: 140,
              render: (value?: string) => {
                const tables = parseSelectedTables(value);
                return tables.length > 0 ? <Tag color="purple">{tables.length} 张表</Tag> : <Text type="secondary">无</Text>;
              },
            },
            {
              title: '状态',
              dataIndex: 'status',
              width: 120,
              render: (value: TaskResultStatus) => renderTaskResultStatus(value),
            },
            {
              title: '摘要',
              dataIndex: 'summary',
              ellipsis: true,
              render: (value?: string) => value || <Text type="secondary">暂无摘要</Text>,
            },
            {
              title: '解析时间',
              dataIndex: 'parsedAt',
              width: 180,
              render: (value?: string) => formatDateTime(value),
            },
            {
              title: '完成时间',
              dataIndex: 'completedAt',
              width: 180,
              render: (value?: string) => formatDateTime(value),
            },
            {
              title: '操作',
              key: 'action',
              width: 150,
              fixed: 'right',
              render: (_, record) => (
                <Space split={<span className="table-action-split" />}>
                  <Button type="link" size="small" onClick={() => void showTaskResultDetail(record)}>
                    详情
                  </Button>
                  <Button type="link" size="small" danger onClick={() => removeTaskResult(record)}>
                    删除
                  </Button>
                </Space>
              ),
            },
          ]}
          scroll={{ x: 1460 }}
        />
      </Spin>
    </section>
  );

  return (
    <Layout className="app-shell">
      <Header className="top-header">
        <div className="top-brand">
          <div className="brand-icon"><ApiOutlined /></div>
          <div>
            <Text className="brand-name">AI Task Center</Text>
            <Text className="brand-subtitle">任务中心</Text>
          </div>
        </div>
        <nav className="top-nav" aria-label="一级菜单">
          <button
            type="button"
            className={activeModule === 'config' ? 'top-nav-item active' : 'top-nav-item'}
            onClick={() => setActiveModule('config')}
          >
            <SettingOutlined />
            <span>配置管理</span>
          </button>
          <button
            type="button"
            className={activeModule === 'task' ? 'top-nav-item active' : 'top-nav-item'}
            onClick={() => setActiveModule('task')}
          >
            <FileTextOutlined />
            <span>任务配置</span>
          </button>
          <button
            type="button"
            className={activeModule === 'taskRun' ? 'top-nav-item active' : 'top-nav-item'}
            onClick={() => setActiveModule('taskRun')}
          >
            <RobotOutlined />
            <span>任务列表</span>
          </button>
          <button
            type="button"
            className={activeModule === 'taskResult' ? 'top-nav-item active' : 'top-nav-item'}
            onClick={() => setActiveModule('taskResult')}
          >
            <DatabaseOutlined />
            <span>任务结果</span>
          </button>
        </nav>
      </Header>
      <Layout className="main-layout">
        {activeModule === 'config' && (
          <Sider width={252} className="app-sider" theme="light">
            <div className="side-title">
              <Title level={4}>配置管理</Title>
              <Text type="secondary">配置中心子菜单</Text>
            </div>
            <Menu
              mode="inline"
              selectedKeys={[activeTab]}
              onClick={({ key }) => setActiveTab(key as ActiveTab)}
              items={[
                { key: 'project', icon: <CloudServerOutlined />, label: '项目配置' },
                { key: 'database', icon: <DatabaseOutlined />, label: '数据库配置' },
                { key: 'ai', icon: <RobotOutlined />, label: 'AI 配置' },
                { key: 'cli', icon: <CodeOutlined />, label: '本地 CLI 配置' },
              ]}
            />
          </Sider>
        )}
        <Layout>
        <Content className="app-content">
          {activeModule === 'config' && activeTab === 'project' && renderProjectView()}
          {activeModule === 'config' && activeTab === 'database' && renderDatabaseView()}
          {activeModule === 'config' && activeTab === 'ai' && renderAIView()}
          {activeModule === 'config' && activeTab === 'cli' && renderCliView()}
          {activeModule === 'task' && renderTaskView()}
          {activeModule === 'taskRun' && renderTaskRunView()}
          {activeModule === 'taskResult' && renderTaskResultView()}
        </Content>
        </Layout>
      </Layout>

      <Modal
        title={editingProject ? '编辑项目' : '新增项目'}
        open={projectModalOpen}
        onCancel={() => setProjectModalOpen(false)}
        onOk={() => void saveProject()}
        okText="保存"
        cancelText="取消"
      >
        <Form layout="vertical" form={projectForm}>
          <Form.Item label="项目名称" name="projectName" rules={[{ required: true, message: '请输入项目名称' }]}>
            <Input placeholder="新项目名称" />
          </Form.Item>
          <Form.Item label="项目介绍" name="projectDesc">
            <Input.TextArea rows={3} placeholder="项目介绍（可选）" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={editingConnection ? '编辑数据源连接' : '新增数据源连接'}
        open={connectionModalOpen}
        onCancel={() => setConnectionModalOpen(false)}
        onOk={() => void saveConnection()}
        okText={editingConnection ? '保存修改' : '确认添加'}
        cancelText="取消"
        width={780}
        footer={(_, { OkBtn, CancelBtn }) => (
          <>
            <Button loading={testingConnection} onClick={() => void testCurrentConnection()}>
              测试连接
            </Button>
            <CancelBtn />
            <OkBtn />
          </>
        )}
      >
        <Form layout="vertical" form={connectionForm}>
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item label="连接名称" name="connectionName" rules={[{ required: true, message: '请填写连接名称' }]}>
                <Input placeholder="测试环境主库" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="部署环境" name="envName">
                <Select
                  showSearch
                  allowClear
                  placeholder="选择或输入环境"
                  options={envOptions.map((env) => ({ value: String(env), label: String(env) }))}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="数据库类型" name="connectionType" rules={[{ required: true }]}>
                <Select
                  onChange={(value) => connectionForm.setFieldValue('port', defaultPorts[value] ?? 3306)}
                  options={Object.keys(defaultPorts).map((value) => ({ value, label: value }))}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="Host 地址" name="connectionUrl" rules={[{ required: true, message: '请填写 Host 地址' }]}>
                <Input placeholder="127.0.0.1" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="端口" name="port" rules={[{ required: true, message: '请填写端口' }]}>
                <InputNumber min={0} className="full-field" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="数据库名" name="databaseName" rules={[{ required: true, message: '请填写数据库名' }]}>
                <Input />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="用户名" name="dbLoginName" rules={[{ required: true, message: '请填写用户名' }]}>
                <Input />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="密码" name="dbLoginPassword">
                <Input
                  type={showDbPassword ? 'text' : 'password'}
                  autoComplete="off"
                  suffix={
                    <Button
                      type="text"
                      size="small"
                      icon={showDbPassword ? <EyeInvisibleOutlined /> : <EyeOutlined />}
                      onClick={() => setShowDbPassword((value) => !value)}
                    />
                  }
                />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>

      <Modal
        title={editingTask ? '编辑任务配置' : '新增任务配置'}
        open={taskModalOpen}
        onCancel={() => setTaskModalOpen(false)}
        onOk={() => void saveTask()}
        okText="保存"
        cancelText="取消"
        width={720}
      >
        <Form layout="vertical" form={taskForm}>
          <Form.Item label="任务名称" name="taskName" rules={[{ required: true, message: '请填写任务名称' }]}>
            <Input placeholder="生成单词练习页面" />
          </Form.Item>
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item label="所属项目" name="projectId" rules={[{ required: true, message: '请选择所属项目' }]}>
                <Select
                  showSearch
                  placeholder="选择项目"
                  optionFilterProp="label"
                  options={projects.map((project) => ({ value: project.ID, label: project.projectName }))}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="默认执行工具" name="cliId" rules={[{ required: true, message: '请选择默认执行工具' }]}>
                <Select
                  showSearch
                  placeholder="选择 CLI"
                  optionFilterProp="label"
                  options={cliConfigs.map((config) => ({ value: config.id, label: config.label || config.id }))}
                />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item label="关联数据库" name="databaseConfigId">
            <Select
              allowClear
              showSearch
              placeholder="可选，选择数据库配置"
              optionFilterProp="label"
              options={taskConnections.map((connection) => ({
                value: connection.ID,
                label: `${connection.connectionName} / ${connection.databaseName}`,
              }))}
            />
          </Form.Item>
          <Form.Item label="任务描述" name="taskDesc">
            <Input.TextArea rows={4} placeholder="描述这个任务要做什么、适合什么时候使用" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="选择数据表"
        open={tableModalOpen}
        onCancel={() => setTableModalOpen(false)}
        onOk={() => void saveSelectedTables()}
        okText="保存"
        cancelText="取消"
        width={720}
        confirmLoading={savingTables}
      >
        <Spin spinning={tableLoading}>
          <Space direction="vertical" size={14} className="full-field">
            <div className="table-picker-meta">
              <Text strong>{selectedTask?.taskName || '未选择任务'}</Text>
              <Text type="secondary">
                数据源：{selectedTask?.databaseConfigId ? connectionMap.get(selectedTask.databaseConfigId) || '未找到数据库' : '未关联'}
              </Text>
            </div>
            <Select
              mode="multiple"
              allowClear
              showSearch
              value={selectedTables}
              onChange={setSelectedTables}
              placeholder="请选择需要 CLI 读取结构和数据的表"
              optionFilterProp="label"
              className="full-field"
              options={availableTables.map((table) => ({ value: table, label: table }))}
              maxTagCount="responsive"
            />
            <Text type="secondary">
              未来执行 CLI 任务时，可以根据这里选择的表读取表结构、字段信息和样例数据。
            </Text>
          </Space>
        </Spin>
      </Modal>

      <Modal
        title="新增任务"
        open={taskRunCreateOpen}
        onCancel={() => setTaskRunCreateOpen(false)}
        onOk={() => void saveTaskRun()}
        okText="创建"
        cancelText="取消"
        width={680}
      >
        <Form layout="vertical" form={taskRunCreateForm}>
          <Form.Item label="任务配置" name="taskConfigId" rules={[{ required: true, message: '请选择任务配置' }]}>
            <Select
              showSearch
              placeholder="选择任务配置"
              optionFilterProp="label"
              options={tasks.map((task) => ({
                value: task.ID,
                label: `${task.taskName} / ${projectMap.get(task.projectId) || '未找到项目'}`,
              }))}
            />
          </Form.Item>
          <Form.Item label="任务名称" name="taskName">
            <Input placeholder="默认使用任务配置名称" />
          </Form.Item>
          <Text type="secondary">
            创建后会生成一条待执行任务记录，后续执行器会根据任务配置中的项目、CLI、数据源和已选表运行。
          </Text>
        </Form>
      </Modal>

      <Modal
        title="开始执行"
        open={taskRunStartOpen}
        onCancel={() => setTaskRunStartOpen(false)}
        onOk={() => void startSelectedTaskRuns()}
        okText="提交执行"
        cancelText="取消"
        width={680}
        confirmLoading={startingTaskRuns}
      >
        <Form layout="vertical" form={taskRunStartForm}>
          <Form.Item label="执行 CLI" name="cliId" rules={[{ required: true, message: '请选择执行 CLI' }]}>
            <Select
              showSearch
              placeholder="选择 Codex 或 Antigravity"
              optionFilterProp="label"
              options={cliConfigs
                .filter((config) => config.enabled)
                .map((config) => ({ value: config.id, label: config.label || config.id }))}
            />
          </Form.Item>
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item label="执行方式" name="executionMode" rules={[{ required: true, message: '请选择执行方式' }]}>
                <Select
                  options={[
                    { value: 'thread', label: '多线程' },
                    { value: 'process', label: '多进程' },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item label="线程/进程数量" name="workerCount" rules={[{ required: true, message: '请填写数量' }]}>
                <InputNumber min={1} max={32} className="full-field" />
              </Form.Item>
            </Col>
          </Row>
          <Text type="secondary">
            将提交 {selectedTaskRunIds.length} 条任务给 Python Worker，Worker 会根据这里选择的 CLI 和并发数量决定执行方式。
          </Text>
        </Form>
      </Modal>

      <Modal
        title="任务日志"
        open={logModalOpen}
        onCancel={() => setLogModalOpen(false)}
        footer={<Button onClick={() => setLogModalOpen(false)}>关闭</Button>}
        width={760}
      >
        <Space direction="vertical" size={12} className="full-field">
          <div className="table-picker-meta">
            <Text strong>{currentLogRun?.taskName || '未选择任务'}</Text>
            <Text type="secondary">状态：{currentLogRun ? taskRunStatusLabel(currentLogRun.status) : '-'}</Text>
          </div>
          <pre className="task-log-box">{currentLogRun?.runLog || '暂无日志'}</pre>
        </Space>
      </Modal>

      <Modal
        title="任务结果详情"
        open={resultDetailOpen}
        onCancel={() => setResultDetailOpen(false)}
        footer={<Button onClick={() => setResultDetailOpen(false)}>关闭</Button>}
        width={860}
      >
        <Space direction="vertical" size={12} className="full-field">
          <div className="table-picker-meta">
            <Text strong>{currentResult?.resultName || '未选择结果'}</Text>
            <Text type="secondary">
              状态：{currentResult ? taskResultStatusLabel(currentResult.status) : '-'}
              {' / '}
              项目：{currentResult ? projectMap.get(currentResult.projectId) || '未找到项目' : '-'}
            </Text>
            <Text type="secondary">
              来源表：{parseSelectedTables(currentResult?.sourceTables).join(', ') || '无'}
            </Text>
          </div>
          {currentResult?.summary && (
            <div className="table-picker-meta">
              <Text strong>摘要</Text>
              <Text>{currentResult.summary}</Text>
            </div>
          )}
          {currentResult?.errorMessage && (
            <div className="table-picker-meta">
              <Text strong type="danger">错误信息</Text>
              <Text type="danger">{currentResult.errorMessage}</Text>
            </div>
          )}
          <pre className="task-log-box">{currentResult?.resultContent || '暂无结果内容'}</pre>
        </Space>
      </Modal>
    </Layout>
  );
}

// 函数：validateAI
function validateAI(providers: AIProviderConfigItem[], active: string) {
  if (providers.length === 0) return '请至少添加一个 AI 配置';
  const ids = new Set<string>();
  for (const provider of providers) {
    const id = provider.id.trim();
    if (!id) return '请填写 AI 配置 ID';
    if (ids.has(id)) return `AI 配置 ID「${id}」重复`;
    ids.add(id);
    if (!provider.base_url.trim()) return `请填写「${id}」的 Base URL`;
    if (!provider.model.trim()) return `请填写「${id}」的模型名称`;
  }
  if (!ids.has(active)) return '请选择默认 AI 配置';
  return '';
}

// 函数：validateCliConfigs
function validateCliConfigs(configs: LocalCliConfigItem[], active: string) {
  if (configs.length === 0) return '请至少添加一个 CLI 配置';
  const ids = new Set<string>();
  for (const config of configs) {
    const id = config.id.trim();
    if (!id) return '请填写 CLI 配置 ID';
    if (ids.has(id)) return `CLI 配置 ID「${id}」重复`;
    ids.add(id);
    if (!config.command.trim()) return `请填写「${id}」的命令路径`;
    if (!config.workingDirectory.trim()) return `请填写「${id}」的默认工作目录`;
  }
  if (!ids.has(active)) return '请选择默认 CLI 配置';
  return '';
}

// 函数：errorMessage
function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback;
}

// 函数：formatDateTime
function formatDateTime(value?: string) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

// 函数：taskRunStatusLabel
function taskRunStatusLabel(status: TaskRunStatus) {
  return taskRunStatusOptions.find((item) => item.value === status)?.label || status;
}

// 函数：renderTaskRunStatus
function renderTaskRunStatus(status: TaskRunStatus) {
  const option = taskRunStatusOptions.find((item) => item.value === status);
  return <Tag color={option?.color || 'default'}>{option?.label || status}</Tag>;
}

// 函数：taskResultStatusLabel
function taskResultStatusLabel(status: TaskResultStatus) {
  return taskResultStatusOptions.find((item) => item.value === status)?.label || status;
}

// 函数：renderTaskResultStatus
function renderTaskResultStatus(status: TaskResultStatus) {
  const option = taskResultStatusOptions.find((item) => item.value === status);
  return <Tag color={option?.color || 'default'}>{option?.label || status}</Tag>;
}

// 函数：parseSelectedTables
function parseSelectedTables(value?: string) {
  if (!value) return [];
  try {
    const parsed = JSON.parse(value);
    if (Array.isArray(parsed)) {
      return parsed.map((item) => String(item)).filter(Boolean);
    }
  } catch {
    return value.split(',').map((item) => item.trim()).filter(Boolean);
  }
  return [];
}

// 函数：splitArgs
function splitArgs(value: string | undefined) {
  return (value || '')
    .split(/\s+/)
    .map((item) => item.trim())
    .filter(Boolean);
}
