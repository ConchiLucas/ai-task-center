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
  Space,
  Spin,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ConnectionConfig,
  ExecutionTargetItem,
  ProjectConfig,
  TaskConfig,
  TaskOnboardingResponse,
  TaskOnboardingResultSummary,
  TaskOnboardingStep,
  confirmTaskOnboardingBatchValidation,
  confirmTaskOnboardingResultValidation,
  generateTaskOnboardingBatchValidation,
  generateTaskOnboardingBatches,
  generateTaskOnboardingResultValidation,
  generateTaskOnboardingResults,
  getTaskOnboarding,
  selectTaskOnboardingExecutionTarget,
} from './api';

const { Text, Paragraph } = Typography;

interface Props {
  open: boolean;
  task: TaskConfig | null;
  projects: ProjectConfig[];
  connections: ConnectionConfig[];
  executionTargets: ExecutionTargetItem[];
  onClose: () => void;
  onReady: (task: TaskConfig) => void;
}

function readableError(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}

function ValidationResults({ results }: { results: TaskOnboardingResultSummary[] }) {
  return (
    <Table<TaskOnboardingResultSummary>
      rowKey="id"
      size="small"
      dataSource={results}
      pagination={false}
      scroll={{ x: 580 }}
      locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="尚未生成验证数据" /> }}
      columns={[
        { title: 'ID', dataIndex: 'id', width: 80 },
        { title: '结果名称', dataIndex: 'resultName', width: 220, ellipsis: true },
        { title: '状态', dataIndex: 'status', width: 100, render: (value: string) => <Tag>{value}</Tag> },
        { title: '摘要', dataIndex: 'summary', ellipsis: true, render: (value?: string) => value || '-' },
      ]}
    />
  );
}

export default function TaskOnboardingDrawer({
  open,
  task,
  projects,
  connections,
  executionTargets,
  onClose,
  onReady,
}: Props) {
  const { message, modal } = AntApp.useApp();
  const [state, setState] = useState<TaskOnboardingResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [reselectingTarget, setReselectingTarget] = useState(false);
  const [batchForm] = Form.useForm();
  const [targetForm] = Form.useForm();
  const targetKey = Form.useWatch('target', targetForm) as string | undefined;

  const projectName = useMemo(
    () => projects.find((item) => item.ID === state?.task.projectId)?.projectName || '-',
    [projects, state?.task.projectId],
  );
  const connectionName = useMemo(
    () => connections.find((item) => item.ID === state?.task.databaseConfigId)?.connectionName || '-',
    [connections, state?.task.databaseConfigId],
  );
  const selectedTarget = useMemo(
    () => executionTargets.find((item) => (
      item.type === state?.task.executorType && item.id === state?.task.executorId
    )) || null,
    [executionTargets, state?.task.executorId, state?.task.executorType],
  );

  const refresh = useCallback(async () => {
    if (!open || !task?.ID) return;
    setLoading(true);
    setLoadError('');
    try {
      const response = await getTaskOnboarding(task.ID);
      setState(response);
    } catch (error) {
      setLoadError(readableError(error, '加载任务引导失败'));
    } finally {
      setLoading(false);
    }
  }, [open, task?.ID]);

  useEffect(() => {
    if (!open) {
      setState(null);
      setLoadError('');
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
    if (!task || state?.currentStep !== 'BATCH_GENERATION') return;
    batchForm.setFieldsValue({
      batchSize: 50,
      taskNamePrefix: task.taskName,
      includeFailed: false,
    });
  }, [batchForm, state?.currentStep, task]);

  useEffect(() => {
    if (!state?.task.executorType || !state.task.executorId) return;
    targetForm.setFieldValue('target', `${state.task.executorType}:${state.task.executorId}`);
  }, [state?.task.executorId, state?.task.executorType, targetForm]);

  const allowed = (action: string) => state?.allowedActions.includes(action) === true;

  const submit = async (action: () => Promise<TaskOnboardingResponse>, fallback: string, ready = false) => {
    if (submitting || !task) return;
    setSubmitting(true);
    try {
      const response = await action();
      setState(response);
      if (ready && response.currentStep === 'READY') onReady(task);
    } catch (error) {
      message.error(readableError(error, fallback));
      await refresh();
    } finally {
      setSubmitting(false);
    }
  };

  const copyPrompt = async () => {
    if (!state?.prompt) return;
    try {
      await navigator.clipboard.writeText(state.prompt);
      message.success('提示词已复制');
    } catch (error) {
      message.error(readableError(error, '复制提示词失败'));
    }
  };

  const generateValidationBatch = () => {
    if (!task?.ID) return;
    void submit(
      () => generateTaskOnboardingBatchValidation(task.ID!, {
        batchSize: 3,
        taskNamePrefix: `${task.taskName} - 验证`,
        includeFailed: false,
      }),
      '生成验证批次失败',
    );
  };

  const generateFormalBatches = async () => {
    if (!task?.ID) return;
    const values = await batchForm.validateFields();
    await submit(
      () => generateTaskOnboardingBatches(task.ID!, {
        batchSize: Number(values.batchSize),
        taskNamePrefix: values.taskNamePrefix,
        includeFailed: values.includeFailed === true,
      }),
      '正式生成批次失败',
      true,
    );
  };

  const applySelectedTarget = async () => {
    if (!task?.ID) return;
    const values = await targetForm.validateFields();
    const targetKey = String(values.target || '');
    const separator = targetKey.indexOf(':');
    const executorType = targetKey.slice(0, separator) as 'CLI' | 'AI_PROVIDER';
    const executorId = targetKey.slice(separator + 1);
    await submit(
      () => selectTaskOnboardingExecutionTarget(task.ID!, { executorType, executorId }),
      '选择模型调用通道失败',
    );
    setReselectingTarget(false);
  };

  const confirmSelectedTarget = () => {
    if (state?.currentStep === 'TARGET_SELECTION') {
      void applySelectedTarget();
      return;
    }
    modal.confirm({
      title: '重新选择模型调用通道',
      content: '更换通道会清空当前处理器就绪状态，并回到“结果代码准备”步骤。',
      okText: '确认更换',
      cancelText: '取消',
      onOk: applySelectedTarget,
    });
  };

  const body = (step: TaskOnboardingStep) => {
    if (!state) return null;
    if (step === 'TARGET_SELECTION') {
      if (state.currentStep !== step && !reselectingTarget) return null;
      const separator = targetKey?.indexOf(':') ?? -1;
      const candidate = separator > 0
        ? executionTargets.find((item) => (
          item.type === targetKey?.slice(0, separator) && item.id === targetKey?.slice(separator + 1)
        ))
        : null;
      return (
        <div className="onboarding-node-body onboarding-target-picker">
          <Paragraph type="secondary">
            请选择任务运行时唯一使用的模型调用通道。CLI 与 AI API 都只负责返回模型结果，不参与编写接入代码。
          </Paragraph>
          <Form form={targetForm} layout="vertical">
            <Form.Item label="模型调用通道" name="target" rules={[{ required: true, message: '请选择模型调用通道' }]}> 
              <Select
                showSearch
                optionFilterProp="label"
                placeholder="选择一个已启用通道"
                onChange={() => targetForm.validateFields(['target']).catch(() => undefined)}
                options={executionTargets.filter((item) => item.enabled).map((item) => ({
                  value: `${item.type}:${item.id}`,
                  label: `${item.label || item.id} / ${item.protocol} / ${item.capabilities.join(', ')}`,
                }))}
              />
            </Form.Item>
          </Form>
          {candidate && (
            <div className="onboarding-target-detail">
              <Text strong>{candidate.label || candidate.id}</Text>
              <Tag color={candidate.type === 'CLI' ? 'blue' : 'purple'}>{candidate.type}</Tag>
              <Tag>{candidate.protocol}</Tag>
              {candidate.capabilities.map((capability) => <Tag key={capability}>{capability}</Tag>)}
            </div>
          )}
          <Space>
            <Button type="primary" loading={submitting} onClick={confirmSelectedTarget}>确认通道</Button>
            {state.currentStep !== 'TARGET_SELECTION' && (
              <Button onClick={() => setReselectingTarget(false)}>取消</Button>
            )}
          </Space>
        </div>
      );
    }
    if (state.currentStep !== step) return null;
    if (step === 'RESULT_CODE' || step === 'BATCH_CODE') {
      return (
        <div className="onboarding-node-body">
          <Paragraph type="secondary">将提示词复制到任意外部编码工具。工具只修改代码和测试，完成后回填 CODE_READY。</Paragraph>
          <pre className="onboarding-prompt">{state.prompt}</pre>
          <Button icon={<CopyOutlined />} onClick={() => void copyPrompt()}>复制提示词</Button>
        </div>
      );
    }
    if (step === 'RESULT_VALIDATION') {
      return (
        <div className="onboarding-node-body">
          <Paragraph type="secondary">验证由你人工完成。有问题时直接让 Codex继续修改，然后重新生成。</Paragraph>
          <ValidationResults results={state.validationResults} />
          <Space wrap>
            <Button loading={submitting} onClick={() => void submit(
              () => generateTaskOnboardingResultValidation(task!.ID!),
              '生成验证结果失败',
            )}>
              {state.validationResults.length ? '重新小批量生成' : '小批量生成'}
            </Button>
            {allowed('CONFIRM_RESULT_VALIDATION') && (
              <Button type="primary" loading={submitting} onClick={() => void submit(
                () => confirmTaskOnboardingResultValidation(task!.ID!),
                '进入正式生成失败',
              )}>
                完成手动验证，进入下一步
              </Button>
            )}
          </Space>
        </div>
      );
    }
    if (step === 'RESULT_GENERATION') {
      return (
        <div className="onboarding-node-body">
          <Button type="primary" loading={submitting} onClick={() => void submit(
            () => generateTaskOnboardingResults(task!.ID!),
            '正式生成结果失败',
          )}>
            正式生成全部结果
          </Button>
        </div>
      );
    }
    if (step === 'BATCH_VALIDATION') {
      return (
        <div className="onboarding-node-body">
          <Paragraph type="secondary">生成一个待执行验证批次，仅供人工检查，不会出现在正式任务列表。</Paragraph>
          {state.validationRun ? (
            <div className="onboarding-run-summary">
              <Text strong>{state.validationRun.taskName}</Text>
              <Tag>{state.validationRun.status}</Tag>
              <Text type="secondary">结果数：{state.validationRun.expectedResultCount ?? '-'}</Text>
            </div>
          ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="尚未生成验证批次" />}
          <ValidationResults results={state.validationRunResults} />
          <Space wrap>
            <Button loading={submitting} onClick={generateValidationBatch}>
              {state.validationRun ? '重新生成验证批次' : '生成验证批次'}
            </Button>
            {allowed('CONFIRM_BATCH_VALIDATION') && (
              <Button type="primary" loading={submitting} onClick={() => void submit(
                () => confirmTaskOnboardingBatchValidation(task!.ID!),
                '进入正式批次生成失败',
              )}>
                完成手动验证，进入下一步
              </Button>
            )}
          </Space>
        </div>
      );
    }
    if (step === 'BATCH_GENERATION') {
      return (
        <div className="onboarding-node-body">
          <Form form={batchForm} layout="vertical">
            <Form.Item label="每个批次数量" name="batchSize" rules={[{ required: true }]}>
              <InputNumber min={1} max={1000} className="full-field" />
            </Form.Item>
            <Form.Item label="任务名称前缀" name="taskNamePrefix" rules={[{ required: true }]}>
              <Input />
            </Form.Item>
            <Form.Item label="失败结果" name="includeFailed" valuePropName="checked">
              <Switch checkedChildren="包含" unCheckedChildren="不包含" />
            </Form.Item>
            <Button type="primary" loading={submitting} onClick={() => void generateFormalBatches()}>
              正式生成全部批次
            </Button>
          </Form>
        </div>
      );
    }
    return <div className="onboarding-node-body"><Text type="success">任务已完成接入。</Text></div>;
  };

  return (
    <Drawer
      title={task ? `任务接入：${task.taskName}` : '任务接入'}
      open={open}
      onClose={onClose}
      width="min(900px, 88vw)"
      className="task-onboarding-drawer"
      extra={<Button icon={<ReloadOutlined />} loading={loading} disabled={submitting} onClick={() => void refresh()}>刷新</Button>}
    >
      <Spin spinning={loading && !state}>
        {loadError ? (
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
              {selectedTarget && (
                <div className="onboarding-selected-target">
                  <Text type="secondary">模型调用通道：</Text>
                  <Tag color={selectedTarget.type === 'CLI' ? 'blue' : 'purple'}>
                    {selectedTarget.label || selectedTarget.id}
                  </Tag>
                  <Tag>{selectedTarget.protocol}</Tag>
                  {selectedTarget.capabilities.map((capability) => <Tag key={capability}>{capability}</Tag>)}
                  {state.currentStep !== 'TARGET_SELECTION' && (
                    <Button type="link" size="small" onClick={() => setReselectingTarget(true)}>重新选择</Button>
                  )}
                </div>
              )}
              {state.errorMessage && <Text type="danger">{state.errorMessage}</Text>}
            </div>
            <div className="onboarding-flow">
              {state.nodes.map((node, index) => (
                <div key={node.step} className="onboarding-flow-item">
                  <div className={`onboarding-node onboarding-node-${node.state.toLowerCase()}`}>
                    <span className="onboarding-node-number">{index + 1}</span>
                    <span>{node.label}</span>
                  </div>
                  {body(node.step)}
                </div>
              ))}
            </div>
          </div>
        ) : <Empty description="暂无任务接入状态" />}
      </Spin>
    </Drawer>
  );
}
