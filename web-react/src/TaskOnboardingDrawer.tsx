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
  LocalCliConfigItem,
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
} from './api';

const { Text, Paragraph } = Typography;

interface Props {
  open: boolean;
  task: TaskConfig | null;
  projects: ProjectConfig[];
  connections: ConnectionConfig[];
  cliConfigs: LocalCliConfigItem[];
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
  cliConfigs,
  onClose,
  onReady,
}: Props) {
  const { message } = AntApp.useApp();
  const [state, setState] = useState<TaskOnboardingResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [batchForm] = Form.useForm();

  const projectName = useMemo(
    () => projects.find((item) => item.ID === state?.task.projectId)?.projectName || '-',
    [projects, state?.task.projectId],
  );
  const connectionName = useMemo(
    () => connections.find((item) => item.ID === state?.task.databaseConfigId)?.connectionName || '-',
    [connections, state?.task.databaseConfigId],
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
      cliId: task.cliId || cliConfigs.find((item) => item.enabled)?.id,
      taskNamePrefix: task.taskName,
      includeFailed: false,
    });
  }, [batchForm, cliConfigs, state?.currentStep, task]);

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
    const cliId = task.cliId || cliConfigs.find((item) => item.enabled)?.id;
    if (!cliId) {
      message.warning('请先为任务配置执行 CLI');
      return;
    }
    void submit(
      () => generateTaskOnboardingBatchValidation(task.ID!, {
        batchSize: 3,
        cliId,
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
        cliId: values.cliId,
        taskNamePrefix: values.taskNamePrefix,
        includeFailed: values.includeFailed === true,
      }),
      '正式生成批次失败',
      true,
    );
  };

  const body = (step: TaskOnboardingStep) => {
    if (!state || state.currentStep !== step) return null;
    if (step === 'RESULT_CODE' || step === 'BATCH_CODE') {
      return (
        <div className="onboarding-node-body">
          <Paragraph type="secondary">将提示词交给 Codex。Codex只修改代码和测试，完成后回填 CODE_READY。</Paragraph>
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
            <Form.Item label="执行 CLI" name="cliId" rules={[{ required: true }]}>
              <Select options={cliConfigs.filter((item) => item.enabled).map((item) => ({ value: item.id, label: item.label || item.id }))} />
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
