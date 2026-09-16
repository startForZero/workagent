package com.chenxi.workagent.agent.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.ToolSuspendException;
import java.util.List;
import java.util.Map;

/**
 * 参数补全工具（M3 HITL 类型 A）：当任务缺少必需信息且无法合理推断时，模型调用本工具
 * 向用户收集参数。本工具永远不真正执行——抛出 {@link ToolSuspendException} 让框架挂起，
 * 前端弹出表单，用户填写提交后经 answer 端点把值作为工具结果回填续跑。
 * @author 辰夕
 */
public class AskUserTool {

    /** 工具名常量（answer 续跑回填 ToolResultBlock 时按名匹配） */
    public static final String TOOL_NAME = "ask_user";

    @Tool(name = TOOL_NAME, description = "当完成任务缺少必需信息、且无法从上下文或文件中合理推断时，"
            + "调用本工具向用户提问收集参数（例如：目标环境、时间范围、风格偏好、阈值等）。"
            + "调用后会暂停执行并等待用户填写，用户提交后你会收到所填内容作为本工具的结果。"
            + "注意：仅在信息确实必需时调用；能合理推断的就直接给出假设继续执行，不要滥用打断用户。")
    public String ask_user(
            @ToolParam(name = "question", description = "要向用户说明的问题/背景，一句话说清为什么需要这些信息")
                    String question,
            @ToolParam(name = "fields", description = "要收集的字段列表，每项形如 "
                    + "{\"key\":\"字段键名(英文)\",\"label\":\"字段标题(中文)\","
                    + "\"type\":\"text|textarea|number|select\",\"required\":true,"
                    + "\"placeholder\":\"输入提示\",\"options\":[\"选项A\",\"选项B\"](仅 select 需要)}")
                    List<Map<String, Object>> fields) {
        // 永不真正执行：挂起信号，框架转换为 pending ToolResultBlock 并发出 REQUIRE_EXTERNAL_EXECUTION 事件
        throw new ToolSuspendException(question);
    }
}
