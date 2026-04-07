## 2026-04-07 Maven 多模块 spring-boot:run 前必须先 install

- **现象**：`mvn spring-boot:run -pl platform-web` 启动报 `NoClassDefFoundError: com/aiworkplatform/service/deploy/DeployService`
- **原因**：`spring-boot:run` 只编译当前模块，不会自动编译和安装其依赖的兄弟模块到本地仓库
- **正确做法**：先执行 `mvn install -DskipTests` 安装所有模块，再 `mvn spring-boot:run -pl platform-web`
- **适用场景**：所有 Maven 多模块项目的本地启动
