package com.smartrecruit.common.security;

/**
 * 内部服务 JWT 验证失败异常。
 *
 * <p>由 {@link InternalTokenService} 在签名错误、aud 不匹配、过期、
 * scope 缺失等情况下抛出，携带机器可读的错误码供调用方区分 401 / 403。
 */
public class InternalTokenValidationException extends RuntimeException {

    private final String errorCode;

    public InternalTokenValidationException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public InternalTokenValidationException(String message, String errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
