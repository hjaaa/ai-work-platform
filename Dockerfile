# 运行时镜像（JAR 由本地 Maven 预构建）
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY platform-web/target/*.jar app.jar

# 创建日志目录和非 root 用户
RUN apk add --no-cache git && \
    mkdir -p /app/logs && \
    addgroup -S appgroup && adduser -S appuser -G appgroup && \
    chown -R appuser:appgroup /app/logs
USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
