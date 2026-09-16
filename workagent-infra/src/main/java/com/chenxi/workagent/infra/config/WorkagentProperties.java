package com.chenxi.workagent.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 辰夕 WorkAgent 全局可调参数（workagent.*），禁止在代码中写死。
 * @author 辰夕
 */
@ConfigurationProperties(prefix = "workagent")
public class WorkagentProperties {

    /** 工作区根目录（agent 工作区、上传文件落地）；默认取启动目录下 ./data，生产用环境变量指向 /data */
    private String workspaceRoot = "./data/workagent/workspace";

    /** 技能本地缓存根目录（M3 使用） */
    private String skillCacheRoot = "./data/workagent/skill-cache";

    private final Jwt jwt = new Jwt();
    private final Security security = new Security();
    private final Upload upload = new Upload();
    private final Minio minio = new Minio();
    private final Agent agent = new Agent();
    private final Admin admin = new Admin();
    private final Sandbox sandbox = new Sandbox();
    private final Skill skill = new Skill();

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public String getSkillCacheRoot() {
        return skillCacheRoot;
    }

    public void setSkillCacheRoot(String skillCacheRoot) {
        this.skillCacheRoot = skillCacheRoot;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public Security getSecurity() {
        return security;
    }

    public Upload getUpload() {
        return upload;
    }

    public Minio getMinio() {
        return minio;
    }

    public Agent getAgent() {
        return agent;
    }

    public Admin getAdmin() {
        return admin;
    }

    public Sandbox getSandbox() {
        return sandbox;
    }

    public Skill getSkill() {
        return skill;
    }

    /** 内置管理员种子账号：启动时按邮箱查不到才创建，凭据走环境变量 */
    public static class Admin {
        /** 管理员邮箱；为空则不创建种子账号 */
        private String email = "admin@chenxi.local";
        /** 初始密码（生产必须走环境变量覆盖，且首次登录后应改密） */
        private String password = "";
        /** 昵称 */
        private String nickname = "管理员";

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getNickname() {
            return nickname;
        }

        public void setNickname(String nickname) {
            this.nickname = nickname;
        }
    }

    /** JWT 签发配置 */
    public static class Jwt {
        /** HS256 密钥（至少 32 字节），生产走环境变量 */
        private String secret = "chenxi-workagent-dev-secret-change-me";
        /** 登录态有效天数 */
        private int expireDays = 7;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public int getExpireDays() {
            return expireDays;
        }

        public void setExpireDays(int expireDays) {
            this.expireDays = expireDays;
        }
    }

    /** 安全相关 */
    public static class Security {
        /** apiKey 等敏感字段 AES-GCM 加密密钥（32 字节），生产走环境变量 */
        private String aesKey = "chenxi-workagent-aes-key-0123456";

        public String getAesKey() {
            return aesKey;
        }

        public void setAesKey(String aesKey) {
            this.aesKey = aesKey;
        }
    }

    /** 对话附件上传 */
    public static class Upload {
        /** 单文件大小上限（MB） */
        private long maxSizeMb = 20;

        public long getMaxSizeMb() {
            return maxSizeMb;
        }

        public void setMaxSizeMb(long maxSizeMb) {
            this.maxSizeMb = maxSizeMb;
        }
    }

    /** MinIO 对象存储 */
    public static class Minio {
        private String endpoint = "http://localhost:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        /** 上传附件 bucket */
        private String bucketUploads = "workagent-uploads";
        /** 沙箱产物 bucket（M2 deliver_artifact 归档） */
        private String bucketArtifacts = "workagent-artifacts";
        /** 技能包 bucket（M3 技能市场 zip） */
        private String bucketSkills = "workagent-skills";
        /** 预签名 URL 过期分钟数 */
        private int presignExpireMinutes = 30;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getBucketUploads() {
            return bucketUploads;
        }

        public void setBucketUploads(String bucketUploads) {
            this.bucketUploads = bucketUploads;
        }

        public String getBucketArtifacts() {
            return bucketArtifacts;
        }

        public void setBucketArtifacts(String bucketArtifacts) {
            this.bucketArtifacts = bucketArtifacts;
        }

        public String getBucketSkills() {
            return bucketSkills;
        }

        public void setBucketSkills(String bucketSkills) {
            this.bucketSkills = bucketSkills;
        }

        public int getPresignExpireMinutes() {
            return presignExpireMinutes;
        }

        public void setPresignExpireMinutes(int presignExpireMinutes) {
            this.presignExpireMinutes = presignExpireMinutes;
        }
    }

    /** Agent 运行时 */
    public static class Agent {
        /** ReAct 最大迭代轮次 */
        private int maxIters = 30;
        /** AgentFactory 实例缓存上限（LRU） */
        private int instanceCacheSize = 200;
        /** 平台默认模型 modelKey（用户未选模型时兜底，dashscope:模型名 形式） */
        private String defaultModelKey = "dashscope:qwen-plus";
        /** 平台默认模型 apiKey（兜底用，生产走环境变量 DASHSCOPE_API_KEY） */
        private String defaultModelApiKey = "";

        public int getMaxIters() {
            return maxIters;
        }

        public void setMaxIters(int maxIters) {
            this.maxIters = maxIters;
        }

        public int getInstanceCacheSize() {
            return instanceCacheSize;
        }

        public void setInstanceCacheSize(int instanceCacheSize) {
            this.instanceCacheSize = instanceCacheSize;
        }

        public String getDefaultModelKey() {
            return defaultModelKey;
        }

        public void setDefaultModelKey(String defaultModelKey) {
            this.defaultModelKey = defaultModelKey;
        }

        public String getDefaultModelApiKey() {
            return defaultModelApiKey;
        }

        public void setDefaultModelApiKey(String defaultModelApiKey) {
            this.defaultModelApiKey = defaultModelApiKey;
        }
    }

    /** Docker 沙箱（M2，纯 Docker 方案，每会话一容器） */
    public static class Sandbox {
        /** 是否启用沙箱；关闭时退化为宿主机本地文件系统（M1 行为） */
        private boolean enabled = true;
        /** 沙箱镜像（需带 python3；框架默认 ubuntu 镜像无 Python） */
        private String image = "python:3.11-slim";
        /** CPU 限额（核） */
        private long cpuCount = 1L;
        /** 内存限额（字节），默认 512Mi */
        private long memorySizeBytes = 536870912L;
        /** 容器网络模式（none=断网，沙箱只跑纯计算，HTTP 走宿主 http_request 工具） */
        private String network = "none";
        /** HITL 挂起（确认/参数补全）快照超时（分钟），超时后 resume 视为过期 */
        private int hitlExpireMinutes = 10;
        /** Docker 可用性探测缓存（秒），避免每个 run 都执行 docker info */
        private int dockerProbeCacheSeconds = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = image;
        }

        public long getCpuCount() {
            return cpuCount;
        }

        public void setCpuCount(long cpuCount) {
            this.cpuCount = cpuCount;
        }

        public long getMemorySizeBytes() {
            return memorySizeBytes;
        }

        public void setMemorySizeBytes(long memorySizeBytes) {
            this.memorySizeBytes = memorySizeBytes;
        }

        public String getNetwork() {
            return network;
        }

        public void setNetwork(String network) {
            this.network = network;
        }







        public int getHitlExpireMinutes() {
            return hitlExpireMinutes;
        }

        public void setHitlExpireMinutes(int hitlExpireMinutes) {
            this.hitlExpireMinutes = hitlExpireMinutes;
        }

        public int getDockerProbeCacheSeconds() {
            return dockerProbeCacheSeconds;
        }

        public void setDockerProbeCacheSeconds(int dockerProbeCacheSeconds) {
            this.dockerProbeCacheSeconds = dockerProbeCacheSeconds;
        }
    }

    /** 技能市场（M3：MinIO 技能包 + 本地缓存） */
    public static class Skill {
        /** 技能 zip 包大小上限（MB） */
        private long maxSizeMb = 10;
        /** 单个技能包内文件数上限 */
        private int maxFiles = 100;
        /** 技能文件内容预览大小上限（KB），超出拒绝读取 */
        private long previewMaxSizeKb = 256;
        /** 技能仓库列表缓存 TTL（秒）：getAllSkills 每轮推理都会触发，避免频繁查库 */
        private int listCacheSeconds = 5;

        public long getMaxSizeMb() {
            return maxSizeMb;
        }

        public void setMaxSizeMb(long maxSizeMb) {
            this.maxSizeMb = maxSizeMb;
        }

        public int getMaxFiles() {
            return maxFiles;
        }

        public void setMaxFiles(int maxFiles) {
            this.maxFiles = maxFiles;
        }

        public long getPreviewMaxSizeKb() {
            return previewMaxSizeKb;
        }

        public void setPreviewMaxSizeKb(long previewMaxSizeKb) {
            this.previewMaxSizeKb = previewMaxSizeKb;
        }

        public int getListCacheSeconds() {
            return listCacheSeconds;
        }

        public void setListCacheSeconds(int listCacheSeconds) {
            this.listCacheSeconds = listCacheSeconds;
        }
    }
}
