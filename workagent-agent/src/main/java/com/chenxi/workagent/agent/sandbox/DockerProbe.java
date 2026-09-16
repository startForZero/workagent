package com.chenxi.workagent.agent.sandbox;

import com.chenxi.workagent.infra.config.WorkagentProperties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Docker 守护进程可用性探测（带缓存，避免每个 run 都 fork 一次 docker info）。
 * 沙箱开启但 Docker 不可用时，RunService 据此给出明确错误提示。
 * @author 辰夕
 */
@Slf4j
@Component
public class DockerProbe {

    private static final int PROBE_TIMEOUT_SECONDS = 5;

    private final WorkagentProperties properties;
    private final AtomicLong lastProbeAt = new AtomicLong(0);
    private volatile boolean lastResult;

    public DockerProbe(WorkagentProperties properties) {
        this.properties = properties;
    }

    /**
     * Docker 是否可用（结果按 workagent.sandbox.docker-probe-cache-seconds 缓存）。
     */
    public synchronized boolean available() {
        long now = System.currentTimeMillis();
        long cacheMillis = properties.getSandbox().getDockerProbeCacheSeconds() * 1000L;
        if (now - lastProbeAt.get() < cacheMillis) {
            return lastResult;
        }
        lastResult = probe();
        lastProbeAt.set(now);
        if (!lastResult) {
            log.warn("Docker 守护进程不可用，沙箱运行将失败，请启动 Docker Desktop");
        }
        return lastResult;
    }

    private boolean probe() {
        Process process = null;
        try {
            process = new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean exited = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return exited && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }
}
