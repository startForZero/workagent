package com.chenxi.workagent.agent.sandbox;

import com.chenxi.workagent.infra.config.WorkagentProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 沙箱残留容器清扫。
 *
 * <p>框架按 {@code agentscope-sandbox-<sessionId>} 命名容器，sessionId 持久化在
 * AgentStateStore，会话续跑会复用同名容器。JVM 重启 / 流异常中断时容器可能残留，
 * 下一次同会话 run 会撞名（docker run exit=125 name already in use）。
 *
 * <p>两条清扫线：
 * <ul>
 *   <li>{@link #sweepOnStartup()}：启动时强清全部 agentscope-sandbox-* 容器——
 *       JVM 重启后旧 run 已死，留下的容器必为孤儿，可安全强删（含 running 态）</li>
 *   <li>{@link #sweepExited()}：run 开始前清理 exited 态残留（running 中的属于
 *       活跃 run，不动）</li>
 * </ul>
 * @author 辰夕
 */
@Slf4j
@Component
public class SandboxJanitor {

    /** 框架沙箱容器名前缀（与 DockerSandbox.createAndStartContainer 一致） */
    private static final String CONTAINER_NAME_PREFIX = "agentscope-sandbox-";
    private static final int DOCKER_CMD_TIMEOUT_SECONDS = 15;

    private final WorkagentProperties properties;
    private final DockerProbe dockerProbe;

    public SandboxJanitor(WorkagentProperties properties, DockerProbe dockerProbe) {
        this.properties = properties;
        this.dockerProbe = dockerProbe;
    }

    /** 应用就绪后全量清扫（含 running 残留：它们属于已死 JVM 的 run） */
    @EventListener(ApplicationReadyEvent.class)
    public void sweepOnStartup() {
        if (!properties.getSandbox().isEnabled() || !dockerProbe.available()) {
            return;
        }
        List<String> orphans = listSandboxContainers(false);
        if (orphans.isEmpty()) {
            return;
        }
        log.warn("发现 {} 个残留沙箱容器（上次进程遗留），强制清理: {}", orphans.size(), orphans);
        removeContainers(orphans, true);
    }

    /** run 开始前清理 exited 残留；running 中的属于活跃 run，不动 */
    public void sweepExited() {
        if (!properties.getSandbox().isEnabled() || !dockerProbe.available()) {
            return;
        }
        List<String> exited = listSandboxContainers(true);
        if (!exited.isEmpty()) {
            log.info("清理 {} 个已退出的沙箱容器: {}", exited.size(), exited);
            removeContainers(exited, false);
        }
    }

    /**
     * 列出 agentscope-sandbox-* 容器 id。
     *
     * @param onlyExited true=仅已退出；false=全部状态
     */
    private List<String> listSandboxContainers(boolean onlyExited) {
        List<String> cmd = new ArrayList<>(List.of("docker", "ps", "-a", "-q",
                "--filter", "name=" + CONTAINER_NAME_PREFIX));
        if (onlyExited) {
            cmd.add("--filter");
            cmd.add("status=exited");
        }
        String out = runDocker(cmd);
        if (out.isBlank()) {
            return List.of();
        }
        return out.lines().filter(line -> !line.isBlank()).toList();
    }

    private void removeContainers(List<String> ids, boolean force) {
        if (ids.isEmpty()) {
            return;
        }
        List<String> cmd = new ArrayList<>(List.of("docker", "rm"));
        if (force) {
            cmd.add("-f");
        }
        cmd.addAll(ids);
        String out = runDocker(cmd);
        if (!out.isBlank()) {
            log.debug("docker rm 输出: {}", out);
        }
    }

    /** 执行 docker CLI 并返回 stdout；失败仅记 warn（清扫是尽力而为，不阻断主流程） */
    private String runDocker(List<String> cmd) {
        Process process = null;
        try {
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            boolean exited = process.waitFor(DOCKER_CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!exited) {
                log.warn("docker 命令超时: {}", cmd);
                return "";
            }
            return new String(process.getInputStream().readAllBytes()).trim();
        } catch (Exception e) {
            log.warn("docker 命令失败: {}: {}", cmd, e.getMessage());
            return "";
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }
}
