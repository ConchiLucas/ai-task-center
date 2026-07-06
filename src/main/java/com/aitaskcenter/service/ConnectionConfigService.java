package com.aitaskcenter.service;

import com.aitaskcenter.dto.PageResult;
import com.aitaskcenter.model.ConnectionConfig;
import com.aitaskcenter.repository.ConnectionConfigRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ConnectionConfigService {
    private final ConnectionConfigRepository repository;

    public ConnectionConfigService(ConnectionConfigRepository repository) {
        this.repository = repository;
    }

    public PageResult<ConnectionConfig> list(int page, int pageSize, String connectionGroup, String envName) {
        List<ConnectionConfig> all = StringUtils.hasText(connectionGroup)
                ? repository.findByConnectionGroupOrderByCreatedAtDesc(connectionGroup.trim())
                : repository.findAll();
        List<ConnectionConfig> filtered = all.stream()
                .filter(item -> !StringUtils.hasText(envName) || envName.trim().equals(item.getEnvName()))
                .toList();
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(pageSize, 1);
        int from = Math.min((safePage - 1) * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());
        return new PageResult<>(filtered.subList(from, to), filtered.size(), safePage, safeSize);
    }

    @Transactional
    public ConnectionConfig create(ConnectionConfig input) {
        ConnectionConfig config = new ConnectionConfig();
        copyAndValidate(input, config);
        return repository.save(config);
    }

    @Transactional
    public ConnectionConfig update(Long id, ConnectionConfig input) {
        ConnectionConfig config = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("数据库配置不存在"));
        copyAndValidate(input, config);
        return repository.save(config);
    }

    public void delete(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("缺少数据库配置 ID");
        }
        repository.deleteById(id);
    }

    public void test(ConnectionConfig input) {
        String jdbcUrl = jdbcUrl(input);
        Properties properties = new Properties();
        properties.put("user", clean(input.getDbLoginName()));
        properties.put("password", input.getDbLoginPassword() == null ? "" : input.getDbLoginPassword());
        DriverManager.setLoginTimeout((int) Duration.ofSeconds(5).toSeconds());
        try (Connection ignored = DriverManager.getConnection(jdbcUrl, properties)) {
        } catch (Exception ex) {
            throw new IllegalArgumentException("连接失败: " + ex.getMessage());
        }
    }

    public void testById(Long id) {
        ConnectionConfig config = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("数据库配置不存在"));
        test(config);
    }

    private static void copyAndValidate(ConnectionConfig input, ConnectionConfig target) {
        target.setConnectionName(require(input.getConnectionName(), "请填写连接名称"));
        target.setConnectionType(defaultText(input.getConnectionType(), "mysql").toLowerCase(Locale.ROOT));
        target.setConnectionUrl(require(input.getConnectionUrl(), "请填写 Host 地址"));
        target.setConnectionGroup(require(input.getConnectionGroup(), "请先选择项目"));
        target.setDatabaseName(require(input.getDatabaseName(), "请填写数据库名"));
        target.setPort(input.getPort() == null ? defaultPort(target.getConnectionType()) : input.getPort());
        target.setDbLoginName(require(input.getDbLoginName(), "请填写用户名"));
        target.setDbLoginPassword(input.getDbLoginPassword() == null ? "" : input.getDbLoginPassword());
        target.setEnvName(clean(input.getEnvName()));
        target.setUserName(defaultText(input.getUserName(), "local"));
    }

    private static String jdbcUrl(ConnectionConfig input) {
        String type = defaultText(input.getConnectionType(), "mysql").toLowerCase(Locale.ROOT);
        String host = require(input.getConnectionUrl(), "请填写 Host 地址");
        int port = input.getPort() == null ? defaultPort(type) : input.getPort();
        String database = require(input.getDatabaseName(), "请填写数据库名");
        return switch (type) {
            case "pgsql", "postgres", "postgresql" -> "jdbc:postgresql://" + host + ":" + port + "/" + database;
            case "mysql" -> "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
            case "mssql", "sqlserver" -> "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + database + ";encrypt=false";
            case "oracle" -> "jdbc:oracle:thin:@" + host + ":" + port + "/" + database;
            case "sqlite" -> "jdbc:sqlite:" + database;
            default -> throw new IllegalArgumentException("暂不支持该数据库类型: " + type);
        };
    }

    private static int defaultPort(String type) {
        return switch (type) {
            case "pgsql", "postgres", "postgresql" -> 5432;
            case "mssql", "sqlserver" -> 1433;
            case "oracle" -> 1521;
            case "sqlite" -> 0;
            default -> 3306;
        };
    }

    private static String require(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String defaultText(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }
}
