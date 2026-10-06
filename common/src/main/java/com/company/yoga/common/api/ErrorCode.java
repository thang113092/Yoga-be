package com.company.yoga.common.api;

public interface ErrorCode {
    String getCode();
    int getHttpStatusCode();
    String getMessage();
    String getKey();
}
