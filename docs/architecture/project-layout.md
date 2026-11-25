# 工程目录与边界

本项目采用单仓库、多应用布局。后端、网页和扩展各自保留构建及测试入口，公共文档、启动脚本与基础设施配置在根目录统一管理。

| 路径 | 职责 | 后续内容放置建议 |
| --- | --- | --- |
| `server/` | Spring Boot API、视频处理与 Agent 编排 | Java 业务代码、Maven 配置、数据库迁移及后端测试 |
| `client/` | Vue 工作台 | 页面、组件、请求与 SSE 状态处理、前端测试 |
| `extension/` | Chrome 侧栏扩展 | 扩展清单、侧栏、扩展工具模块及测试 |
| `infra/` | 中间件与部署辅助配置 | 当前为 `rocketmq/broker.conf` |
| `scripts/` | 项目级运维与开发脚本 | 当前 `dev-up.sh` 负责检查环境并准备中间件 |
| `docs/architecture/` | 架构及设计 | 模块边界、流程说明与设计决策 |
| `docs/development/` | 开发协作 | Git、环境和日常开发说明 |
| `docs/learning/` | 学习资料 | 阅读路线、练习记录及问答 |
| `docs/reviews/` | 历史审查 | 有日期的审查与验证记录 |
| `docs/images/` | 文档图片 | README 和设计说明使用的截图 |
| `.github/workflows/` | 持续集成 | 后端、网页、扩展的现有检查 |

## 根目录入口

`docker-compose.yml` 与 `.env.example` 保留在根目录，使现有 Compose 命令、环境变量加载和数据卷相对路径继续有效。Compose 中 Broker 配置的挂载源已改为 `./infra/rocketmq/broker.conf`。

MySQL、Redis、MinIO 和 Qdrant 启动后仍会在根目录生成各自的数据目录。这些运行数据已被 Git 忽略；RocketMQ 继续使用原有命名卷。此次没有迁移或清理运行数据。

## 受保护的应用目录

本次不移动、不改写 `server/`、`client/`、`extension/` 中的任何文件，包括源码、测试、资源、依赖清单和锁文件。保留模块路径可维持 CI 的工作目录、Prettier 的跨模块路径、Maven Wrapper 和扩展加载方式。

后端仍采用现有 `controller → service → repository / mapper` 的职责划分，具体规则见 [贡献规范](../../CONTRIBUTING.md)。测试保留在各模块现有位置，不额外建立空的根级 `tests/`。

## 本次 Git 基线

`baseline/original` 标记下载目录导入后的原始版本；后续独立提交记录目录整理。原项目 LICENSE 和上游 README 署名链接均保留。Unix 启动脚本 `server/mvnw` 和 `scripts/dev-up.sh` 设置了 Git 可执行标记，文件内容没有改变。
