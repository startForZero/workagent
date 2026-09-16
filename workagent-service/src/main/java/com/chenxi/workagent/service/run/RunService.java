package com.chenxi.workagent.service.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chenxi.workagent.agent.factory.AgentFactory;
import com.chenxi.workagent.agent.sandbox.DockerProbe;
import com.chenxi.workagent.agent.sandbox.SandboxJanitor;
import com.chenxi.workagent.agent.sse.AgentEventSseMapper;
import com.chenxi.workagent.agent.sse.SseEnvelope;
import com.chenxi.workagent.agent.tool.AskUserTool;
import io.agentscope.core.event.ExceedMaxItersEvent;
import com.chenxi.workagent.infra.common.constant.CacheKeys;
import com.chenxi.workagent.infra.common.enums.ErrorCode;
import com.chenxi.workagent.infra.common.enums.RunStatus;
import com.chenxi.workagent.infra.common.enums.SseEventType;
import com.chenxi.workagent.infra.common.exception.BizException;
import com.chenxi.workagent.infra.config.WorkagentProperties;
import com.chenxi.workagent.infra.entity.MessageDO;
import com.chenxi.workagent.infra.entity.RunDO;
import com.chenxi.workagent.infra.entity.SessionDO;
import com.chenxi.workagent.infra.entity.SkillDO;
import com.chenxi.workagent.infra.mapper.MessageMapper;
import com.chenxi.workagent.infra.mapper.RunMapper;
import com.chenxi.workagent.infra.mapper.SessionMapper;
import com.chenxi.workagent.service.artifact.ArtifactService;
import com.chenxi.workagent.service.file.FileService;
import com.chenxi.workagent.service.model.ModelService;
import com.chenxi.workagent.service.run.dto.AnswerRequest;
import com.chenxi.workagent.service.run.dto.ConfirmRequest;
import com.chenxi.workagent.service.run.dto.RunRequest;
import com.chenxi.workagent.service.session.SessionService;
import com.chenxi.workagent.service.skill.SkillService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.ExternalExecutionResultEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * 运行编排：一次问答 = 一个 run。状态机迁移集中在此类校验。
 * 事件流：HarnessAgent.streamEvents → AgentEventSseMapper → SseEnvelope →（api 层）SSE。
 * M2：HITL 风险确认（挂起快照存 Redis，resume 续跑）、产物事件经 run 通道注入 SSE 流。
 * @author 辰夕
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunService {

    /** Redis 事件缓存上限（断线补发窗口） */
    private static final int EVENT_CACHE_MAX = 500;
    /** 事件缓存过期时间 */
    private static final Duration EVENT_CACHE_TTL = Duration.ofHours(1);
    /** run.start 事件序号固定为 0 */
    private static final long SEQ_RUN_START = 0L;
    /** 落库的工具负载截断长度 */
    private static final int TRANSCRIPT_PAYLOAD_MAX = 600;
    /** resume 时发给 agent 的确认消息文本（实际语义在 metadata 的确认结果里） */
    private static final String CONFIRM_MSG_TEXT = "用户已完成风险操作确认。";

    private final AgentFactory agentFactory;
    private final AgentEventSseMapper eventMapper;
    private final SessionService sessionService;
    private final FileService fileService;
    private final ModelService modelService;
    private final SkillService skillService;
    private final RunMapper runMapper;
    private final SessionMapper sessionMapper;
    private final MessageMapper messageMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DockerProbe dockerProbe;
    private final SandboxJanitor sandboxJanitor;
    private final WorkagentProperties properties;

    /** 活跃 run 的事件通道：ArtifactService 经它把 artifact.created 注入 SSE 流 */
    private final Map<String, RunChannel> runChannels = new ConcurrentHashMap<>();

    private record RunChannel(Sinks.Many<SseEnvelope> sink, AtomicLong seq) {
    }

    /**
     * 发起运行，返回 SSE 事件流（首帧为 run.start，携带 runId）。
     */
    public Flux<SseEnvelope> startRun(Long userId, RunRequest request) {
        if (properties.getSandbox().isEnabled() && !dockerProbe.available()) {
            throw new BizException(ErrorCode.SANDBOX_UNAVAILABLE);
        }
        // 顺手清掉 exited 态沙箱残留，避免同会话续跑撞名（docker run exit=125）
        sandboxJanitor.sweepExited();
        SessionDO session = sessionService.requireOwned(userId, request.sessionId());
        // 同一会话同一时刻只允许一个活跃 run（运行中或挂起等待确认/参数）
        Long activeCount = runMapper.selectCount(new LambdaQueryWrapper<RunDO>()
                .eq(RunDO::getSessionId, session.getId())
                .in(RunDO::getStatus, RunStatus.RUNNING.name(), RunStatus.WAITING_CONFIRM.name(),
                        RunStatus.WAITING_INPUT.name()));
        if (activeCount != null && activeCount > 0) {
            throw new BizException(ErrorCode.RUN_STATUS_ILLEGAL);
        }
        String effectiveModelKey = modelService.effectiveModelKey(userId, request.modelKey());
        List<String> stagedFiles = fileService.stageFilesForRun(userId, session.getSessionId(),
                request.fileIds());

        RunDO run = new RunDO();
        run.setRunId(UUID.randomUUID().toString().replace("-", ""));
        run.setSessionId(session.getId());
        run.setStatus(RunStatus.RUNNING.name());
        run.setModelKey(effectiveModelKey);
        run.setStartedAt(LocalDateTime.now());
        runMapper.insert(run);
        sessionService.touchOnRun(session.getId(), request.message());

        String runId = run.getRunId();
        // M3：@ 唤起技能——校验可见性后落 Redis 快照（续跑时经 buildRuntimeContext 重放 SkillFilter）
        List<String> runSkillKeys = skillService.resolveVisibleByKeys(userId, request.skillKeys())
                .stream().map(SkillDO::getSkillKey).toList();
        if (!runSkillKeys.isEmpty()) {
            saveSkillSnapshot(runId, runSkillKeys);
        }
        SseEnvelope startEnvelope = SseEnvelope.of(SseEventType.RUN_START, runId, SEQ_RUN_START,
                Map.of("runId", runId, "sessionId", session.getSessionId(), "modelKey", effectiveModelKey));
        cacheEvent(startEnvelope);
        saveUserMessage(session.getId(), runId, userId, request, runSkillKeys);

        Msg userMsg = buildUserMessage(request.message(), stagedFiles, runSkillKeys);
        RuntimeContext ctx = buildRuntimeContext(userId, session, runId);

        // Agent 实例获取可能触发模型解析/构建（阻塞），放到弹性线程池
        Flux<SseEnvelope> body = streamBody(run, session, userId, effectiveModelKey,
                List.of(userMsg), ctx, SEQ_RUN_START, false, null, null);
        return Flux.concat(Flux.just(startEnvelope), body);
    }

    /**
     * HITL 续跑：用户对挂起的风险工具调用给出允许/拒绝后，从挂起点继续执行。
     * 返回 SSE 事件流（事件追加到当前 assistant 消息，不再发 run.start）。
     * 快照存 Redis（TTL=hitl-expire-minutes），多实例下任意节点可恢复。
     */
    public Flux<SseEnvelope> resume(Long userId, String runId, ConfirmRequest request) {
        RunDO run = requireRun(runId);
        SessionDO session = sessionMapper.selectById(run.getSessionId());
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND);
        }
        if (!RunStatus.WAITING_CONFIRM.name().equals(run.getStatus())) {
            throw new BizException(ErrorCode.RUN_STATUS_ILLEGAL);
        }
        String hitlKey = CacheKeys.runHitl(runId);
        String snapshotJson = redisTemplate.opsForValue().get(hitlKey);
        List<ConfirmResult> results;
        if (snapshotJson != null) {
            results = buildConfirmResults(snapshotJson, request);
            redisTemplate.delete(hitlKey);
        } else {
            // 快照过期兜底：从落库转写的 confirm 步骤重建 ToolUseBlock（框架仅按 id 匹配）。
            // 关键作用：让过期确认仍能续跑收官，否则 agent 会话态里的 ASKING 残留会毒化后续所有 run。
            results = buildConfirmResultsFromTranscript(session.getId(), runId, request);
        }

        // 状态回到 RUNNING，续跑结束时归一化
        run.setStatus(RunStatus.RUNNING.name());
        run.setEndedAt(null);
        runMapper.updateById(run);

        Msg confirmMsg = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(CONFIRM_MSG_TEXT)
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                .build();
        RuntimeContext ctx = buildRuntimeContext(userId, session, runId);
        // seq 续接事件缓存长度，保证断线补发的序号单调
        long seqBase = cachedEventCount(runId);
        // 本次确认的整体结论（M2 语义：一次确认整批），合并落库时回填旧 confirm 步骤状态
        boolean allApproved = request.confirmations().stream().allMatch(ConfirmRequest.Confirmation::approved);
        String confirmResolution = allApproved ? CONFIRM_STATUS_APPROVED : CONFIRM_STATUS_REJECTED;
        return streamBody(run, session, userId, run.getModelKey(), List.of(confirmMsg), ctx, seqBase,
                true, confirmResolution, null);
    }

    /**
     * 参数补全续跑（M3 HITL 类型 A）：用户填写 ask_user 表单提交后，把值作为挂起工具的结果回填，
     * 从挂起点继续执行。返回 SSE 事件流（事件追加到当前 assistant 消息）。
     * 框架契约：pending 工具调用 + 携带匹配 id 的 ToolResultBlock 的 Msg（role=TOOL）→ 校验通过后续跑。
     */
    public Flux<SseEnvelope> answerParam(Long userId, String runId, AnswerRequest request) {
        RunDO run = requireRun(runId);
        SessionDO session = sessionMapper.selectById(run.getSessionId());
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND);
        }
        if (!RunStatus.WAITING_INPUT.name().equals(run.getStatus())) {
            throw new BizException(ErrorCode.RUN_STATUS_ILLEGAL);
        }
        String paramKey = CacheKeys.runParam(runId);
        String snapshotJson = redisTemplate.opsForValue().get(paramKey);
        if (snapshotJson != null) {
            validatePendingToolCall(snapshotJson, request.toolCallId());
            redisTemplate.delete(paramKey);
        } else {
            // 快照过期兜底：从落库转写的 param 步骤校验 toolCallId（防止会话态被挂起残留卡死）
            validatePendingToolCallFromTranscript(session.getId(), runId, request.toolCallId());
        }

        run.setStatus(RunStatus.RUNNING.name());
        run.setEndedAt(null);
        runMapper.updateById(run);

        String answersJson;
        try {
            answersJson = objectMapper.writeValueAsString(Map.of("answers", request.values()));
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "参数序列化失败");
        }
        ToolResultBlock answerBlock = ToolResultBlock.builder()
                .id(request.toolCallId())
                .name(AskUserTool.TOOL_NAME)
                .output(TextBlock.builder().text(answersJson).build())
                .state(ToolResultState.SUCCESS)
                .build();
        Msg answerMsg = Msg.builder().role(MsgRole.TOOL).content(answerBlock).build();
        RuntimeContext ctx = buildRuntimeContext(userId, session, runId);
        long seqBase = cachedEventCount(runId);
        return streamBody(run, session, userId, run.getModelKey(), List.of(answerMsg), ctx, seqBase,
                true, PARAM_STATUS_RESOLVED, request.values());
    }

    /**
     * 中断运行（前端「停止生成」）。
     */
    public void stopRun(Long userId, String runId) {
        RunDO run = requireRun(runId);
        if (!RunStatus.RUNNING.name().equals(run.getStatus())) {
            throw new BizException(ErrorCode.RUN_STATUS_ILLEGAL);
        }
        SessionDO session = sessionMapper.selectById(run.getSessionId());
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND);
        }
        HarnessAgent agent = agentFactory.get(userId, run.getModelKey());
        Msg interruptMsg = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent("用户主动停止了本次任务。")
                .build();
        agent.interrupt(String.valueOf(userId), session.getSessionId(), interruptMsg);
        finishRun(run.getId(), RunStatus.CANCELLED);
    }

    /**
     * 供 ArtifactService 调用：向活跃 run 的 SSE 流补发 artifact.created 事件。
     * run 不在当前节点流式输出时（理论上 deliver 只发生在活跃 run 内）仅告警。
     */
    public void publishArtifactEvent(String runId, Map<String, Object> payload) {
        RunChannel channel = runChannels.get(runId);
        if (channel == null) {
            log.warn("run 事件通道不存在，产物事件丢弃: runId={}", runId);
            return;
        }
        SseEnvelope envelope = SseEnvelope.of(SseEventType.ARTIFACT_CREATED, runId,
                channel.seq().incrementAndGet(), payload);
        cacheEvent(envelope);
        channel.sink().tryEmitNext(envelope);
    }

    // ---------- private ----------

    /** 步骤类别：思考 / 阶段结论（中间文本块）/ 工具调用 / HITL 风险确认 / 参数补全表单 */
    private static final String STEP_KIND_THINK = "think";
    private static final String STEP_KIND_TEXT = "text";
    private static final String STEP_KIND_TOOL = "tool";
    private static final String STEP_KIND_CONFIRM = "confirm";
    private static final String STEP_KIND_PARAM = "param";
    /** 工具步骤状态 */
    private static final String TOOL_STATUS_RUNNING = "running";
    private static final String TOOL_STATUS_DONE = "done";
    /** 确认步骤状态 */
    private static final String CONFIRM_STATUS_PENDING = "pending";
    private static final String CONFIRM_STATUS_APPROVED = "approved";
    private static final String CONFIRM_STATUS_REJECTED = "rejected";
    /** 参数表单步骤状态 */
    private static final String PARAM_STATUS_PENDING = "pending";
    private static final String PARAM_STATUS_RESOLVED = "resolved";

    /**
     * 一次 run 的过程步骤（思考/阶段结论/工具调用/风险确认，按发生顺序排列）。
     * concatMap 串行执行，无需并发保护。
     */
    private static final class Step {
        private final String kind;
        private final StringBuilder text = new StringBuilder();
        private String toolCallId;
        private String toolName;
        private final StringBuilder args = new StringBuilder();
        private final StringBuilder result = new StringBuilder();
        private String status;
        /** confirm 步骤：挂起的 replyId 与待确认工具列表 */
        private String replyId;
        private List<Map<String, Object>> confirmTools;
        /** param 步骤：提问内容、表单字段定义、用户提交的值 */
        private String question;
        private List<Map<String, Object>> paramFields;
        private Map<String, Object> paramValues;

        private Step(String kind) {
            this.kind = kind;
        }

        private static Step think() {
            return new Step(STEP_KIND_THINK);
        }

        private static Step text(String content) {
            Step step = new Step(STEP_KIND_TEXT);
            step.text.append(content);
            return step;
        }

        private static Step tool(String toolCallId, String toolName) {
            Step step = new Step(STEP_KIND_TOOL);
            step.toolCallId = toolCallId;
            step.toolName = toolName;
            step.status = TOOL_STATUS_RUNNING;
            return step;
        }

        private static Step confirm(String replyId, List<Map<String, Object>> tools) {
            Step step = new Step(STEP_KIND_CONFIRM);
            step.replyId = replyId;
            step.confirmTools = tools;
            step.status = CONFIRM_STATUS_PENDING;
            return step;
        }

        private static Step param(String toolCallId, String question, List<Map<String, Object>> fields) {
            Step step = new Step(STEP_KIND_PARAM);
            step.toolCallId = toolCallId;
            step.question = question;
            step.paramFields = fields;
            step.status = PARAM_STATUS_PENDING;
            return step;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("kind", kind);
            if (STEP_KIND_TOOL.equals(kind)) {
                map.put("name", toolName);
                map.put("args", args.toString());
                map.put("result", result.toString());
                map.put("status", status);
            } else if (STEP_KIND_CONFIRM.equals(kind)) {
                map.put("replyId", replyId);
                map.put("tools", confirmTools);
                map.put("status", status);
            } else if (STEP_KIND_PARAM.equals(kind)) {
                map.put("toolCallId", toolCallId);
                map.put("question", question);
                map.put("fields", paramFields);
                map.put("status", status);
                if (paramValues != null) {
                    map.put("values", paramValues);
                }
            } else {
                map.put("text", text.toString());
            }
            return map;
        }
    }

    /** 一次 run 的转写累积器：steps 为过程步骤流，text 为当前（最终）答案文本块 */
    private static final class Transcript {
        private final List<Step> steps = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private String error;

        private boolean isEmpty() {
            return steps.isEmpty() && text.isEmpty() && error == null;
        }

        /** 思考分片追加到最近的思考步骤；不存在则新建 */
        private void appendThink(String delta) {
            Step last = steps.isEmpty() ? null : steps.get(steps.size() - 1);
            if (last == null || !STEP_KIND_THINK.equals(last.kind)) {
                last = Step.think();
                steps.add(last);
            }
            last.text.append(delta);
        }

        /** 当前文本块冲入步骤流（工具调用前/新文本块开始前的中间结论） */
        private void flushText() {
            if (!text.isEmpty()) {
                steps.add(Step.text(text.toString()));
                text.setLength(0);
            }
        }

        /** 按 toolCallId 倒序查找工具步骤 */
        private Step findTool(String toolCallId) {
            for (int i = steps.size() - 1; i >= 0; i--) {
                Step step = steps.get(i);
                if (STEP_KIND_TOOL.equals(step.kind) && step.toolCallId.equals(toolCallId)) {
                    return step;
                }
            }
            return null;
        }

        /** 按 replyId 倒序查找确认步骤 */
        private Step findConfirm(String replyId) {
            for (int i = steps.size() - 1; i >= 0; i--) {
                Step step = steps.get(i);
                if (STEP_KIND_CONFIRM.equals(step.kind) && step.replyId.equals(replyId)) {
                    return step;
                }
            }
            return null;
        }

        /** 按 toolCallId 倒序查找参数表单步骤 */
        private Step findParam(String toolCallId) {
            for (int i = steps.size() - 1; i >= 0; i--) {
                Step step = steps.get(i);
                if (STEP_KIND_PARAM.equals(step.kind) && step.toolCallId.equals(toolCallId)) {
                    return step;
                }
            }
            return null;
        }
    }

    /**
     * run 事件流主体：agent 事件 + 通道注入事件（artifact.created）合并输出。
     * 挂起（WAITING_CONFIRM/WAITING_INPUT）时流自然结束但 run 不收官，等待 resume/answer。
     *
     * @param seqBase     事件序号起点（resume 续接已有缓存长度）
     * @param mergeOnSave 落库时是否合并进既有 assistant 消息（resume/answer=true，与实时渲染一致）
     * @param resolution  续跑落库时回填旧 pending 步骤的结论（approved/rejected/resolved），首轮传 null
     * @param paramValues answer 流程用户填写的值（回填到旧 param 步骤供回放），其余流程传 null
     */
    private Flux<SseEnvelope> streamBody(RunDO run, SessionDO session, Long userId, String modelKey,
                                         List<Msg> msgs, RuntimeContext ctx,
                                         long seqBase, boolean mergeOnSave, String resolution,
                                         Map<String, Object> paramValues) {
        String runId = run.getRunId();
        AtomicLong seq = new AtomicLong(seqBase);
        AtomicBoolean waitingConfirm = new AtomicBoolean(false);
        AtomicBoolean waitingParam = new AtomicBoolean(false);
        AtomicBoolean maxItersExceeded = new AtomicBoolean(false);
        Transcript transcript = new Transcript();
        Sinks.Many<SseEnvelope> sink = Sinks.many().multicast().onBackpressureBuffer();
        runChannels.put(runId, new RunChannel(sink, seq));

        Flux<SseEnvelope> agentEvents = Mono.fromCallable(() -> agentFactory.get(userId, modelKey))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(agent -> agent.streamEvents(msgs, ctx))
                .concatMap(event -> eventMapper.toSseEventType(event)
                        .map(type -> {
                            // EXCEED_MAX_ITERS 原始 payload 没有 message 字段，前端会落到默认「运行出错」。
                            // 这里转写为友好提示（含 maxIters/currentIter），便于用户判断是任务过深还是配置偏紧。
                            Object data = event;
                            if (event instanceof ExceedMaxItersEvent exceed) {
                                maxItersExceeded.set(true);
                                data = Map.of(
                                        "code", ErrorCode.RUN_MAX_ITERS_EXCEEDED.getCode(),
                                        "message", "已达最大推理步数 (" + exceed.getCurrentIter()
                                                + "/" + exceed.getMaxIters()
                                                + ")，请把任务拆细或重新发起",
                                        "maxIters", exceed.getMaxIters(),
                                        "currentIter", exceed.getCurrentIter());
                            }
                            SseEnvelope envelope = SseEnvelope.of(type, runId, seq.incrementAndGet(), data);
                            cacheEvent(envelope);
                            accumulate(transcript, event);
                            if (event instanceof RequireUserConfirmEvent confirmEvent) {
                                waitingConfirm.set(true);
                                onRequireConfirm(run, confirmEvent);
                            }
                            if (event instanceof RequireExternalExecutionEvent paramEvent) {
                                waitingParam.set(true);
                                onRequireParam(run, paramEvent);
                            }
                            return Mono.just(envelope);
                        })
                        .orElse(Mono.empty()))
                // agent 流终止（完成/出错/取消）时收口注入通道，merge 才能完结
                .doFinally(signal -> sink.tryEmitComplete());

        return Flux.merge(agentEvents, sink.asFlux())
                .doOnComplete(() -> {
                    RunStatus terminal = maxItersExceeded.get() ? RunStatus.ERROR : RunStatus.DONE;
                    finishRun(run.getId(), pendingStatus(waitingConfirm, waitingParam, terminal));
                    saveAssistantMessage(session.getId(), runId, transcript, mergeOnSave, resolution,
                            paramValues);
                })
                .doOnCancel(() -> {
                    // 前端收到 hitl.confirm/hitl.ask_param（终止事件之一）会主动断开原连接，可能比服务端
                    // 完结早一拍；这种「挂起等待用户操作」的断开不是用户取消，必须保持挂起态供 resume/answer
                    RunStatus terminal = maxItersExceeded.get() ? RunStatus.ERROR : RunStatus.CANCELLED;
                    finishRun(run.getId(), pendingStatus(waitingConfirm, waitingParam, terminal));
                    saveAssistantMessage(session.getId(), runId, transcript, mergeOnSave, resolution,
                            paramValues);
                })
                .onErrorResume(e -> {
                    log.error("运行异常: runId={}", runId, e);
                    finishRun(run.getId(), RunStatus.ERROR);
                    transcript.error = "运行出错：" + e.getMessage();
                    saveAssistantMessage(session.getId(), runId, transcript, mergeOnSave, resolution,
                            paramValues);
                    SseEnvelope errorEnvelope = SseEnvelope.of(SseEventType.RUN_ERROR, runId,
                            seq.incrementAndGet(),
                            Map.of("code", ErrorCode.INTERNAL_ERROR.getCode(), "message", transcript.error));
                    cacheEvent(errorEnvelope);
                    return Flux.just(errorEnvelope);
                })
                .doFinally(signal -> runChannels.remove(runId));
    }

    /** 终态判定：挂起等待确认 > 挂起等待参数 > 正常终态 */
    private static RunStatus pendingStatus(AtomicBoolean waitingConfirm, AtomicBoolean waitingParam,
                                           RunStatus otherwise) {
        if (waitingConfirm.get()) {
            return RunStatus.WAITING_CONFIRM;
        }
        if (waitingParam.get()) {
            return RunStatus.WAITING_INPUT;
        }
        return otherwise;
    }

    /** 参数补全挂起：快照（replyId + 原始 toolCalls）存 Redis，供 answer 校验 toolCallId */
    private void onRequireParam(RunDO run, RequireExternalExecutionEvent event) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("replyId", event.getReplyId());
            snapshot.put("toolCalls", event.getToolCalls());
            redisTemplate.opsForValue().set(CacheKeys.runParam(run.getRunId()),
                    objectMapper.writeValueAsString(snapshot),
                    Duration.ofMinutes(properties.getSandbox().getHitlExpireMinutes()));
        } catch (Exception e) {
            log.warn("参数补全快照缓存失败: runId={}", run.getRunId(), e);
        }
    }

    /** 校验提交的 toolCallId 确为本次挂起的工具调用（防伪造/串单） */
    private void validatePendingToolCall(String snapshotJson, String toolCallId) {
        try {
            for (JsonNode b : objectMapper.readTree(snapshotJson).path("toolCalls")) {
                if (toolCallId.equals(b.path("id").asText(""))) {
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("参数补全快照解析失败", e);
            throw new BizException(ErrorCode.PARAM_ANSWER_EXPIRED);
        }
        throw new BizException(ErrorCode.PARAM_INVALID, "未知的工具调用");
    }

    /** 快照过期兜底：从落库转写的 param 步骤校验 toolCallId */
    private void validatePendingToolCallFromTranscript(Long sessionPk, String runId, String toolCallId) {
        boolean found = false;
        try {
            MessageDO message = latestAssistantMessage(sessionPk, runId);
            if (message != null) {
                for (JsonNode s : objectMapper.readTree(message.getContent()).path("steps")) {
                    if (STEP_KIND_PARAM.equals(s.path("kind").asText())
                            && toolCallId.equals(s.path("toolCallId").asText(""))) {
                        found = true;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("参数补全转写兜底校验失败: runId={}", runId, e);
        }
        if (!found) {
            finishRun(requireRun(runId).getId(), RunStatus.ERROR);
            throw new BizException(ErrorCode.PARAM_ANSWER_EXPIRED);
        }
        log.info("参数补全快照已过期，从转写校验通过: runId={}", runId);
    }

    /** HITL 挂起：快照（replyId + 原始 toolCalls）存 Redis，供 resume 重建 ToolUseBlock */
    private void onRequireConfirm(RunDO run, RequireUserConfirmEvent event) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("replyId", event.getReplyId());
            snapshot.put("toolCalls", event.getToolCalls());
            redisTemplate.opsForValue().set(CacheKeys.runHitl(run.getRunId()),
                    objectMapper.writeValueAsString(snapshot),
                    Duration.ofMinutes(properties.getSandbox().getHitlExpireMinutes()));
        } catch (Exception e) {
            log.warn("HITL 快照缓存失败: runId={}", run.getRunId(), e);
        }
    }

    /**
     * 快照过期兜底：从本 run 最新 assistant 消息转写里的 confirm 步骤重建 ToolUseBlock。
     * 找不到可用的确认步骤时才判定过期（run 收官 ERROR，避免会话被 WAITING_CONFIRM 卡死）。
     */
    private List<ConfirmResult> buildConfirmResultsFromTranscript(Long sessionPk, String runId,
                                                                  ConfirmRequest request) {
        try {
            MessageDO message = latestAssistantMessage(sessionPk, runId);
            JsonNode confirmStep = null;
            if (message != null) {
                JsonNode steps = objectMapper.readTree(message.getContent()).path("steps");
                for (JsonNode s : steps) {
                    if (STEP_KIND_CONFIRM.equals(s.path("kind").asText())) {
                        confirmStep = s; // 取最后一个确认步骤（当前挂起的那次）
                    }
                }
            }
            if (confirmStep == null) {
                throw new BizException(ErrorCode.HITL_CONFIRM_EXPIRED);
            }
            Map<String, JsonNode> byId = new LinkedHashMap<>();
            for (JsonNode t : confirmStep.path("tools")) {
                byId.put(t.path("id").asText(""), t);
            }
            List<ConfirmResult> results = new ArrayList<>();
            for (ConfirmRequest.Confirmation confirmation : request.confirmations()) {
                JsonNode t = byId.get(confirmation.toolCallId());
                if (t == null) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "未知的工具调用");
                }
                Map<String, Object> input = objectMapper.readValue(t.path("args").asText("{}"),
                        new TypeReference<>() {
                        });
                results.add(new ConfirmResult(confirmation.approved(),
                        new ToolUseBlock(confirmation.toolCallId(), t.path("name").asText(""), input,
                                objectMapper.writeValueAsString(input), null), null));
            }
            log.info("HITL 快照已过期，从转写重建确认结果: runId={}", runId);
            return results;
        } catch (BizException e) {
            finishRun(requireRun(runId).getId(), RunStatus.ERROR);
            throw e;
        } catch (Exception e) {
            log.warn("HITL 转写兜底重建失败: runId={}", runId, e);
            finishRun(requireRun(runId).getId(), RunStatus.ERROR);
            throw new BizException(ErrorCode.HITL_CONFIRM_EXPIRED);
        }
    }

    /** 从 Redis 快照重建 ToolUseBlock，按前端确认结果组装 ConfirmResult（框架仅按 toolCallId 匹配校验） */
    private List<ConfirmResult> buildConfirmResults(String snapshotJson, ConfirmRequest request) {
        try {
            JsonNode node = objectMapper.readTree(snapshotJson);
            // ToolUseBlock 是多态类型（缺 type 属性无法整体反序列化），逐字段重建即可
            Map<String, JsonNode> byId = new LinkedHashMap<>();
            for (JsonNode b : node.path("toolCalls")) {
                byId.put(b.path("id").asText(""), b);
            }
            List<ConfirmResult> results = new ArrayList<>();
            for (ConfirmRequest.Confirmation confirmation : request.confirmations()) {
                JsonNode b = byId.get(confirmation.toolCallId());
                if (b == null) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "未知的工具调用");
                }
                Map<String, Object> input = objectMapper.convertValue(b.path("input"),
                        new TypeReference<>() {
                        });
                // 框架 ToolValidator 校验的是 content（原始 JSON 串）而非 input，必须回填
                String content = b.hasNonNull("content") ? b.get("content").asText()
                        : objectMapper.writeValueAsString(input);
                ToolUseBlock block = new ToolUseBlock(confirmation.toolCallId(),
                        b.path("name").asText(""), input, content, null);
                results.add(new ConfirmResult(confirmation.approved(), block, null));
            }
            return results;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("HITL 快照解析失败", e);
            throw new BizException(ErrorCode.HITL_CONFIRM_EXPIRED);
        }
    }

    private RuntimeContext buildRuntimeContext(Long userId, SessionDO session, String runId) {
        RuntimeContext.Builder builder = RuntimeContext.builder()
                .userId(String.valueOf(userId))
                .sessionId(session.getSessionId())
                .put(ArtifactService.CTX_KEY_RUN_ID, runId);
        // @ 唤起技能重放：快照存在即把本 run 的技能目录收窄为选中技能（续跑与首跑保持一致）
        List<String> skillKeys = readSkillSnapshot(runId);
        if (!skillKeys.isEmpty()) {
            builder.put(SkillFilter.class, SkillFilter.only(skillKeys.toArray(new String[0])));
        }
        return builder.build();
    }

    /** @ 技能快照：run 存续期内有效（与事件缓存同 TTL），供续跑重放 */
    private void saveSkillSnapshot(String runId, List<String> skillKeys) {
        try {
            redisTemplate.opsForValue().set(CacheKeys.runSkills(runId),
                    objectMapper.writeValueAsString(skillKeys), EVENT_CACHE_TTL);
        } catch (Exception e) {
            log.warn("@ 技能快照写入失败: runId={}", runId, e);
        }
    }

    private List<String> readSkillSnapshot(String runId) {
        try {
            String json = redisTemplate.opsForValue().get(CacheKeys.runSkills(runId));
            if (json == null) {
                return List.of();
            }
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private long cachedEventCount(String runId) {
        try {
            Long size = redisTemplate.opsForList().size(CacheKeys.runEvents(runId));
            return size != null ? size : SEQ_RUN_START;
        } catch (Exception e) {
            return SEQ_RUN_START;
        }
    }

    /**
     * 将流式事件累积进转写（提取规则与前端 handleEvent 一致，保证回放与实时渲染一致）。
     * 工具事件按 toolCallId 聚合为一张卡：START 建卡、DELTA 累积参数、END 美化参数、
     * RESULT 分片累积结果、RESULT_END 置完成；参数分片的 __fragment__ 占位名不产生卡片。
     */
    private void accumulate(Transcript transcript, AgentEvent event) {
        try {
            JsonNode node = objectMapper.valueToTree(event);
            switch (event.getType()) {
                case THINKING_BLOCK_START -> transcript.steps.add(Step.think());
                case THINKING_BLOCK_DELTA -> transcript.appendThink(node.path("delta").asText(""));
                case TEXT_BLOCK_START -> transcript.flushText();
                case TEXT_BLOCK_DELTA -> transcript.text.append(node.path("delta").asText(""));
                case TOOL_CALL_START -> {
                    transcript.flushText();
                    transcript.steps.add(Step.tool(node.path("toolCallId").asText(""),
                            node.path("toolCallName").asText("tool")));
                }
                case TOOL_CALL_DELTA -> {
                    Step step = transcript.findTool(node.path("toolCallId").asText(""));
                    if (step != null) {
                        appendClipped(step.args, node.path("delta").asText(""));
                    }
                }
                case TOOL_CALL_END -> {
                    Step step = transcript.findTool(node.path("toolCallId").asText(""));
                    if (step != null) {
                        prettyArgs(step);
                    }
                }
                case TOOL_RESULT_TEXT_DELTA, TOOL_RESULT_DATA_DELTA -> {
                    Step step = transcript.findTool(node.path("toolCallId").asText(""));
                    if (step == null) {
                        // resume 续跑不会重发 TOOL_CALL_START，被放行工具以结果事件先出现，补建卡片
                        transcript.flushText();
                        step = Step.tool(node.path("toolCallId").asText(""),
                                node.path("toolCallName").asText("tool"));
                        transcript.steps.add(step);
                    }
                    appendClipped(step.result, node.path("delta").asText(""));
                }
                case TOOL_RESULT_END -> {
                    Step step = transcript.findTool(node.path("toolCallId").asText(""));
                    if (step != null) {
                        step.status = TOOL_STATUS_DONE;
                    }
                }
                case REQUIRE_USER_CONFIRM -> {
                    transcript.flushText();
                    RequireUserConfirmEvent confirmEvent = (RequireUserConfirmEvent) event;
                    transcript.steps.add(Step.confirm(confirmEvent.getReplyId(),
                            confirmEvent.getToolCalls().stream().map(this::toolCallSummary).toList()));
                }
                case USER_CONFIRM_RESULT -> {
                    UserConfirmResultEvent resultEvent = (UserConfirmResultEvent) event;
                    Step step = transcript.findConfirm(resultEvent.getReplyId());
                    if (step != null) {
                        boolean allApproved = resultEvent.getConfirmResults().stream()
                                .allMatch(ConfirmResult::isConfirmed);
                        step.status = allApproved ? CONFIRM_STATUS_APPROVED : CONFIRM_STATUS_REJECTED;
                    }
                }
                case REQUIRE_EXTERNAL_EXECUTION -> {
                    // ask_user 挂起：建参数表单卡（question/fields 取自工具入参）
                    transcript.flushText();
                    JsonNode toolCall = node.path("toolCalls").isArray() && !node.path("toolCalls").isEmpty()
                            ? node.path("toolCalls").get(0) : objectMapper.createObjectNode();
                    JsonNode input = toolCall.path("input");
                    List<Map<String, Object>> fields = objectMapper.convertValue(
                            input.path("fields"), new TypeReference<>() {
                            });
                    transcript.steps.add(Step.param(toolCall.path("id").asText(""),
                            input.path("question").asText(""), fields));
                }
                case EXTERNAL_EXECUTION_RESULT -> {
                    // 用户已提交：本流内能找到表单卡（理论上 answer 总是新请求，卡在上一条消息里，
                    // 此处为同流兜底）则置为已提交；找不到则靠合并落库时回填旧步骤
                    ExternalExecutionResultEvent resultEvent = (ExternalExecutionResultEvent) event;
                    for (ToolResultBlock resultBlock : resultEvent.getToolResults()) {
                        Step step = transcript.findParam(resultBlock.getId());
                        if (step != null) {
                            step.status = PARAM_STATUS_RESOLVED;
                        }
                    }
                }
                case EXCEED_MAX_ITERS -> {
                    // 写一份友好提示到 transcript.error，刷新页面后回放仍能看见原因
                    ExceedMaxItersEvent exceed = (ExceedMaxItersEvent) event;
                    transcript.error = "已达最大推理步数 (" + exceed.getCurrentIter()
                            + "/" + exceed.getMaxIters() + ")，请把任务拆细或重新发起";
                }
                default -> { /* 其他事件不入转写 */ }
            }
        } catch (Exception e) {
            log.warn("转写累积失败: type={}", event.getType(), e);
        }
    }

    /** 待确认工具调用摘要（confirm 步骤的 tools 负载） */
    private Map<String, Object> toolCallSummary(ToolUseBlock block) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", block.getId());
        item.put("name", block.getName());
        String argsJson;
        try {
            argsJson = objectMapper.writeValueAsString(block.getInput());
        } catch (Exception e) {
            argsJson = "";
        }
        item.put("args", argsJson.length() > TRANSCRIPT_PAYLOAD_MAX
                ? argsJson.substring(0, TRANSCRIPT_PAYLOAD_MAX) + '…' : argsJson);
        return item;
    }

    /** 追加并截断到 TRANSCRIPT_PAYLOAD_MAX */
    private void appendClipped(StringBuilder sb, String delta) {
        if (sb.length() >= TRANSCRIPT_PAYLOAD_MAX) {
            return;
        }
        sb.append(delta);
        if (sb.length() > TRANSCRIPT_PAYLOAD_MAX) {
            sb.setLength(TRANSCRIPT_PAYLOAD_MAX);
            sb.append('…');
        }
    }

    /** 工具参数分片拼接完成后尝试格式化为 JSON（非 JSON 保留原文） */
    private void prettyArgs(Step step) {
        String raw = step.args.toString();
        if (raw.isBlank()) {
            return;
        }
        try {
            String pretty = objectMapper.readTree(raw).toPrettyString();
            step.args.setLength(0);
            step.args.append(pretty.length() > TRANSCRIPT_PAYLOAD_MAX
                    ? pretty.substring(0, TRANSCRIPT_PAYLOAD_MAX) + '…' : pretty);
        } catch (Exception e) {
            // 参数非完整 JSON，保留原始分片文本
        }
    }

    /** 用户消息落库：{text, files:[{name,size}], skillKeys?}（skillKeys 供历史回放还原 @ chip） */
    private void saveUserMessage(Long sessionPk, String runId, Long userId, RunRequest request,
                                 List<String> skillKeys) {
        try {
            List<Map<String, Object>> files = fileService.findOwnedFiles(userId, request.fileIds())
                    .stream()
                    .map(f -> {
                        Map<String, Object> item = new LinkedHashMap<String, Object>();
                        item.put("name", f.getFilename());
                        item.put("size", f.getSize());
                        return item;
                    })
                    .toList();
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("text", request.message());
            content.put("files", files);
            if (skillKeys != null && !skillKeys.isEmpty()) {
                content.put("skillKeys", skillKeys);
            }
            insertMessage(sessionPk, runId, MessageDO.ROLE_USER, content);
        } catch (Exception e) {
            // 消息落库失败不阻断运行
            log.warn("用户消息落库失败: runId={}", runId, e);
        }
    }

    /**
     * 助手消息落库：{steps:[过程步骤], text:最终答案, error?}；空转写（如启动即失败）不落。
     * merge=true（resume/answer）时合并进本 run 既有 assistant 消息，保证历史回放与实时渲染一致。
     * resolution 非空时回填旧 pending 步骤（确认卡 → approved/rejected，表单卡 → resolved+values，
     * running 工具 → done）——续跑流不会重发旧事件，旧步骤状态只能在此修正。
     */
    private void saveAssistantMessage(Long sessionPk, String runId, Transcript transcript, boolean merge,
                                      String resolution, Map<String, Object> paramValues) {
        try {
            if (transcript.isEmpty()) {
                return;
            }
            List<Map<String, Object>> newSteps = transcript.steps.stream().map(Step::toMap).toList();
            if (merge) {
                MessageDO existing = latestAssistantMessage(sessionPk, runId);
                if (existing != null) {
                    JsonNode old = objectMapper.readTree(existing.getContent());
                    List<Map<String, Object>> steps = new ArrayList<>(objectMapper.convertValue(
                            old.path("steps"), new TypeReference<>() {
                            }));
                    if (resolution != null) {
                        for (Map<String, Object> step : steps) {
                            if (STEP_KIND_CONFIRM.equals(step.get("kind"))
                                    && CONFIRM_STATUS_PENDING.equals(step.get("status"))) {
                                step.put("status", resolution);
                            }
                            if (STEP_KIND_PARAM.equals(step.get("kind"))
                                    && PARAM_STATUS_PENDING.equals(step.get("status"))) {
                                step.put("status", PARAM_STATUS_RESOLVED);
                                if (paramValues != null) {
                                    step.put("values", paramValues);
                                }
                            }
                            if (STEP_KIND_TOOL.equals(step.get("kind"))
                                    && TOOL_STATUS_RUNNING.equals(step.get("status"))) {
                                step.put("status", TOOL_STATUS_DONE);
                            }
                        }
                    }
                    steps.addAll(newSteps);
                    Map<String, Object> content = new LinkedHashMap<>();
                    content.put("steps", steps);
                    content.put("text", old.path("text").asText("") + transcript.text);
                    String error = transcript.error != null ? transcript.error
                            : (old.hasNonNull("error") ? old.get("error").asText() : null);
                    if (error != null) {
                        content.put("error", error);
                    }
                    existing.setContent(objectMapper.writeValueAsString(content));
                    messageMapper.updateById(existing);
                    return;
                }
            }
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("steps", newSteps);
            content.put("text", transcript.text.toString());
            if (transcript.error != null) {
                content.put("error", transcript.error);
            }
            insertMessage(sessionPk, runId, MessageDO.ROLE_ASSISTANT, content);
        } catch (Exception e) {
            log.warn("助手消息落库失败: runId={}", runId, e);
        }
    }

    private MessageDO latestAssistantMessage(Long sessionPk, String runId) {
        return messageMapper.selectOne(new LambdaQueryWrapper<MessageDO>()
                .eq(MessageDO::getSessionId, sessionPk)
                .eq(MessageDO::getRunId, runId)
                .eq(MessageDO::getRole, MessageDO.ROLE_ASSISTANT)
                .orderByDesc(MessageDO::getId)
                .last("LIMIT 1"));
    }

    private void insertMessage(Long sessionPk, String runId, String role, Map<String, Object> content)
            throws Exception {
        MessageDO message = new MessageDO();
        message.setSessionId(sessionPk);
        message.setRunId(runId);
        message.setRole(role);
        message.setContent(objectMapper.writeValueAsString(content));
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
    }

    private RunDO requireRun(String runId) {
        RunDO run = runMapper.selectOne(new LambdaQueryWrapper<RunDO>()
                .eq(RunDO::getRunId, runId));
        if (run == null) {
            throw new BizException(ErrorCode.RUN_NOT_FOUND);
        }
        return run;
    }

    private Msg buildUserMessage(String message, List<String> stagedFiles, List<String> skillKeys) {
        StringBuilder text = new StringBuilder(message);
        if (!stagedFiles.isEmpty()) {
            text.append("\n\n[附件] 以下文件已放入工作区，可直接读取：\n");
            stagedFiles.forEach(p -> text.append("- ").append(p).append('\n'));
        }
        if (skillKeys != null && !skillKeys.isEmpty()) {
            text.append("\n\n[技能] 用户通过 @ 指定使用以下技能：")
                    .append(String.join("、", skillKeys)).append("，请优先加载并按其指引执行。");
        }
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(text.toString())
                .build();
    }

    private void cacheEvent(SseEnvelope envelope) {
        try {
            String key = CacheKeys.runEvents(envelope.runId());
            redisTemplate.opsForList().rightPush(key, objectMapper.writeValueAsString(envelope));
            redisTemplate.opsForList().trim(key, -EVENT_CACHE_MAX, -1);
            redisTemplate.expire(key, EVENT_CACHE_TTL);
        } catch (Exception e) {
            // 事件缓存失败不阻断主流程
            log.warn("run 事件缓存失败: runId={}", envelope.runId(), e);
        }
    }

    /** 终态收口：仅从进行态（RUNNING/WAITING_CONFIRM/WAITING_INPUT）迁移，避免续跑竞态下被旧流覆盖 */
    private void finishRun(Long runPk, RunStatus status) {
        try {
            RunDO run = runMapper.selectById(runPk);
            if (run != null && (RunStatus.RUNNING.name().equals(run.getStatus())
                    || RunStatus.WAITING_CONFIRM.name().equals(run.getStatus())
                    || RunStatus.WAITING_INPUT.name().equals(run.getStatus()))) {
                run.setStatus(status.name());
                if (status != RunStatus.WAITING_CONFIRM && status != RunStatus.WAITING_INPUT) {
                    run.setEndedAt(LocalDateTime.now());
                }
                runMapper.updateById(run);
            }
        } catch (Exception e) {
            log.warn("更新 run 状态失败: runPk={}, status={}", runPk, status, e);
        }
    }
}
