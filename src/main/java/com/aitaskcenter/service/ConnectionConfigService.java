package com.aitaskcenter.service;

import com.aitaskcenter.dto.PageResult;
import com.aitaskcenter.model.ConnectionConfig;
import com.aitaskcenter.repository.ConnectionConfigRepository;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ConnectionConfigService {
    private final ConnectionConfigRepository repository;

    // 方法：ConnectionConfigService
    public ConnectionConfigService(ConnectionConfigRepository repository) {
        this.repository = repository;
    }

    // 方法：list
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
    // 方法：create
    public ConnectionConfig create(ConnectionConfig input) {
        ConnectionConfig config = new ConnectionConfig();
        copyAndValidate(input, config);
        return repository.save(config);
    }

    @Transactional
    // 方法：update
    public ConnectionConfig update(Long id, ConnectionConfig input) {
        ConnectionConfig config = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("数据库配置不存在"));
        copyAndValidate(input, config);
        return repository.save(config);
    }

    // 方法：delete
    public void delete(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("缺少数据库配置 ID");
        }
        repository.deleteById(id);
    }

    // 方法：test
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

    // 方法：testById
    public void testById(Long id) {
        ConnectionConfig config = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("数据库配置不存在"));
        test(config);
    }

    // 方法：listTables
    public List<String> listTables(Long id) {
        ConnectionConfig config = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("数据库配置不存在"));
        Properties properties = new Properties();
        properties.put("user", clean(config.getDbLoginName()));
        properties.put("password", config.getDbLoginPassword() == null ? "" : config.getDbLoginPassword());
        DriverManager.setLoginTimeout((int) Duration.ofSeconds(5).toSeconds());
        try (Connection connection = DriverManager.getConnection(jdbcUrl(config), properties)) {
            DatabaseMetaData metaData = connection.getMetaData();
            Set<String> tables = new LinkedHashSet<>();
            try (ResultSet rs = metaData.getTables(connection.getCatalog(), null, "%", new String[] {"TABLE"})) {
                while (rs.next()) {
                    String schema = rs.getString("TABLE_SCHEM");
                    String tableName = rs.getString("TABLE_NAME");
                    if (isUserTableSchema(schema) && StringUtils.hasText(tableName)) {
                        tables.add(StringUtils.hasText(schema) ? schema + "." + tableName : tableName);
                    }
                }
            }
            return new ArrayList<>(tables);
        } catch (Exception ex) {
            throw new IllegalArgumentException("读取表列表失败: " + ex.getMessage());
        }
    }

    // 方法：copyAndValidate
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

    // 方法：jdbcUrl
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

    // 方法：defaultPort
    private static int defaultPort(String type) {
        return switch (type) {
            case "pgsql", "postgres", "postgresql" -> 5432;
            case "mssql", "sqlserver" -> 1433;
            case "oracle" -> 1521;
            case "sqlite" -> 0;
            default -> 3306;
        };
    }

    // 方法：require
    private static String require(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    // 方法：clean
    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    // 方法：defaultText
    private static String defaultText(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    // 方法：isUserTableSchema
    private static boolean isUserTableSchema(String schema) {
        if (!StringUtils.hasText(schema)) {
            return true;
        }
        String value = schema.toLowerCase(Locale.ROOT);
        return !value.equals("information_schema")
                && !value.equals("pg_catalog")
                && !value.equals("mysql")
                && !value.equals("performance_schema")
                && !value.equals("sys");
    }
}
