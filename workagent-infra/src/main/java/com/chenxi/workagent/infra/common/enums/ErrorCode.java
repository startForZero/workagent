package com.chenxi.workagent.infra.common.enums;

/**
 * 统一错误码（code 供前端程序化判断，message 面向用户）。
 * @author 辰夕
 */
public enum ErrorCode {

    // 通用 1xxx
    PARAM_INVALID(1001, "参数不合法"),
    UNAUTHORIZED(1002, "未登录或登录已过期"),
    FORBIDDEN(1003, "无权限执行该操作"),
    NOT_FOUND(1004, "资源不存在"),
    INTERNAL_ERROR(1500, "服务内部错误"),

    // 账号 2xxx
    EMAIL_ALREADY_EXISTS(2001, "该邮箱已注册"),
    EMAIL_OR_PASSWORD_WRONG(2002, "邮箱或密码错误"),
    PASSWORD_WRONG(2003, "当前密码不正确"),

    // 模型 3xxx
    MODEL_NOT_FOUND(3001, "模型配置不存在"),
    MODEL_TEST_FAILED(3002, "模型连通性测试失败，请检查 baseUrl 与 apiKey"),
    MODEL_DISABLED(3003, "该模型已停用"),

    // 会话/运行 4xxx
    SESSION_NOT_FOUND(4001, "会话不存在"),
    RUN_NOT_FOUND(4002, "运行不存在"),
    RUN_STATUS_ILLEGAL(4003, "当前运行状态不允许该操作"),

    // 文件 5xxx
    FILE_TOO_LARGE(5001, "文件超出大小限制"),
    FILE_TYPE_NOT_ALLOWED(5002, "不支持的文件类型"),
    FILE_NOT_FOUND(5003, "文件不存在"),
    FILE_UPLOAD_FAILED(5004, "文件上传失败");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
