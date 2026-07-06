# AI Task Center

配置管理功能的 Java + PostgreSQL 实现，参考 `vibecoding-utils` 的配置管理页，保留：

- 项目配置
- 数据库配置
- AI 配置

已去掉左侧“服务器配置”。

## 启动 PostgreSQL

```bash
docker compose up -d postgres
```

默认连接信息与 `.env.example` 一致：

```text
jdbc:postgresql://localhost:55432/ai_task_center
user: conchi
password: conchi123456
```

## 启动后端与页面

```bash
mvn spring-boot:run
```

打开：

```text
http://localhost:8080
```

Spring Boot 会通过 JPA 自动创建本地表：

- `tb_interface_project`
- `tb_connection`
- `tb_ai_config`
