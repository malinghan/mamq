package com.malinghan.mamq.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.malinghan.mamq.model.Message;
import com.malinghan.mamq.model.Result;

public class MAProducer {

    private final String baseUrl;

    public MAProducer(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int send(String topic, Message<?> message) {
        String url = baseUrl + "/mamq/send?t=" + topic;
        String body = JSON.toJSONString(message);
        String response = HttpUtil.post(url, body);  // 简单 HTTP 工具类
        Result<Integer> result = JSON.parseObject(response,
                new TypeReference<Result<Integer>>() {});
        return result.getData();
    }
}