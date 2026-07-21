package com.aiwork.baas.data.exec;

import com.aiwork.baas.data.bind.ValueCodec;
import com.aiwork.baas.data.bind.WriteBodyParser;
import com.aiwork.baas.data.config.DataPlaneProperties;
import com.aiwork.baas.data.context.DataRequestContext;
import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.data.meta.AclChecker;
import com.aiwork.baas.data.meta.DataMetadataService;
import com.aiwork.baas.data.meta.DataOperation;
import com.aiwork.baas.data.meta.TableMeta;
import com.aiwork.baas.data.query.ParsedQuery;
import com.aiwork.baas.data.rest.PreferHeader;
import com.aiwork.baas.data.sql.BoundSql;
import com.aiwork.baas.data.sql.SelectPlan;
import com.aiwork.baas.data.sql.SqlBuilder;
import com.aiwork.baas.datasource.ProjectDataSourceRegistry;
import com.aiwork.baas.entity.BaasColumn;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * 数据面执行层(spec §7.5):项目库单连接手动事务 + MDL 执行屏障 + 服务端游标真流式 +
 * 有界缓冲 + 并发响应信号量 + representation 三步算法。
 *
 * @author ai-work
 * @date 2026/07/21
 */
@Slf4j
@Component
public class DataPlaneExecutor {

    /** MySQL 严格模式数据不符的 vendor code → 400(§11)。 */
    private static final Set<Integer> DATA_ERROR_CODES = Set.of(1048, 1264, 1265, 1292, 1366, 1406);

    private final ProjectDataSourceRegistry registry;

    private final DataMetadataService metadataService;

    private final AclChecker aclChecker;

    private final SqlBuilder sqlBuilder;

    private final ValueCodec codec;

    private final WriteBodyParser bodyParser;

    private final DataPlaneProperties properties;

    private final Semaphore responsePermits;

    private final ObjectMapper objectMapper;

    /**
     * 创建数据面执行器。
     * @param registry 项目连接池注册表
     * @param metadataService 数据面元数据服务
     * @param aclChecker ACL 校验器
     * @param sqlBuilder SQL 构建器
     * @param codec JDBC 值编解码器
     * @param bodyParser 写请求体解析器
     * @param properties 数据面配置
     * @param responsePermits 行响应并发许可
     * @param objectMapper 数据面专用 ObjectMapper
     */
    public DataPlaneExecutor(ProjectDataSourceRegistry registry, DataMetadataService metadataService,
            AclChecker aclChecker, SqlBuilder sqlBuilder, ValueCodec codec, WriteBodyParser bodyParser,
            DataPlaneProperties properties, @Qualifier("dataResponsePermits") Semaphore responsePermits,
            @Qualifier("dataPlaneObjectMapper") ObjectMapper objectMapper) {
        this.registry = registry;
        this.metadataService = metadataService;
        this.aclChecker = aclChecker;
        this.sqlBuilder = sqlBuilder;
        this.codec = codec;
        this.bodyParser = bodyParser;
        this.properties = properties;
        this.responsePermits = responsePermits;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询数据并在事务提交后输出有界 JSON 数组。
     * @param ctx 已鉴权请求上下文
     * @param tableName 表名
     * @param query 已解析查询
     * @param prefer Prefer 请求头
     * @param response HTTP 响应
     */
    public void executeGet(DataRequestContext ctx, String tableName, ParsedQuery query, PreferHeader prefer,
            HttpServletResponse response) {
        TableMeta fastMeta = metadataService.loadActive(ctx.project().getId(), tableName);
        aclChecker.check(fastMeta, ctx.role(), DataOperation.SELECT, false);
        sqlBuilder.buildSelect(fastMeta, query, ctx);
        withPermit(() -> {
            RowsResult result = inTransaction(ctx, fastMeta, (connection, meta) -> {
                aclChecker.check(meta, ctx.role(), DataOperation.SELECT, false);
                SelectPlan plan = sqlBuilder.buildSelect(meta, query, ctx);
                BoundedJsonBuffer buffer = newBuffer();
                int rows = streamRows(connection, plan, buffer);
                Long total = prefer.countExact() ? queryCount(connection, sqlBuilder.buildCount(meta, query, ctx))
                        : null;
                return new RowsResult(buffer, rows, total);
            });
            if (result.total() != null) {
                response.setHeader("Content-Range", contentRange(query.offset(), result.rows(), result.total()));
            }
            writeBuffer(result.buffer(), response, 200);
        });
    }

    /**
     * 原子插入一行或多行数据，并按 Prefer 返回主键或完整行。
     * @param ctx 已鉴权请求上下文
     * @param tableName 表名
     * @param body 写请求体
     * @param prefer Prefer 请求头
     * @param response HTTP 响应
     */
    public void executePost(DataRequestContext ctx, String tableName, JsonNode body, PreferHeader prefer,
            HttpServletResponse response) {
        TableMeta fastMeta = metadataService.loadActive(ctx.project().getId(), tableName);
        // representation 的 select 权限检查前置于任何写入(spec §7.1/§8.2)
        aclChecker.check(fastMeta, ctx.role(), DataOperation.INSERT, prefer.returnRepresentation());
        bodyParser.parseInsert(fastMeta, body, ctx, properties.getBatchMaxRows());
        Runnable work = () -> {
            BoundedJsonBuffer buffer = inTransaction(ctx, fastMeta, (connection, meta) -> {
                aclChecker.check(meta, ctx.role(), DataOperation.INSERT, prefer.returnRepresentation());
                WriteBodyParser.ParsedWrite write = bodyParser.parseInsert(meta, body, ctx,
                        properties.getBatchMaxRows());
                BoundSql insert = sqlBuilder.buildInsert(meta, write);
                List<Long> ids = executeInsert(connection, insert);
                if (prefer.returnRepresentation()) {
                    BoundedJsonBuffer representation = newBuffer();
                    streamRows(connection,
                            new SelectPlan(sqlBuilder.buildSelectByIds(meta, meta.columns(), ids), meta.columns()),
                            representation);
                    return representation;
                }
                return idArrayBuffer(ids);
            });
            writeBuffer(buffer, response, 201);
        };
        if (prefer.returnRepresentation()) {
            withPermit(work);
        } else {
            work.run();
        }
    }

    /**
     * 按过滤条件更新数据，并按 Prefer 返回计数或更新后的完整行。
     * @param ctx 已鉴权请求上下文
     * @param tableName 表名
     * @param query 已解析查询
     * @param body 写请求体
     * @param prefer Prefer 请求头
     * @param response HTTP 响应
     */
    public void executePatch(DataRequestContext ctx, String tableName, ParsedQuery query, JsonNode body,
            PreferHeader prefer, HttpServletResponse response) {
        TableMeta fastMeta = metadataService.loadActive(ctx.project().getId(), tableName);
        aclChecker.check(fastMeta, ctx.role(), DataOperation.UPDATE, prefer.returnRepresentation());
        WriteBodyParser.ParsedWrite fastWrite = bodyParser.parsePatch(fastMeta, body, ctx);
        sqlBuilder.buildUpdate(fastMeta, query, fastWrite, ctx);
        if (!prefer.returnRepresentation()) {
            BoundedJsonBuffer buffer = inTransaction(ctx, fastMeta, (connection, meta) -> {
                aclChecker.check(meta, ctx.role(), DataOperation.UPDATE, false);
                WriteBodyParser.ParsedWrite write = bodyParser.parsePatch(meta, body, ctx);
                int count = executeUpdate(connection, sqlBuilder.buildUpdate(meta, query, write, ctx));
                return countBuffer(count);
            });
            writeBuffer(buffer, response, 200);
            return;
        }

        withPermit(() -> {
            BoundedJsonBuffer buffer = inTransaction(ctx, fastMeta, (connection, meta) -> {
                aclChecker.check(meta, ctx.role(), DataOperation.UPDATE, true);
                WriteBodyParser.ParsedWrite write = bodyParser.parsePatch(meta, body, ctx);
                int probeLimit = properties.getRepresentationMaxRows() + 1;
                List<Long> ids = queryIds(connection, sqlBuilder.buildCaptureIds(meta, query, ctx, probeLimit));
                if (ids.size() > properties.getRepresentationMaxRows()) {
                    throw DataApiException.badRequest(
                            "representation 匹配行数超过 " + properties.getRepresentationMaxRows() + " 上限",
                            "请缩小过滤范围或去掉 return=representation");
                }
                BoundedJsonBuffer representation = newBuffer();
                if (ids.isEmpty()) {
                    JsonGenerator generator = representation.generator();
                    generator.writeStartArray();
                    generator.writeEndArray();
                    return representation;
                }
                executeUpdate(connection, sqlBuilder.buildUpdateByIds(meta, write, ids));
                streamRows(connection,
                        new SelectPlan(sqlBuilder.buildSelectByIds(meta, meta.columns(), ids), meta.columns()),
                        representation);
                return representation;
            });
            writeBuffer(buffer, response, 200);
        });
    }

    /**
     * 按过滤条件删除数据并返回受影响行数。
     * @param ctx 已鉴权请求上下文
     * @param tableName 表名
     * @param query 已解析查询
     * @param response HTTP 响应
     */
    public void executeDelete(DataRequestContext ctx, String tableName, ParsedQuery query,
            HttpServletResponse response) {
        TableMeta fastMeta = metadataService.loadActive(ctx.project().getId(), tableName);
        aclChecker.check(fastMeta, ctx.role(), DataOperation.DELETE, false);
        sqlBuilder.buildDelete(fastMeta, query, ctx);
        BoundedJsonBuffer buffer = inTransaction(ctx, fastMeta, (connection, meta) -> {
            aclChecker.check(meta, ctx.role(), DataOperation.DELETE, false);
            int count = executeUpdate(connection, sqlBuilder.buildDelete(meta, query, ctx));
            return countBuffer(count);
        });
        writeBuffer(buffer, response, 200);
    }

    @FunctionalInterface
    interface TxWork<T> {

        /**
         * 在已取得 MDL 且完成元数据复查的事务连接上执行工作。
         * @param connection 当前项目库事务连接
         * @param meta 事务内重读的表元数据
         * @return 工作结果
         * @throws SQLException JDBC 执行失败
         * @throws IOException JSON 缓冲写入失败
         */
        T run(Connection connection, TableMeta meta) throws SQLException, IOException;

    }

    private record RowsResult(BoundedJsonBuffer buffer, int rows, Long total) {
    }

    private <T> T inTransaction(DataRequestContext ctx, TableMeta fastMeta, TxWork<T> work) {
        return registry.execute(ctx.project(), dataSource -> {
            try (Connection connection = dataSource.getConnection()) {
                boolean previousAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    TableMeta meta = barrier(connection, ctx, fastMeta);
                    T result = work.run(connection, meta);
                    connection.commit();
                    return result;
                }
                catch (Throwable throwable) {
                    rollback(connection, throwable);
                    throw asDataException(throwable);
                }
                finally {
                    try {
                        connection.setAutoCommit(previousAutoCommit);
                    }
                    catch (SQLException ignored) {
                        // 连接归还前恢复失败仅影响本连接,池会校验
                    }
                }
            }
            catch (SQLException exception) {
                throw translate(exception);
            }
        });
    }

    private static void rollback(Connection connection, Throwable primaryFailure) {
        try {
            connection.rollback();
        }
        catch (SQLException rollbackFailure) {
            primaryFailure.addSuppressed(rollbackFailure);
        }
    }

    /**
     * 执行屏障(spec §7.5):事务内先取目标表共享 MDL,再重读平台元数据;
     * 预锁失败(并发重命名/删除)重新解析一次后按 §9.5 响应。
     */
    private TableMeta barrier(Connection connection, DataRequestContext ctx, TableMeta fastMeta) throws SQLException {
        String physicalName = fastMeta.table().getTableName();
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(properties.getQueryTimeoutSeconds());
            statement.execute("SELECT 1 FROM `" + physicalName + "` WHERE FALSE");
        }
        catch (SQLTimeoutException exception) {
            throw exception;
        }
        catch (SQLException exception) {
            metadataService.loadActive(ctx.project().getId(), physicalName);
            throw DataApiException.forbidden("表暂不可用", "表结构变更进行中,请稍后重试");
        }
        return metadataService.loadActive(ctx.project().getId(), physicalName);
    }

    /**
     * 创建 MySQL 服务端游标读取所需的只进、只读 PreparedStatement。
     * @param connection JDBC 连接
     * @param sql 参数化 SQL
     * @param queryTimeoutSeconds 查询超时秒数
     * @param fetchSize 游标批量读取行数
     * @return 已配置的 PreparedStatement
     * @throws SQLException 创建或配置语句失败
     */
    public static PreparedStatement streamingStatement(Connection connection, String sql, int queryTimeoutSeconds,
            int fetchSize) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY);
        statement.setQueryTimeout(queryTimeoutSeconds);
        statement.setFetchSize(fetchSize);
        return statement;
    }

    private int streamRows(Connection connection, SelectPlan plan, BoundedJsonBuffer buffer)
            throws SQLException, IOException {
        try (PreparedStatement statement = streamingStatement(connection, plan.boundSql().sql(),
                properties.getQueryTimeoutSeconds(), properties.getFetchSize())) {
            bindParams(statement, plan.boundSql().params());
            try (ResultSet resultSet = statement.executeQuery()) {
                JsonGenerator generator = buffer.generator();
                generator.writeStartArray();
                int rows = 0;
                List<BaasColumn> columns = plan.outputColumns();
                while (resultSet.next()) {
                    generator.writeStartObject();
                    for (int i = 0; i < columns.size(); i++) {
                        generator.writeFieldName(columns.get(i).getColumnName());
                        codec.writeColumn(generator, columns.get(i), resultSet, i + 1);
                    }
                    generator.writeEndObject();
                    rows++;
                    buffer.checkLimit();
                }
                generator.writeEndArray();
                buffer.checkLimit();
                return rows;
            }
        }
    }

    private List<Long> executeInsert(Connection connection, BoundSql insert) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(insert.sql(),
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setQueryTimeout(properties.getQueryTimeoutSeconds());
            bindParams(statement, insert.params());
            statement.executeUpdate();
            List<Long> ids = new ArrayList<>();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                while (keys.next()) {
                    ids.add(keys.getLong(1));
                }
            }
            return ids;
        }
    }

    private int executeUpdate(Connection connection, BoundSql boundSql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(boundSql.sql())) {
            statement.setQueryTimeout(properties.getQueryTimeoutSeconds());
            bindParams(statement, boundSql.params());
            return statement.executeUpdate();
        }
    }

    private List<Long> queryIds(Connection connection, BoundSql boundSql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(boundSql.sql())) {
            statement.setQueryTimeout(properties.getQueryTimeoutSeconds());
            bindParams(statement, boundSql.params());
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (resultSet.next()) {
                    ids.add(resultSet.getLong(1));
                }
                return ids;
            }
        }
    }

    private long queryCount(Connection connection, BoundSql boundSql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(boundSql.sql())) {
            statement.setQueryTimeout(properties.getQueryTimeoutSeconds());
            bindParams(statement, boundSql.params());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static void bindParams(PreparedStatement statement, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            statement.setObject(i + 1, params.get(i));
        }
    }

    private BoundedJsonBuffer newBuffer() throws IOException {
        return new BoundedJsonBuffer(objectMapper.getFactory(), properties.getResponseMaxBytes());
    }

    private BoundedJsonBuffer idArrayBuffer(List<Long> ids) throws IOException {
        BoundedJsonBuffer buffer = newBuffer();
        JsonGenerator generator = buffer.generator();
        generator.writeStartArray();
        for (Long id : ids) {
            generator.writeStartObject();
            generator.writeNumberField("id", id);
            generator.writeEndObject();
        }
        generator.writeEndArray();
        return buffer;
    }

    private BoundedJsonBuffer countBuffer(int count) throws IOException {
        BoundedJsonBuffer buffer = newBuffer();
        JsonGenerator generator = buffer.generator();
        generator.writeStartObject();
        generator.writeNumberField("count", count);
        generator.writeEndObject();
        return buffer;
    }

    private static void writeBuffer(BoundedJsonBuffer buffer, HttpServletResponse response, int status) {
        try {
            buffer.writeTo(response, status);
        }
        catch (IOException exception) {
            throw DataApiException.internal("响应输出失败");
        }
    }

    private static String contentRange(int offset, int rows, long total) {
        if (rows == 0) {
            return "*/" + total;
        }
        return offset + "-" + (offset + rows - 1) + "/" + total;
    }

    private void withPermit(Runnable action) {
        if (!responsePermits.tryAcquire()) {
            throw DataApiException.tooManyRequests("并发响应构建繁忙,请稍后重试");
        }
        try {
            action.run();
        }
        finally {
            responsePermits.release();
        }
    }

    private static RuntimeException asDataException(Throwable throwable) {
        if (throwable instanceof DataApiException dataApiException) {
            return dataApiException;
        }
        if (throwable instanceof SQLException sqlException) {
            return translate(sqlException);
        }
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        log.error("data plane unexpected error type={}", throwable.getClass().getName());
        return DataApiException.internal("数据操作失败");
    }

    private static DataApiException translate(SQLException exception) {
        if (exception instanceof SQLTimeoutException) {
            return DataApiException.internal("SQL 执行超时");
        }
        if (DATA_ERROR_CODES.contains(exception.getErrorCode())) {
            return DataApiException.badRequest("数据不符合列约束");
        }
        if (exception instanceof SQLIntegrityConstraintViolationException) {
            return DataApiException.conflict("唯一键冲突或约束违反");
        }
        // 脱敏:只落 sqlState/vendorCode,不落 message(可能含 SQL 原文与参数值,§11)
        log.error("data plane sql error, sqlState={}, vendorCode={}", exception.getSQLState(),
                exception.getErrorCode());
        return DataApiException.internal("数据操作失败");
    }

}
