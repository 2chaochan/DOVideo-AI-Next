# Git 开发工作流

当前目录已初始化为本地仓库，主分支为 `main`。首次提交保存原始项目，标签 `baseline/original` 指向该版本；第二次提交单独记录工程目录整理。原项目更早的 Git 历史不在这份下载目录中。

## 开始一次学习或开发

在项目根目录执行，下面的 Git 命令同时适用于 PowerShell 和 Git Bash：

```sh
git status
git switch -c study/agent-loop
```

建议分支按目标命名：`study/` 用于阅读与练习，`feat/` 用于功能，`fix/` 用于修复，`chore/` 用于工程维护。一次分支聚焦一个学习目标。

修改后先查看差异、执行相关模块的检查，再按明确路径暂存：

```sh
git diff
git add docs/learning/README.md
git diff --cached
git commit -m "docs: record agent loop learning notes"
```

格式与验证命令见 [贡献规范](../../CONTRIBUTING.md)。在 Windows 的 PowerShell 中，后端使用 `server` 目录下的 `./mvnw.cmd verify`；README 中的 `.sh`、`source` 和 `set -a` 需要 Git Bash 或 WSL。仅运行 `docker compose up` 不会执行 `dev-up.sh` 中的 RocketMQ 卷权限准备及主题创建步骤。

## 比较与恢复

```sh
git log --oneline --decorate
git diff baseline/original HEAD --stat
git diff baseline/original HEAD -- server client extension
git show baseline/original:README.md
```

最后第二条可以检查应用目录相对原始基线的内容差异；本次只有 `server/mvnw` 的可执行标记变化。探索原始基线可新建分支，避免覆盖当前工作：

```sh
git switch -c study/original baseline/original
```

已提交的改动需要撤销时，先确认工作区干净，再使用 `git revert <commit>` 创建反向提交。不要把 `git reset --hard` 当作日常切换版本的操作。

## 连接自己的远程仓库

当前没有配置远程地址，也没有推送。准备好自己的空仓库后，将下面示例替换为真实地址再执行：

```sh
git remote add origin https://github.com/YOUR_ACCOUNT/YOUR_REPOSITORY.git
git push -u origin main
git push origin baseline/original
```

推送前用 `git remote -v` 确认目标。不要直接把 README 中原作者的仓库地址作为自己有写权限的远程仓库。项目原有 MIT 许可证与版权声明应保留。

真实 `.env`、密钥、cookies、依赖目录、构建产物及中间件数据不进入提交。`.env.example`、依赖锁文件、数据库迁移和 Maven Wrapper 继续纳入版本控制。
