import { CopyOutlined, ReloadOutlined } from '@ant-design/icons';
import {
  App as AntApp,
  Button,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  Select,
  Spin,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ConnectionConfig,
  LocalCliConfigItem,
  ProjectConfig,
  TaskConfig,
  TaskOnboardingResponse,
  TaskOnboardingResultSummary,
  TaskOnboardingStep,
  confirmTaskOnboardingBatchValidation,
  confirmTaskOnboardingResultValidation,
  generateTaskOnboardingBatches,
  generateTaskOnboardingResults,
  getTaskOnboarding,
} from './api';

const { Text, Paragraph } = Typography;

interface TaskOnboardingDrawerProps {
  open: boolean;
  task: TaskConfig | null;
  projects: ProjectConfig[];
  connections: ConnectionConfig[];
  cliConfigs: LocalCliConfigItem[];
  onClose: () => void;
  onReady: (task: TaskConfig) => void;
}

const codeSteps: TaskOnboardingStep[] = ['RESULT_CODE', 'BATCH_CODE'];

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}

function resultColumns() {
  return [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '结果名称', dataIndex: 'resultName', width: 180, ellipsis: true },
    { title: '状态', dataIndex: 'status', width: 110, render: (value: string) => <Tag>{value}</Tag> },
    { title: '摘要', dataIndex: 'summary', ellipsis: true, render: (value: string | null) => value || '-' },
  ];
}

function ValidationResults({ results }: { results: TaskOnboardingResultSummary[] }) {
  return (
    <Table<TaskOnboardingResultSummary>
      rowKey="id"
      size="small"
      className="onboarding-validation-table"
      dataSource={results}
      columns={resultColumns()}
      pagination={false}
      locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无验证结果" /> }}
      scroll={{ x: 560 }}
    />
  );
}

export default function TaskOnboardingDrawer({
  open,
  task,
  projects,
  connections,
  cliConfigs,
  onClose,
  onReady,
}: TaskOnboardingDrawerProps) {
  const { message } = AntApp.useApp();
  const [state, setState] = useState<TaskOnboardingResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [batchForm] = Form.useForm();
  const readyTaskId = useRef<number | null>(null);

  const projectName = useMemo(
    () => projects.find((project) => project.ID === task?.projectId)?.projectName || '-',
    [projects, task?.projectId],
  );
  const connectionName = useMemo(
    () => connections.find((connection) => connection.ID === task?.databaseConfigId)?.connectionName || '-',
    [connections, task?.databaseConfigId],
  );

  const refresh = useCallback(async () => {
    if (!task?.ID) return;
    setLoading(true);
    setLoadError('');
    try {
      const response = await getTaskOnboarding(task.ID);
      setState(response);
    } catch (error) {
      setLoadError(errorMessage(error, '加载任务引导失败'));
    } finally {
      setLoading(false);
    }
  }, [task?.ID]);

  useEffect(() => {
    if (!open) {
      setState(null);
      setLoadError('');
      readyTaskId.current = null;
      return;
    }
    void refresh();
  }, [open, refresh]);

  useEffect(() => {
    if (!open) return undefined;
    const onFocus = () => void refresh();
    window.addEventListener('focus', onFocus);
    return () => window.removeEventListener('focus', onFocus);
  }, [open, refresh]);

  useEffect(() => {
    if (!task || state?.currentStep !== 'READY' || readyTaskId.current === task.ID) return;
    readyTaskId.current = task.ID || null;
    onReady(task);
  }, [onReady, state?.currentStep, task]);

  useEffect(() => {
    if (state?.currentStep !== 'BATCH_GENERATION' || !task) return;
    batchForm.setFieldsValue({
      batchSize: 50,
      cliId: task.cliId || cliConfigs.find((config) => config.enabled)?.id,
      taskNamePrefix: task.taskName,
      includeFailed: false,
    });
  }, [batchForm, cliConfigs, state?.currentStep, task]);

  const isAllowed = (action: string) => state?.allowedActions.includes(action) === true;
  const applyResponse = (response: TaskOnboardingResponse) => setState(response);

  const copyPrompt = async () => {
    if (!state?.prompt || !isAllowed('COPY_PROMPT')) return;
    try {
      await navigator.clipboard.writeText(state.prompt);
      message.success('提示词已复制');
    } catch (error) {
      message.error(errorMessage(error, '复制提示词失败'));
    }
  };

  const submit = async (action: () => Promise<TaskOnboardingResponse>, fallback: string) => {
    if (!task?.ID) return;
    setSubmitting(true);
    try {
      applyResponse(await action());
    } catch (error) {
      message.error(errorMessage(error, fallback));
      await refresh();
    } finally {
      setSubmitting(false);
    }
  };

  const submitBatchGeneration = async () => {
    if (!task?.ID || !isAllowed('GENERATE_BATCHES')) return;
    const values = await batchForm.validateFields();
    await submit(
      () => generateTaskOnboardingBatches(task.ID!, {
        batchSize: Number(values.batchSize),
        cliId: values.cliId,
        taskNamePrefix: values.taskNamePrefix,
        includeFailed: values.includeFailed === true,
      }),
      '生成执行批次失败',
    );
  };

  const renderNodeBody = (nodeStep: TaskOnboardingStep) => {
    if (!state) return null;
    const isCurrent = state.currentStep === nodeStep;
    const isComplete = state.nodes.find((node) => node.step === nodeStep)?.state === 'COMPLETED';

    if (codeSteps.includes(nodeStep) && (isCurrent || isComplete)) {
      return (
        <div className="onboarding-node-body">
          {isCurrent && state.prompt ? (
            <>
              <pre className="onboarding-prompt">{state.prompt}</pre>
              {isAllowed('COPY_PROMPT') && (
                <Button icon={<CopyOutlined />} onClick={() => void copyPrompt()}>
                  复制提示词
                </Button>
              )}
            </>
          ) : <Text type="secondary">已完成代码定制与回填。</Text>}
        </div>
      );
    }

    if (nodeStep === 'RESULT_VALIDATION' && (isCurrent || isComplete)) {
      return (
        <div className="onboarding-node-body">
          <ValidationResults results={state.validationResults} />
          {isCurrent && isAllowed('CONFIRM_RESULT_VALIDATION') && (
            <Button type="primary" loading={submitting} onClick={() => void submit(
              () => confirmTaskOnboardingResultValidation(task!.ID!),
              '确认任务结果验证失败',
            )}>
              确认验证结果
            </Button>
          )}
        </div>
      );
    }

    if (nodeStep === 'RESULT_GENERATION' && (isCurrent || isComplete)) {
      return (
        <div className="onboarding-node-body">
          {isCurrent && isAllowed('GENERATE_RESULTS') ? (
            <Button type="primary" loading={submitting} onClick={() => void submit(
              () => generateTaskOnboardingResults(task!.ID!),
              '生成任务结果失败',
            )}>
              正式生成结果
            </Button>
          ) : <Text type="secondary">已完成正式任务结果生成。</Text>}
        </div>
      );
    }

    if (nodeStep === 'BATCH_VALIDATION' && (isCurrent || isComplete)) {
      return (
        <div className="onboarding-node-body">
          {state.validationRun && (
            <div className="onboarding-run-summary">
              <Text strong>{state.validationRun.taskName}</Text>
              <Tag>{state.validationRun.status}</Tag>
              <Text type="secondary">预期结果：{state.validationRun.expectedResultCount ?? '-'}</Text>
            </div>
          )}
          <ValidationResults results={state.validationRunResults} />
          {isCurrent && isAllowed('CONFIRM_BATCH_VALIDATION') && (
            <Button type="primary" loading={submitting} onClick={() => void submit(
              () => confirmTaskOnboardingBatchValidation(task!.ID!),
              '确认任务批次验证失败',
            )}>
              确认验证批次
            </Button>
          )}
        </div>
      );
    }

    if (nodeStep === 'BATCH_GENERATION' && (isCurrent || isComplete)) {
      return (
        <div className="onboarding-node-body">
          {isCurrent && isAllowed('GENERATE_BATCHES') ? (
            <Form form={batchForm} layout="vertical" className="onboarding-generation-form">
              <Form.Item label="每个批次数量" name="batchSize" rules={[{ required: true, message: '请填写每批数量' }]}>
                <InputNumber min={1} max={1000} className="full-field" />
              </Form.Item>
              <Form.Item label="默认执行 CLI" name="cliId" rules={[{ required: true, message: '请选择执行 CLI' }]}>
                <Select options={cliConfigs.filter((config) => config.enabled).map((config) => ({ value: config.id, label: config.label || config.id }))} />
              </Form.Item>
              <Form.Item label="任务名称前缀" name="taskNamePrefix" rules={[{ required: true, message: '请填写任务名称前缀' }]}>
                <Input />
              </Form.Item>
              <Form.Item label="失败结果" name="includeFailed" valuePropName="checked">
                <Switch checkedChildren="包含失败结果" unCheckedChildren="只处理待处理" />
              </Form.Item>
              <Button type="primary" loading={submitting} onClick={() => void submitBatchGeneration()}>
                正式生成批次
              </Button>
            </Form>
          ) : <Text type="secondary">已完成正式执行批次生成。</Text>}
        </div>
      );
    }

    if (nodeStep === 'READY' && (isCurrent || isComplete)) {
      return <Text type="secondary">任务已就绪。</Text>;
    }

    return null;
  };

  return (
    <Drawer
      title={task ? `任务引导：${task.taskName}` : '任务引导'}
      open={open}
      onClose={onClose}
      width="min(900px, 82vw)"
      className="task-onboarding-drawer"
      extra={<Button icon={<ReloadOutlined />} loading={loading} onClick={() => void refresh()}>刷新</Button>}
    >
      <Spin spinning={loading && !state}>
        {!task ? <Empty description="未选择任务配置" /> : loadError ? (
          <div className="onboarding-error-state">
            <Paragraph type="danger">{loadError}</Paragraph>
            <Button type="primary" onClick={() => void refresh()}>重新加载</Button>
          </div>
        ) : state ? (
          <div className="onboarding-content">
            <div className="onboarding-task-summary">
              <Text strong>{state.task.taskName}</Text>
              <Text type="secondary">项目：{projectName}</Text>
              <Text type="secondary">数据源：{connectionName}</Text>
              {state.errorMessage && <Text type="danger">{state.errorMessage}</Text>}
            </div>
            <div className="onboarding-flow" aria-label="任务引导步骤">
              {state.nodes.map((node, index) => (
                <div key={node.step} className="onboarding-flow-item">
                  <button
                    type="button"
                    className={`onboarding-node onboarding-node-${node.state.toLowerCase()}`}
                    onClick={() => node.state === 'LOCKED' && message.warning('请先完成上一步')}
                  >
                    <span className="onboarding-node-number">{index + 1}</span>
                    <span>{node.label}</span>
                  </button>
                  {renderNodeBody(node.step)}
                </div>
              ))}
            </div>
          </div>
        ) : <Empty description="暂无引导状态" />}
      </Spin>
    </Drawer>
  );
}
