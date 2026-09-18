# 升级说明

本文档按里程碑/批次记录「辰夕 WorkAgent」的功能变更与重要修复。格式：新增能力 / 行为变更 / 修复 / 升级注意事项。

---

## M4（2026-09 记忆中心）

### 新增能力

- **长期记忆跨会话共享**：接入框架文件式记忆（`memory_save / memory_search / memory_get / session_search` 工具 + flush/consolidation 中间件）。经 `filesystemRoute` 把 `MEMORY.md` / `memory/` 每日流水 / `agents/xiaozi/sessions/` 会话转录路由到用户级宿主目录 `<memory.root>/<userId>/`——沙箱 SESSION 隔离下默认记忆按会话割裂，路由后跨会话共享
- **记忆中心页**：左侧导航「🧠 记忆中心」。用户记忆 tab 展示全部长期记忆条目（内容 + 来源会话标题 + 更新时间），支持行内编辑、单条删除、一键清空；会话记忆 tab 列出全部会话，点击跳 `/chat?session=` 自动选中续聊
- **MEMORY.md 为真相源，表为 UI 镜像**：新增 `wa_user_memory` 表仅存镜像 + 来源会话元数据，三段式同步——事件回填（`RunService` 拦截 `memory_save` 成功事件拆 bullet 入库并回填来源会话，fullArgs 不截断）、read-repair（GET 列表时与 MEMORY.md 对账，`uk_user_hash` 幂等）、写穿（编辑/删除/清空先改文件成功再改表，撞唯一键回滚文件）
- **清空语义**：一键清空 = MEMORY.md 清空 + `memory/` 每日流水全删 + 表清空；**会话转录 `sessions/` 保留**（属聊天历史，由会话删除功能单独管理）。删除会话与清空记忆正交，互不影响
- **聊天体验**：`memory_save` 工具卡摘要取 content 首个 bullet；`memory_search/session_search` 显示 query；`memory_get` 显示 path；新增 `session_search` 中文名「搜索会话记忆」
- **确认弹窗统一**：新增 `utils/confirm.ts` 原型风格居中弹窗（遮罩 + 卡片 + 动画，Esc/点遮罩取消、Enter 确认、危险操作红色按钮），替换全部原生 `window.confirm`——删除会话 / 删除记忆 / 清空记忆 / 退出登录 / 删除模型 / 删除技能

### 修复 / 关键坑

- **`memory_search` 整串字面匹配**：框架检索为 `Pattern.quote(query)` 字面匹配，多词组合（如「输出偏好 技术栈」）永不命中。提示词已约束 query 只填单个关键词、每轮最多检索 1 次、未命中用 `memory_get` 读 MEMORY.md 兜底
- **记忆路由不能用 NamespaceFactory 做用户隔离**：路由命中后 backendPath 以 `/` 开头，`LocalFilesystem.applyNamespacePrefix` 跳过绝对路径导致 namespace 静默丢失（全用户共享根）。解法：userId 直接拼进各路由的 rootDir，`virtualMode=true` 把绝对 backendPath 重锚定到该目录
- 记忆文件并发写：框架 pathLocks 为实例级，业务侧 `MemoryFileStore` 按 userId 串行化锁 + tmp 原子重写兜底

### 升级注意事项

- MySQL 执行新增表（`schema-m1.sql` 尾部）：`wa_user_memory`（`uk_user_hash(user_id, content_hash)` 幂等键）
- 新增配置段 `workagent.memory.*`（root / flush 节流 30min / 归纳间隔 / 保留天数 / 单条上限 1000），默认 root `./data/workagent/memory`，可用 `MEMORY_ROOT` 环境变量覆盖
- 记忆 flush/consolidation 默认用主模型（用户 BYOK key），首次对话后约 30 分钟触发一轮 LLM 抽取，有少量 token 开销
- 老数据无迁移：升级后首次打开记忆中心为空，随新对话逐步沉淀

---

## 2026-09-16 体验与稳定性修复批次

### 修复

- **`http_request` 工具 HTTPS 全兼容**：企业内网自签 / 私有 CA / IP 直连的 CN-only 证书（无 SAN 扩展）此前必报 `SSLHandshakeException: No subject alternative names present`。底层由 `java.net.http.HttpClient` 切换为 `HttpURLConnection`，按连接配置 trust-all `SSLSocketFactory` + 放行型 `HostnameVerifier`，作用域仅限本工具（JDK HttpClient 无法按实例关闭主机名校验，JVM 级开关会波及模型 SDK，不可用）。附带的兼容处理：PATCH 经 `X-HTTP-Method-Override` 惯例转发；301/302/303/307/308 手动跟随（最多 5 跳，307/308 保留方法与 body）
- **沙箱容器撞名（docker run exit=125）**：框架按 `agentscope-sandbox-<sessionId>` 命名容器，sessionId 持久化在 AgentStateStore；JVM 重启/流中断后旧容器残留，同会话下一次 run 撞名。新增 `SandboxJanitor`：应用启动时强清全部 `agentscope-sandbox-*` 孤儿容器（含 running 态），每个 run 开始前清理 exited 态残留
- **max-iters 触顶显示裸「运行出错」**：框架 `ExceedMaxItersEvent` 原始 payload 无 message 字段，前端落到默认文案。现在 SSE 层转写为「已达最大推理步数 (current/max)，请把任务拆细或重新发起」（新增错误码 `4008 RUN_MAX_ITERS_EXCEEDED`），并同步写入落库消息、run 终态标 `ERROR`

### 行为变更

- **推理区折叠策略**：思考步骤、工具步骤默认折叠为一行（「🧠 正在深度思考…」/「⏳ 工具名： 参数摘要」），点击行头展开看内容；阶段结论与最终答复照常完整展示。过程容器在 run 进行中保持展开、终态自动收口
- **HTML 报告内联预览**：模型输出完整 HTML 文档（```html 围栏或裸 `<!DOCTYPE html>`）时，自动拆为预览卡——`<iframe srcdoc sandbox="allow-scripts">` 沙箱渲染（报告里的 echarts 等脚本能跑，但 opaque origin 读不到本站存储），可切换查看源码。短 HTML 片段仍按代码块展示
- **技能命名不再做字符集限制**：中文 / 含点 / 含空格均可，仅保留 64 字符长度上限
- **技能 zip 支持多套一层文件夹**：macOS「压缩」产出的 `wrapper/SKILL.md` 结构自动归一上提，自动清理 `__MACOSX` 元数据目录

### 新增能力

- **会话删除**：侧栏会话项 hover 出现 ✕，删除时级联清理消息 / 运行 / 附件 / 产物行与 MinIO 对象（尽力而为）；有正在运行的任务时拒绝删除（`4007 SESSION_HAS_ACTIVE_RUN`）
- **输入 @ 唤起技能选择器**：在输入框直接键入 `@` 即弹出技能菜单（WorkBuddy 风格），支持关键字过滤，选中后自动去除 @ 前缀文本

---

## M3（2026-09 技能市场）

### 新增能力

- **技能市场**：公共区（仅管理员维护）+ 我的技能（用户上传）双分区；zip 导入 / 导出（MinIO 预签名 URL）/ 删除 / 文件树预览；同 scope 重名即覆盖（产品语义 = 更新）
- **MinIO 技能仓库**：技能包存 MinIO（`public/` 与 `users/{uid}/` 分区，无版本、每技能仅当前一份），运行时经 `WorkagentSkillRepository`（实现框架 `RuntimeContextSkillRepository` 钩子）按 `ctx.getUserId()` 返回「公共 + 本人」可见技能
- **ETag 两级缓存**：可见列表 5s TTL + AgentSkill 按 (scope/owner/key+etag) 内存缓存，命中零 IO；本地物化目录以 `.etag` 标记判失效
- **@ 唤起收窄**：选中技能以 chip 展示随 run 提交，`RuntimeContext.put(SkillFilter.class, SkillFilter.only(keys))` 收窄本轮 `<available_skills>` 目录；skillKeys 存 Redis run 快照，resume/answer 续跑重放保持一致；历史消息 content JSON 带 skillKeys 还原 chip
- **HITL 参数补全表单**：自研 `ask_user` 工具抛 `ToolSuspendException` → 框架转 pending 并发 `RequireExternalExecutionEvent` → SSE `hitl.ask_param`；前端渲染表单卡（text/textarea/number/select + 必填校验），提交走 `/api/runs/{runId}/answer` 续跑（Msg role=TOOL 回填 ToolResultBlock）

### 修复 / 关键坑

- **workspace-root 必须绝对路径**：框架 `MarketplaceStager` 用 `target.startsWith(stagedDir)` 防目录逃逸，相对路径 normalize 后永不匹配导致技能资源被静默跳过；`AgentFactory` 已 `toAbsolutePath().normalize()`
- resume 续跑不重发 TOOL_CALL_START，被放行工具以 `TOOL_RESULT_*` 先出现，前后端转写均补建工具卡

---

## M2（2026-09 沙箱与产物）

### 新增能力

- **Docker 沙箱**：会话级隔离（每会话一容器，`python:3.11-slim`），CPU/内存限额，默认断网（`network=none`，外网调用经宿主 `http_request` 工具）；`workagent.sandbox.enabled=false` 可整体关闭退回本地执行
- **产物面板**：`deliver_artifact` 工具把 workspace 文件归档 MinIO，SSE `artifact.created` 实时推送，右侧面板列出产物、预签名 URL 下载
- **HITL 策略定型**：「工具全放行 + 仅参数补全弹窗」（`PermissionMode.BYPASS`），风险确认链路保留 SSE 契约但默认不触发

### 升级注意事项

- 需先 `docker pull python:3.11-slim` 并保持 Docker daemon 运行
- MySQL 初始化执行 `workagent-server/src/main/resources/db/schema-m1.sql`（新增 `wa_artifact` / `wa_skill` 等表）
