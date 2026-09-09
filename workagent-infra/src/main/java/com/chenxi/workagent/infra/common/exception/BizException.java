package com.chenxi.workagent.infra.common.exception;

import com.chenxi.workagent.infra.common.enums.ErrorCode;

/**
 * 业务异常，统一由全局异常处理器转换为响应/SSE run.error 事件。
 * @author 辰夕
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
