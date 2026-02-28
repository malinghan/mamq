package com.malinghan.mamq.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Result<T> {
    private int code;    // 1=成功, 0=失败
    private T data;

    public static <T> Result<T> ok(T data) {
        return new Result<>(1, data);
    }
    public static <T> Result<T> error(String msg) {
        return new Result<>(0, (T) msg);
    }
}
