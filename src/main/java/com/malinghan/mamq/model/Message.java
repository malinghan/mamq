package com.malinghan.mamq.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Message<T> {
    // 消息id
    private Long id;
    // 消息内容
    private T body;
    // 消息头
    private Map<String, String> headers;
    // 消息偏移量
    private Integer offset;
}