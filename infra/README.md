# 基础设施配置

`rocketmq/broker.conf` 保存 Broker 配置，由根目录 `docker-compose.yml` 挂载。

在项目根目录运行现有启动脚本 `scripts/dev-up.sh`。环境要求、`.env` 配置和各服务端口见 [项目说明](../README.md)。脚本使用 Bash；Windows 请使用已配置所需工具的 Git Bash 或 WSL。

Compose 入口与数据卷位置保留原样。后续新增中间件静态配置可以放到此目录，并同步维护 Compose 中的挂载路径；真实凭据和运行数据不放入这里。
