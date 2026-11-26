# 从零学习 DoVideoAI

先建立“用户操作如何变成一个带证据的 Agent 任务”的整体认识，再逐步学习基础设施。每个阶段在独立 `study/` 分支记录观察与实验，避免一次改动整条链路。

## 专题笔记与近期阅读顺序

已完成的第一篇专题是 [分片上传的数据库幂等](03-upload-idempotency.md)，结合本仓库实际改造说明请求重试、分布式锁、事务、唯一约束和故障测试。

接下来两次学习可以按下面的顺序进行，按自己的进度安排：

1. 先读 [目录职责](../architecture/project-layout.md)、[Git 工作流](../development/git-workflow.md) 和本页前两个阶段，建立项目入口与模块认识；有环境时运行前端 Demo。
2. 再读专题笔记，沿 Controller、Service、Repository 阅读上传链路，运行上传相关测试，完成文末自测题。

学习时把原项目已有实现、本仓库后续修改和自己的推断分别记清楚。下面保留完整学习路线，后续专题在有实际代码与验证后逐步补充。

## 第一阶段：认识项目与前端演示

阅读 [项目介绍](../../README.md) 和 [目录说明](../architecture/project-layout.md)，认识 Java、Vue、HTTP、JSON、异步任务和环境变量的用途。

在根目录打开终端：

```sh
cd client
npm ci
npm run dev
```

访问 `http://localhost:5173/?demo`，观察目标输入、任务阶段、结果和证据。该演示使用内置数据，可以先于数据库和模型配置进行。

阅读 `client/src/demoData.js`、`App.vue`、`api.js`、`useAnalysisWorkspace.js`。验收目标：解释页面如何发起请求、保存状态和展示进度；区分演示数据与真实后端响应。

## 第二阶段：理解后端入口

从 `server/src/main/java/com/example/server/ServerApplication.java` 开始，依次阅读 `controller/HealthController`、`UserController`、`AnalysisController` 和相应应用服务。结合 `resources/application.properties` 理解外部配置。

阅读 `ArchitectureTest` 与 [贡献规范](../../CONTRIBUTING.md)，理解 Controller、Service、Mapper 的职责。验收目标：能够从一个 HTTP 接口追踪到服务与数据库操作。

## 第三阶段：追踪异步视频任务

重点阅读 `AnalysisDispatchService`、`consumer/VideoAnalysisConsumer`、`VideoContextService`、`VideoTranscriptionService`、`TaskEventService` 和相关测试。

画出“提交任务 → MQ → 视频解析 → 阶段事件”的调用图，区分 MySQL、Redis、MinIO 与 RocketMQ 的用途。验收目标：说明为什么请求提交后可以立即返回，以及失败后如何定位任务状态。

## 第四阶段：理解 Agent 闭环

重点阅读 `service/AgentLoopService`、`service/mode/ModeRouter`、`utils/DeepSeekUtils`、`EvidenceVerificationService`、`AgentExecutionBudget` 与 `dto/AgentState`。

沿代码查找 Planner、Executor、Critic 的输入输出，确认轮次、超时和 Token 预算的终止条件。验收目标：解释一次普通模型调用与这个项目的 Agent 编排分别承担什么职责，并找到证据不足时的处理分支。

## 第五阶段：检索、检查点与评估

阅读 `LongVideoContextService`、`VideoEvidenceRetrievalService`、`QdrantVectorStore`、`AgentCheckpointService` 和 `repository/AgentCheckpointRepository`。

结合 `resources/evaluation/golden-video-tasks.json`、`AgentEvaluationService` 和 `OfflineAgentEvaluationRunner` 了解评估入口。验收目标：解释检索降级、状态恢复与离线评估如何帮助验证 Agent 行为。

## 第六阶段：启动真实链路并做第一个改动

按根 README 准备 JDK 21、Node.js 22、Docker、媒体工具、本地 `.env` 和模型服务配置。Windows 下先区分 PowerShell 与 Git Bash 的命令语法，详见 [Git 工作流](../development/git-workflow.md)。

先用短视频验证完整链路，再选择一个小目标，例如改善任务状态文案、补充一个失败场景的说明，或在理解现有断言后新增有意义的边界测试。运行涉及模块的现有检查，保存输入、预期与实际结果，再提交。

可使用以下提问模板记录学习：

- 这个类由谁调用，输入输出是什么？
- 状态存在哪里，失败后由谁恢复？
- 哪个条件决定重试、降级或终止？
- 哪个现有测试可以验证我的理解？

原有 [六支柱问答](interview-qa-six-pillars.md) 与 [进阶问答](interview-qa-v2.md) 可作为补充资料；其中的描述需要结合当前源码核对。
