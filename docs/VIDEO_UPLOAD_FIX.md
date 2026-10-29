# 华灯 3.1.1 大视频上传修复

基线：`v3.1.1-huadeng`，提交 `5613446ac9e64b4faf52335c82780513613e286e`。
修复分支：`fix/video-upload-v3.1.1`。

## 行为

- 请求中的本地视频合计不超过 20 MiB：沿用 inline Base64。
- 合计超过 20 MiB：视频通过 OkHttp 文件流上传，请求中只发送文件 URI。
- Gemini Developer API：使用当前提供商的 Files API，等待 `ACTIVE`，支持 generateContent 和 Interactions。
- 文件引用按提供商、API 地址、所选 API key 和文件内容 SHA-256 隔离缓存。重启后可复用；复用前查询服务端状态，文件删除或接近失效时重新上传。
- 同一请求的上传与生成固定使用同一把 API key。自定义中转站必须同时实现 Files API；上传失败会报告错误，不会回退为大块 Base64。
- Vertex：只在使用服务账号、填写 GCS 桶名后上传。API key / Express 模式缺少 GCS 上传身份时会明确提示配置。
- 上传准备只修改请求的临时副本，聊天记录中的本地视频地址保持不变。
- 上传、轮询支持取消，文件上传不在内存中组装完整二进制数据。移除 GoogleProvider 和 InteractionsAPI 的完整请求体日志。

## Vertex 配置

在提供商设置中启用 Vertex AI 和服务账号，导入自己的服务账号 JSON，再填写“**大视频 GCS 存储桶**”。
该桶必须已存在，使用 `my-bucket` 或 `gs://my-bucket`，不填写对象路径。

建议使用与 Vertex 项目关联的专用私有桶，给予服务账号该桶的对象创建、读取、删除权限，并确保 Vertex 有权读取该桶。
应用不会创建桶、启用 Billing 或修改 IAM。

GCS 的存储、操作和网络传输可能收费。临时对象写入 `rikkahub-video/` 前缀，在生成完成、错误或取消后尝试删除；如果应用被杀、断网或权限不足，清理可能失败。请为此专用前缀配置 1 天自动删除的生命周期规则作为兜底。

## 构建和安装

GitHub Actions 的 `Video upload fix APK` 工作流运行 AI 模块 JVM 测试后构建 arm64 debug APK，产物在工作流的 Artifacts 中。

这是独立测试包，应用 ID 为 `me.rerere.rikkahub.huadeng.debug`，可与官方版并存。
原作者签名密钥不在仓库中，因此不能覆盖官方安装。可先在官方版导出备份，再导入测试版；保留官方版直到确认数据和功能正常。

`-Prikkahub.forkBuild=true` 仅用于允许独立签名的 fork 构建，不会恢复与原作者证书绑定的加密插件能力。

## 验证边界

回归测试使用临时稀疏视频文件和模拟 HTTP 响应，验证 72 MiB 文件流上传、20 MiB 分界、合计分流、引用缓存、过期/删除、API key 隔离、上传失败、处理完成轮询/失败、取消清理、Vertex 配置和 GCS 清理，以及 Interactions URI 格式。

这些测试不调用付费 API，也不代表在目标手机上完成了视频理解端到端实测。安装后需使用自己的提供商和原 72 MB 视频验证。

参考：[Gemini Files API](https://ai.google.dev/api/files)、[视频理解](https://ai.google.dev/gemini-api/docs/video-understanding)、[Cloud Storage 对象上传](https://docs.cloud.google.com/storage/docs/json_api/v1/objects/insert)。
