package com.malinghan.mamq.server;


import com.malinghan.mamq.model.Message;
import com.malinghan.mamq.model.Stat;
import com.malinghan.mamq.model.Subscription;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

//topic → MessageQueue[topic、messages、subscriptions]
public class MessageQueue {
    // 主题
    private final String topic;
    // 内存队列消息存储：下标即 offset
    private final List<Message> messages = new ArrayList<>();
    // 订阅关系：consumerId → Subscription[topic、consumerId、offset]
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    public MessageQueue(String topic) {
        this.topic = topic;
    }

    // 写入消息，返回 offset（即当前 size - 1）
    public synchronized int send(Message message) {
        messages.add(message);
        return messages.size() - 1;
    }

    // 订阅：初始化 offset=0 的书签
    public void subscribe(String consumerId) {
        subscriptions.putIfAbsent(consumerId,
                new Subscription(topic, consumerId, 0));
    }

    // 取消订阅
    public void unsubscribe(String consumerId) {
        subscriptions.remove(consumerId);
    }

    // 读取当前 offset 的消息，不推进 offset
    public Message recv(String consumerId) {
        Subscription sub = subscriptions.get(consumerId);
        if (sub == null || sub.getOffset() >= messages.size()) {
            return null;
        }
        return messages.get(sub.getOffset());
    }

    // 确认消费，offset 推进到 offset+1
    public int ack(String consumerId, int offset) {
        Subscription sub = subscriptions.get(consumerId);
        if (sub != null) {
            sub.setOffset(offset + 1);
        }
        return offset;
    }

    // 统计
    public Stat stat(String consumerId) {
        Subscription sub = subscriptions.get(consumerId);
        int position = sub == null ? 0 : sub.getOffset();
        return new Stat(messages.size(), position);
    }
}
