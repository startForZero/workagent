package com.chenxi.workagent.service.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chenxi.workagent.agent.factory.AgentFactory;
import com.chenxi.workagent.agent.sse.AgentEventSseMapper;
import com.chenxi.workagent.agent.sse.SseEnvelope;
import com.chenxi.workagent.infra.common.constant.CacheKeys;
import com.chenxi.workagent.infra.common.enums.ErrorCode;
import com.chenxi.workagent.infra.common.enums.RunStatus;
import com.chenxi.workagent.infra.common.enums.SseEventType;
import com.chenxi.workagent.infra.common.exception.BizException;
import com.chenxi.workagent.infra.entity.FileDO;
import com.chenxi.workagent.infra.entity.MessageDO;
import com.chenxi.workagent.infra.entity.RunDO;
import com.chenxi.workagent.infra.entity.SessionDO;
import com.chenxi.workagent.infra.mapper.MessageMapper;
import com.chenxi.workagent.infra.mapper.RunMapper;
import com.chenxi.workagent.infra.mapper.SessionMapper;
import com.chenxi.workagent.service.file.FileService;
import com.chenxi.workagent.service.model.ModelService;
import com.chenxi.workagent.service.run.dto.RunRequest;
import com.chenxi.workagent.service.session.SessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 运行编排：一次问答 = 一个 run。状态机迁移集中在此类校验。
 * 事件流：HarnessAgent.streamEvents → AgentEventSseMapper → SseEnvelope →（api 层）SSE。
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

    private final AgentFactory agentFactory;
    private final AgentEventSseMapper eventMapper;
    private final SessionService sessionService;
    private final FileService fileService;
    private final ModelService modelService;
    private final RunMapper runMapper;
    private final SessionMapper sessionMapper;
    private final MessageMapper messageMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 发起运行，返回 SSE 事件流（首帧为 run.start，携带 runId）。
     */
    public Flux<SseEnvelope> startRun(Long userId, RunRequest request) {
        SessionDO session = sessionService.requireOwned(userId, request.sessionId());
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
        SseEnvelope startEnvelope = SseEnvelope.of(SseEventType.RUN_START, runId, SEQ_RUN_START,
                Map.of("runId", runId, "sessionId", session.getSessionId(), "modelKey", effectiveModelKey));
        cacheEvent(startEnvelope);
        saveUserMessage(session.getId(), runId, userId, request);

        Msg userMsg = buildUserMessage(request.message(), stagedFiles);
        RuntimeContext ctx = RuntimeContext.builder()
                .userId(String.valueOf(userId))
                .sessionId(session.getSessionId())
                .build();
        AtomicLong seq = new AtomicLong(SEQ_RUN_START);
        Transcript transcript = new Transcript();

        // Agent 实例获取可能触发模型解析/构建（阻塞），放到弹性线程池
        Flux<SseEnvelope> body = Mono.fromCallable(() -> agentFactory.get(userId, effectiveModelKey))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(agent -> agent.streamEvents(List.of(userMsg), ctx))
                .concatMap(event -> eventMapper.toSseEventType(event)
                        .map(type -> {
                            SseEnvelope envelope = SseEnvelope.of(type, runId, seq.incrementAndGet(), event);
                            cacheEvent(envelope);
                            accumulate(transcript, type, event);
                            return Mono.just(envelope);
                        })
                        .orElse(Mono.empty()))
                .doOnComplete(() -> {
                    finishRun(run.getId(), RunStatus.DONE);
                    saveAssistantMessage(session.getId(), runId, transcript);
                })
                .doOnCancel(() -> {
                    finishRun(run.getId(), RunStatus.CANCELLED);
                    saveAssistantMessage(session.getId(), runId, transcript);
                })
                .onErrorResume(e -> {
                    log.error("运行异常: runId={}", runId, e);
                    finishRun(run.getId(), RunStatus.ERROR);
                    transcript.error = "运行出错：" + e.getMessage();
                    saveAssistantMessage(session.getId(), runId, transcript);
                    SseEnvelope errorEnvelope = SseEnvelope.of(SseEventType.RUN_ERROR, runId,
                            seq.incrementAndGet(),
                            Map.of("code", ErrorCode.INTERNAL_ERROR.getCode(), "message", transcript.error));
                    cacheEvent(errorEnvelope);
                    return Flux.just(errorEnvelope);
                });

        return Flux.concat(Flux.just(startEnvelope), body);
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

    // ---------- private ----------

    /** 步骤类别：思考 / 阶段结论（中间文本块）/ 工具调用 */
    private static final String STEP_KIND_THINK = "think";
    private static final String STEP_KIND_TEXT = "text";
    private static final String STEP_KIND_TOOL = "tool";
    /** 工具步骤状态 */
    private static final String TOOL_STATUS_RUNNING = "running";
    private static final String TOOL_STATUS_DONE = "done";

    /**
     * 一次 run 的过程步骤（思考/阶段结论/工具调用，按发生顺序排列）。
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

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("kind", kind);
            if (STEP_KIND_TOOL.equals(kind)) {
                map.put("name", toolName);
                map.put("args", args.toString());
                map.put("result", result.toString());
                map.put("status", status);
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
    }

    /**
     * 将流式事件累积进转写（提取规则与前端 handleEvent 一致，保证回放与实时渲染一致）。
     * 工具事件按 toolCallId 聚合为一张卡：START 建卡、DELTA 累积参数、END 美化参数、
     * RESULT 分片累积结果、RESULT_END 置完成；参数分片的 __fragment__ 占位名不产生卡片。
     */
    private void accumulate(Transcript transcript, SseEventType type, AgentEvent event) {
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
                    if (step != null) {
                        appendClipped(step.result, node.path("delta").asText(""));
                    }
                }
                case TOOL_RESULT_END -> {
                    Step step = transcript.findTool(node.path("toolCallId").asText(""));
                    if (step != null) {
                        step.status = TOOL_STATUS_DONE;
                    }
                }
                default -> { /* 其他事件不入转写 */ }
            }
        } catch (Exception e) {
            log.warn("转写累积失败: type={}", type, e);
        }
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

    /** 用户消息落库：{text, files:[{name,size}]} */
    private void saveUserMessage(Long sessionPk, String runId, Long userId, RunRequest request) {
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
            insertMessage(sessionPk, runId, MessageDO.ROLE_USER, content);
        } catch (Exception e) {
            // 消息落库失败不阻断运行
            log.warn("用户消息落库失败: runId={}", runId, e);
        }
    }

    /** 助手消息落库：{steps:[过程步骤], text:最终答案, error?}；空转写（如启动即失败）不落 */
    private void saveAssistantMessage(Long sessionPk, String runId, Transcript transcript) {
        try {
            if (transcript.isEmpty()) {
                return;
            }
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("steps", transcript.steps.stream().map(Step::toMap).toList());
            content.put("text", transcript.text.toString());
            if (transcript.error != null) {
                content.put("error", transcript.error);
            }
            insertMessage(sessionPk, runId, MessageDO.ROLE_ASSISTANT, content);
        } catch (Exception e) {
            log.warn("助手消息落库失败: runId={}", runId, e);
        }
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

    private Msg buildUserMessage(String message, List<String> stagedFiles) {
        StringBuilder text = new StringBuilder(message);
        if (!stagedFiles.isEmpty()) {
            text.append("\n\n[附件] 以下文件已放入工作区，可直接读取：\n");
            stagedFiles.forEach(p -> text.append("- ").append(p).append('\n'));
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

    private void finishRun(Long runPk, RunStatus status) {
        try {
            RunDO run = runMapper.selectById(runPk);
            if (run != null && RunStatus.RUNNING.name().equals(run.getStatus())) {
                run.setStatus(status.name());
                run.setEndedAt(LocalDateTime.now());
                runMapper.updateById(run);
            }
        } catch (Exception e) {
            log.warn("更新 run 状态失败: runPk={}, status={}", runPk, status, e);
        }
    }
}
