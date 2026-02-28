package com.malinghan.mamq.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Subscription {
    private String topic;
    private String consumerId;
    private int offset;   // 下次 recv 从这个下标开始取
}