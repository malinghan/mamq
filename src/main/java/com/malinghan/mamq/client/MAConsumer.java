package com.malinghan.mamq.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.malinghan.mamq.model.Message;
import com.malinghan.mamq.model.Result;
import com.malinghan.mamq.model.Stat;

public class MAConsumer<T> {

    private final String baseUrl;
    private final String topic;
    private final String consumerId;
    private final Class<T> bodyType;

    public MAConsumer(String baseUrl, String topic,
                      String consumerId, Class<T> bodyType) {
        this.baseUrl = baseUrl;
        this.topic = topic;
        this.consumerId = consumerId;
        this.bodyType = bodyType;
    }

    public void sub() {
        String url = baseUrl + "/mamq/sub?t=" + topic + "&cid=" + consumerId;
        HttpUtil.get(url);
    }

    // 拉取一条消息（不推进 offset）
    public Message<T> recv() {
        String url = baseUrl + "/mamq/recv?t=" + topic + "&cid=" + consumerId;
        String response = HttpUtil.get(url);
        Result<Message<T>> result = JSON.parseObject(response,
                new TypeReference<Result<Message<T>>>() {});
        return result.getData();
    }

    public int ack(int offset) {
        String url = baseUrl + "/mamq/ack?t=" + topic
                + "&cid=" + consumerId + "&offset=" + offset;
        String response = HttpUtil.get(url);
        Result<Integer> result = JSON.parseObject(response,
                new TypeReference<Result<Integer>>() {});
        return result.getData();
    }

    public Stat stat() {
        String url = baseUrl + "/mamq/stat?t=" + topic + "&cid=" + consumerId;
        String response = HttpUtil.get(url);
        Result<Stat> result = JSON.parseObject(response,
                new TypeReference<Result<Stat>>() {});
        return result.getData();
    }

    // 注册 push 监听，返回 this 供链式调用
    // 实际轮询由 MABroker 驱动，这里只是注册
    public MAConsumer<T> listen(MAListener<T> listener) {
        sub();  // 自动订阅
        // MABroker 会扫描已注册的 listener 并启动轮询线程
        this.listener = listener;
        return this;
    }

    // package-private，供 MABroker 访问
    MAListener<T> listener;
}
