# Python Worker 直连 MiMo TTS 设计

## 目标

AI Task Center 的 Python Worker 是所有 AI/TTS 交互的唯一执行边界。移除 TTS 对外部 word-agent 服务的依赖，由 Worker 读取任务中心数据库配置、调用小米 MiMo、保存并提供音频下载。

## 设计

1. 从 `tb_ai_config` 读取 `config_key=default` 的 `active` 和 `providers`。
2. 默认选择 `xiaomi-mimo-tts`，解析数据库实际使用的 `api_key`、`base_url`、`model`，同时兼容驼峰字段。
3. 数据库配置优先；数据库不可用或 Key 缺失时回退 `MIMO_API_KEY` / `WORD_AGENT_MIMO_API_KEY`。
4. 使用现有 `urllib` 调用 `{base_url}/chat/completions`，请求头使用 `api-key`。
5. 解码 `choices[0].message.audio.data`，安全写入 Worker 的 TTS 输出目录。
6. Worker 提供 `/api/tts/files/{file_name}`，TTS 结果中的 `downloadUrl` 指向 Worker 的 `19186` 端口。
7. 批次处理、验证数据约束和来源回填逻辑保持不变，仅替换 TTS 执行器。
8. 删除 `WORD_AGENT_BASE_URL` 和启动脚本中的相关注入。

## 安全

- Key 不写入日志、响应或异常。
- 文件名只允许安全字符，并阻止路径穿越。
- 测试不调用真实小米接口。
- 真实验证仅检查数据库配置来源、Worker 健康接口和下载路由，不生成 TTS。

