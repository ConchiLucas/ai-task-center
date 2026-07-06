const state = {
  tab: 'project',
  projects: [],
  activeProjectName: localStorage.getItem('activeProjectName') || '',
  activeProjectId: Number(localStorage.getItem('activeProjectId') || 0) || null,
  connections: [],
  activeConnectionId: Number(localStorage.getItem('activeConnectionId') || 0) || null,
  editingProjectId: null,
  editingConnectionId: null,
  providers: [],
  activeProviderId: '',
  selectedProviderId: '',
};

const ports = {
  mysql: 3306,
  pgsql: 5432,
  mssql: 1433,
  oracle: 1521,
  sqlite: 0,
};

const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => Array.from(document.querySelectorAll(selector));

function toast(message) {
  const el = $('#toast');
  el.textContent = message;
  el.classList.add('show');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => el.classList.remove('show'), 2400);
}

async function api(path, options = {}) {
  const response = await fetch(`/api${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers || {}),
    },
  });
  const result = await response.json();
  if (!response.ok || result.code !== 0) {
    throw new Error(result.msg || `请求失败 (${response.status})`);
  }
  return result.data;
}

function confirmAction(message, title = '确认操作') {
  const dialog = $('#confirm-dialog');
  $('#confirm-title').textContent = title;
  $('#confirm-message').textContent = message;
  dialog.showModal();
  return new Promise((resolve) => {
    dialog.addEventListener('close', () => resolve(dialog.returnValue === 'ok'), { once: true });
  });
}

function setActiveProject(name, id) {
  state.activeProjectName = name || '';
  state.activeProjectId = id || null;
  localStorage.setItem('activeProjectName', state.activeProjectName);
  localStorage.setItem('activeProjectId', state.activeProjectId || '');
}

function setActiveConnection(id) {
  state.activeConnectionId = id || null;
  localStorage.setItem('activeConnectionId', state.activeConnectionId || '');
  renderConnections();
}

function switchTab(tab) {
  state.tab = tab;
  $$('.tab-button').forEach((button) => button.classList.toggle('active', button.dataset.tab === tab));
  $$('.view').forEach((view) => view.classList.remove('active'));
  $(`#${tab}-view`).classList.add('active');
  if (tab === 'project') loadProjects();
  if (tab === 'database') loadConnections();
  if (tab === 'ai') loadAiConfig();
}

async function loadProjects() {
  try {
    state.projects = await api('/project/getTbInterfaceProjectList');
    if (!state.activeProjectId && state.projects.length > 0) {
      setActiveProject(state.projects[0].projectName, state.projects[0].ID);
    }
    if (state.activeProjectId && !state.projects.some((item) => item.ID === state.activeProjectId)) {
      setActiveProject('', null);
    }
    renderProjects();
  } catch (error) {
    toast(error.message || '加载项目失败');
  }
}

function renderProjects() {
  const list = $('#project-list');
  if (state.projects.length === 0) {
    list.innerHTML = '<div class="empty-state"><p>暂无项目配置</p></div>';
    return;
  }
  list.innerHTML = state.projects.map((project) => {
    const active = state.activeProjectId === project.ID;
    if (state.editingProjectId === project.ID) {
      return `
        <article class="project-card ${active ? 'active' : ''}" data-project-id="${project.ID}">
          <form class="inline-edit" data-edit-project="${project.ID}">
            <input name="projectName" value="${escapeHtml(project.projectName)}" placeholder="项目名称..." />
            <input name="projectDesc" value="${escapeHtml(project.projectDesc || '')}" placeholder="项目介绍 (可选)..." />
            <div class="card-actions editing">
              <button class="text-button" type="submit">保存</button>
              <button class="text-button" type="button" data-cancel-project>取消</button>
            </div>
          </form>
        </article>
      `;
    }
    return `
      <article class="project-card ${active ? 'active' : ''}" data-project-id="${project.ID}">
        <div class="card-title">
          <div class="card-mark">P</div>
          <h3>${escapeHtml(project.projectName)}</h3>
        </div>
        <p title="${escapeHtml(project.projectDesc || '暂无项目介绍')}">${escapeHtml(project.projectDesc || '暂无项目介绍')}</p>
        <div class="card-actions">
          <button class="text-button" type="button" data-edit-project-button="${project.ID}">编辑</button>
          <button class="text-button danger" type="button" data-delete-project="${project.ID}">删除项目</button>
        </div>
      </article>
    `;
  }).join('');
}

async function createProject(event) {
  event.preventDefault();
  const name = $('#new-project-name').value.trim();
  const desc = $('#new-project-desc').value.trim();
  if (!name) {
    toast('请输入项目名称');
    return;
  }
  try {
    const project = await api('/project/createTbInterfaceProject', {
      method: 'POST',
      body: JSON.stringify({ projectName: name, projectDesc: desc }),
    });
    setActiveProject(project.projectName, project.ID);
    $('#new-project-name').value = '';
    $('#new-project-desc').value = '';
    toast('项目创建成功');
    await loadProjects();
  } catch (error) {
    toast(error.message || '项目创建失败');
  }
}

async function loadConnections() {
  const hasProject = !!state.activeProjectId;
  $('#database-empty-project').classList.toggle('hidden', hasProject);
  $('#database-workspace').classList.toggle('hidden', !hasProject);
  if (!hasProject) return;
  try {
    const envName = $('#env-filter').value;
    const data = await api(`/connection/getTbConnectionList?page=1&pageSize=999&connectionGroup=${state.activeProjectId}&envName=${encodeURIComponent(envName)}`);
    state.connections = data.list || [];
    if (state.connections.length === 0) {
      state.activeConnectionId = null;
    } else if (!state.connections.some((item) => item.ID === state.activeConnectionId)) {
      state.activeConnectionId = state.connections[0].ID;
    }
    localStorage.setItem('activeConnectionId', state.activeConnectionId || '');
    renderEnvOptions();
    renderConnections();
  } catch (error) {
    toast(error.message || '加载数据库配置失败');
  }
}

function renderEnvOptions() {
  const envs = [...new Set(state.connections.map((item) => item.envName).filter(Boolean))].sort((a, b) => a.localeCompare(b, 'zh-CN'));
  const current = $('#env-filter').value;
  $('#env-filter').innerHTML = '<option value="">全部环境</option>' + envs.map((env) => `<option value="${escapeHtml(env)}">${escapeHtml(env)}</option>`).join('');
  $('#env-filter').value = envs.includes(current) ? current : '';
  $('#env-options').innerHTML = envs.map((env) => `<option value="${escapeHtml(env)}"></option>`).join('');
}

function renderConnections() {
  const list = $('#connection-list');
  if (state.connections.length === 0) {
    list.innerHTML = '<div class="empty-state"><p>暂无属于该项目的数据库配置</p></div>';
    return;
  }
  list.innerHTML = state.connections.map((conn) => {
    const active = state.activeConnectionId === conn.ID;
    return `
      <article class="connection-card ${active ? 'active' : ''}" data-connection-id="${conn.ID}">
        <div class="connection-top">
          <div class="card-title">
            <div class="card-mark">D</div>
            <div>
              <h4>${escapeHtml(conn.connectionName)} ${conn.envName ? `<span class="badge">${escapeHtml(conn.envName)}</span>` : ''}</h4>
              <p>${escapeHtml(conn.connectionType || '')}</p>
            </div>
          </div>
          <div class="card-actions">
            <button class="text-button" type="button" data-edit-connection="${conn.ID}">编辑</button>
            <button class="text-button danger" type="button" data-delete-connection="${conn.ID}">删除</button>
          </div>
        </div>
        <div class="connection-meta">
          <div><span>Host: </span>${escapeHtml(conn.connectionUrl)}</div>
          <div><span>Port: </span>${escapeHtml(String(conn.port ?? ''))}</div>
          <div><span>DB: </span>${escapeHtml(conn.databaseName)}</div>
          <div><span>User: </span>${escapeHtml(conn.dbLoginName)}</div>
        </div>
      </article>
    `;
  }).join('');
}

function showConnectionForm(conn = null) {
  state.editingConnectionId = conn?.ID || null;
  $('#connection-form-title').textContent = conn ? '编辑数据源连接' : '新增数据源连接';
  $('#connection-form button[type="submit"]').textContent = conn ? '保存修改' : '确认添加';
  $('#connection-name').value = conn?.connectionName || '';
  $('#connection-env').value = conn?.envName || '';
  $('#connection-type').value = conn?.connectionType || 'mysql';
  $('#connection-host').value = conn?.connectionUrl || '';
  $('#connection-port').value = conn?.port ?? ports[$('#connection-type').value] ?? 3306;
  $('#connection-database').value = conn?.databaseName || '';
  $('#connection-user').value = conn?.dbLoginName || '';
  $('#connection-password').value = conn?.dbLoginPassword || '';
  $('#connection-password').type = 'password';
  $('#toggle-db-password').textContent = '显示';
  $('#connection-form').classList.remove('hidden');
}

function readConnectionForm() {
  return {
    ID: state.editingConnectionId,
    connectionName: $('#connection-name').value.trim(),
    envName: $('#connection-env').value.trim(),
    connectionType: $('#connection-type').value,
    connectionUrl: $('#connection-host').value.trim(),
    port: Number($('#connection-port').value || 0),
    databaseName: $('#connection-database').value.trim(),
    dbLoginName: $('#connection-user').value.trim(),
    dbLoginPassword: $('#connection-password').value,
    connectionGroup: String(state.activeProjectId || ''),
  };
}

async function saveConnection(event) {
  event.preventDefault();
  const payload = readConnectionForm();
  if (!payload.connectionName || !payload.connectionUrl || !payload.databaseName || !payload.dbLoginName) {
    toast('请填写完整的数据库信息');
    return;
  }
  try {
    await api(state.editingConnectionId ? '/connection/updateTbConnection' : '/connection/createTbConnection', {
      method: state.editingConnectionId ? 'PUT' : 'POST',
      body: JSON.stringify(payload),
    });
    toast(state.editingConnectionId ? '数据库配置修改成功' : '数据库配置添加成功');
    hideConnectionForm();
    await loadConnections();
  } catch (error) {
    toast(error.message || '保存失败');
  }
}

function hideConnectionForm() {
  state.editingConnectionId = null;
  $('#connection-form').classList.add('hidden');
}

async function testConnection() {
  try {
    await api('/connection/testConnectionPayload', {
      method: 'POST',
      body: JSON.stringify(readConnectionForm()),
    });
    toast('连接成功 ✓');
  } catch (error) {
    toast(error.message || '连接失败');
  }
}

async function loadAiConfig() {
  try {
    const data = await api('/ai/config');
    state.providers = data.providers || [];
    state.activeProviderId = data.active || state.providers.find((item) => item.active)?.id || state.providers[0]?.id || '';
    state.selectedProviderId = state.selectedProviderId && state.providers.some((item) => item.id === state.selectedProviderId)
      ? state.selectedProviderId
      : state.activeProviderId || state.providers[0]?.id || '';
    renderAi();
  } catch (error) {
    toast(error.message || 'AI 配置加载异常');
  }
}

function renderAi() {
  const hasProviders = state.providers.length > 0;
  $('#ai-empty').classList.toggle('hidden', hasProviders);
  $('#ai-workspace').classList.toggle('hidden', !hasProviders);
  if (!hasProviders) return;

  $('#ai-provider-list').innerHTML = state.providers.map((provider) => `
    <button class="ai-item ${provider.id === state.selectedProviderId ? 'active' : ''}" data-ai-id="${escapeHtml(provider.id)}" type="button">
      <div class="ai-item-title">
        <span>${escapeHtml(provider.label || provider.id || '未命名 AI')}</span>
        <span>${provider.id === state.activeProviderId ? '默认' : ''}</span>
      </div>
      <p>${escapeHtml(provider.model || '未设置模型')}</p>
    </button>
  `).join('');
  fillAiForm(selectedProvider());
}

function selectedProvider() {
  return state.providers.find((item) => item.id === state.selectedProviderId) || state.providers[0] || null;
}

function fillAiForm(provider) {
  if (!provider) return;
  $('#ai-form-name').textContent = provider.label || provider.id || '未命名 AI';
  $('#ai-id').value = provider.id || '';
  $('#ai-label').value = provider.label || '';
  $('#ai-type').value = provider.type || 'openai-compatible';
  $('#ai-model').value = provider.model || '';
  $('#ai-base-url').value = provider.base_url || '';
  $('#ai-api-key').value = provider.api_key || '';
  $('#ai-max-tokens').value = provider.max_tokens || 4096;
}

function syncAiForm() {
  const provider = selectedProvider();
  if (!provider) return;
  provider.id = $('#ai-id').value;
  provider.label = $('#ai-label').value;
  provider.type = $('#ai-type').value;
  provider.model = $('#ai-model').value;
  provider.base_url = $('#ai-base-url').value;
  provider.api_key = $('#ai-api-key').value;
  provider.max_tokens = Number($('#ai-max-tokens').value || 4096);
  state.selectedProviderId = provider.id;
}

function addAiProvider() {
  syncAiForm();
  let index = state.providers.length + 1;
  let id = `provider-${index}`;
  while (state.providers.some((item) => item.id === id)) {
    index += 1;
    id = `provider-${index}`;
  }
  state.providers.push({
    id,
    label: '',
    type: 'openai-compatible',
    base_url: '',
    api_key: '',
    model: '',
    max_tokens: 4096,
  });
  state.selectedProviderId = id;
  if (!state.activeProviderId) state.activeProviderId = id;
  renderAi();
}

async function deleteAiProvider() {
  if (state.providers.length <= 1) {
    toast('至少保留一个 AI 配置');
    return;
  }
  const provider = selectedProvider();
  if (!provider) return;
  const ok = await confirmAction('确定删除该 AI 配置吗？');
  if (!ok) return;
  state.providers = state.providers.filter((item) => item !== provider);
  if (state.activeProviderId === provider.id) state.activeProviderId = state.providers[0]?.id || '';
  state.selectedProviderId = state.providers[0]?.id || '';
  renderAi();
}

async function setActiveAi() {
  syncAiForm();
  const provider = selectedProvider();
  if (!provider) return;
  try {
    await api('/ai/config/active', {
      method: 'POST',
      body: JSON.stringify({ active: provider.id }),
    });
    state.activeProviderId = provider.id;
    toast('默认 AI 已保存');
    renderAi();
  } catch (error) {
    toast(error.message || '设置默认失败');
  }
}

function validateAiConfig() {
  syncAiForm();
  if (state.providers.length === 0) return '请至少添加一个 AI 配置';
  const ids = new Set();
  for (const provider of state.providers) {
    const id = (provider.id || '').trim();
    if (!id) return '请填写 AI 配置 ID';
    if (ids.has(id)) return `AI 配置 ID「${id}」重复`;
    ids.add(id);
    if (!(provider.base_url || '').trim()) return `请填写「${id}」的 Base URL`;
    if (!(provider.model || '').trim()) return `请填写「${id}」的模型名称`;
  }
  if (!ids.has(state.activeProviderId)) return '请选择默认 AI 配置';
  return '';
}

async function saveAiConfig() {
  const error = validateAiConfig();
  if (error) {
    toast(error);
    return;
  }
  try {
    await api('/ai/config', {
      method: 'POST',
      body: JSON.stringify({
        active: state.activeProviderId,
        providers: state.providers.map((provider) => ({
          id: provider.id.trim(),
          label: (provider.label || '').trim(),
          type: provider.type || 'openai-compatible',
          base_url: (provider.base_url || '').trim(),
          api_key: (provider.api_key || '').trim(),
          model: (provider.model || '').trim(),
          max_tokens: Number(provider.max_tokens) || 4096,
        })),
      }),
    });
    toast('AI 配置已保存');
    await loadAiConfig();
  } catch (error) {
    toast(error.message || '保存 AI 配置异常');
  }
}

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

function bindEvents() {
  $$('.tab-button').forEach((button) => button.addEventListener('click', () => switchTab(button.dataset.tab)));
  $('#project-create-form').addEventListener('submit', createProject);
  $('#project-list').addEventListener('click', async (event) => {
    const editButton = event.target.closest('[data-edit-project-button]');
    const deleteButton = event.target.closest('[data-delete-project]');
    const cancelButton = event.target.closest('[data-cancel-project]');
    const card = event.target.closest('[data-project-id]');
    if (editButton) {
      state.editingProjectId = Number(editButton.dataset.editProjectButton);
      renderProjects();
      return;
    }
    if (deleteButton) {
      const id = Number(deleteButton.dataset.deleteProject);
      const project = state.projects.find((item) => item.ID === id);
      const ok = await confirmAction(`确定删除项目「${project?.projectName || ''}」吗？`);
      if (!ok) return;
      try {
        await api('/project/deleteTbInterfaceProject', { method: 'DELETE', body: JSON.stringify({ ID: id }) });
        if (state.activeProjectId === id) setActiveProject('', null);
        toast('项目删除成功');
        await loadProjects();
      } catch (error) {
        toast(error.message || '项目删除失败');
      }
      return;
    }
    if (cancelButton) {
      state.editingProjectId = null;
      renderProjects();
      return;
    }
    if (card && !event.target.closest('form')) {
      const project = state.projects.find((item) => item.ID === Number(card.dataset.projectId));
      setActiveProject(project.projectName, project.ID);
      renderProjects();
      if (state.tab === 'database') loadConnections();
    }
  });
  $('#project-list').addEventListener('submit', async (event) => {
    const form = event.target.closest('[data-edit-project]');
    if (!form) return;
    event.preventDefault();
    const id = Number(form.dataset.editProject);
    const payload = {
      ID: id,
      projectName: form.projectName.value.trim(),
      projectDesc: form.projectDesc.value.trim(),
    };
    try {
      const updated = await api('/project/updateTbInterfaceProject', { method: 'PUT', body: JSON.stringify(payload) });
      if (state.activeProjectId === id) setActiveProject(updated.projectName, updated.ID);
      state.editingProjectId = null;
      toast('项目更新成功');
      await loadProjects();
    } catch (error) {
      toast(error.message || '项目更新失败');
    }
  });

  $('#show-connection-form').addEventListener('click', () => showConnectionForm());
  $('#cancel-connection').addEventListener('click', hideConnectionForm);
  $('#test-connection').addEventListener('click', testConnection);
  $('#connection-form').addEventListener('submit', saveConnection);
  $('#env-filter').addEventListener('change', loadConnections);
  $('#connection-type').addEventListener('change', () => {
    $('#connection-port').value = ports[$('#connection-type').value] ?? $('#connection-port').value;
  });
  $('#toggle-db-password').addEventListener('click', () => {
    const input = $('#connection-password');
    input.type = input.type === 'password' ? 'text' : 'password';
    $('#toggle-db-password').textContent = input.type === 'password' ? '显示' : '隐藏';
  });
  $('#connection-list').addEventListener('click', async (event) => {
    const editButton = event.target.closest('[data-edit-connection]');
    const deleteButton = event.target.closest('[data-delete-connection]');
    const card = event.target.closest('[data-connection-id]');
    if (editButton) {
      const conn = state.connections.find((item) => item.ID === Number(editButton.dataset.editConnection));
      showConnectionForm(conn);
      return;
    }
    if (deleteButton) {
      const id = Number(deleteButton.dataset.deleteConnection);
      const ok = await confirmAction('确定删除该数据库配置吗？');
      if (!ok) return;
      try {
        await api('/connection/deleteTbConnection', { method: 'DELETE', body: JSON.stringify({ ID: id }) });
        toast('删除成功');
        await loadConnections();
      } catch (error) {
        toast(error.message || '删除失败');
      }
      return;
    }
    if (card) setActiveConnection(Number(card.dataset.connectionId));
  });

  $('#add-ai-provider').addEventListener('click', addAiProvider);
  $('#add-ai-provider-empty').addEventListener('click', addAiProvider);
  $('#delete-ai-provider').addEventListener('click', deleteAiProvider);
  $('#set-active-ai').addEventListener('click', setActiveAi);
  $('#save-ai-config').addEventListener('click', saveAiConfig);
  $('#ai-provider-list').addEventListener('click', (event) => {
    const item = event.target.closest('[data-ai-id]');
    if (!item) return;
    syncAiForm();
    state.selectedProviderId = item.dataset.aiId;
    renderAi();
  });
  ['#ai-id', '#ai-label', '#ai-type', '#ai-model', '#ai-base-url', '#ai-api-key', '#ai-max-tokens'].forEach((selector) => {
    $(selector).addEventListener('input', syncAiForm);
    $(selector).addEventListener('change', () => {
      syncAiForm();
      renderAi();
    });
  });
}

bindEvents();
loadProjects();
