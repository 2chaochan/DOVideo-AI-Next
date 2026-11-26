# 02 接口如何识别用户并保护视频

后端收到“查看编号 42 的视频”时，需要回答两个问题：请求来自谁？这个视频是否属于他？知道编号不代表有权访问。

## 一次受保护请求的路径

```text
携带 Authorization 的请求
  → AuthInterceptor 校验登录会话
  → 将 userId 放到请求属性
  → Controller 接收 userId
  → Service 检查媒体归属
  → 读取或修改数据
```

`AuthService` 登录成功后生成随机会话令牌。客户端在后续请求中携带 `Bearer` 令牌，后端通过 Redis 会话解析用户身份。这里采用的是服务端会话，不要把项目里的 token 自动理解为 JWT。

## 认证和授权分别是什么

认证回答“你是谁”；授权回答“你能做什么”。`AuthInterceptor` 处理登录身份，`MediaService.requireOwnedMedia` 检查视频所属用户，管理员接口还会调用 `requireAdmin`。

例如，A 登录后拿到自己的令牌，即使猜到 B 的 `mediaId`，服务也应拒绝读取 B 的视频。客户端提交的用户编号不能直接作为可信身份。

## Controller 和 Service 为什么分开

Controller 接收 HTTP 参数、取出认证身份并返回响应。Service 处理业务规则，例如检查归属、提交分析或删除媒体。Mapper 和仓储负责持久化操作。

这让同一套业务规则不必在每个接口里重新写一遍。前端是否隐藏按钮只影响界面，最终权限检查仍在后端完成。

## 动手阅读

依次阅读 [AuthInterceptor](../../../server/src/main/java/com/example/server/config/AuthInterceptor.java)、`WebConfig`、`AuthService.resolveUser`，再在 `MediaController` 中找到 `@RequestAttribute`。最后追到 `MediaService.requireOwnedMedia`。

当前拦截路径由 `WebConfig` 显式配置。以后增加接口时，需要同步检查是否应受保护；引入统一的默认拒绝策略是后续工程方向。

自测：登录成功为什么仍不能查看任意用户的视频？请分别指出认证检查和归属检查的位置。

下一篇：[视频上传与幂等](03-video-upload.md)。
