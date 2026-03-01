package com.malinghan.mamq.server;


import com.malinghan.mamq.model.Message;
import com.malinghan.mamq.model.Stat;
import com.malinghan.mamq.model.Subscription;
import com.malinghan.mamq.store.Store;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

//topic → MessageQueue[topic、messages、subscriptions]
public class MessageQueue {
    private final String topic;
    private final Store store;                                          // ← 替换 ArrayList
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    public MessageQueue(String topic) {
        this.topic = topic;
        this.store = new Store(topic);                                  // ← 初始化 Store
    }

    public int send(Message message) {
        return store.write(message);                                    // ← 写文件
    }

    public Message recv(String consumerId) {
        Subscription sub = subscriptions.get(consumerId);
        if (sub == null || sub.getOffset() >= store.size()) return null;
        int offset = sub.getOffset();
        Message msg = store.read(offset);
        if (msg != null) msg.setOffset(offset);  // 填充 offset，客户端 ack 时使用
        return msg;
    }

    // 批量消费，最多返回 size 条
    public List<Message> batch(String consumerId, int size) {
        Subscription sub = subscriptions.get(consumerId);
        if (sub == null) return Collections.emptyList();
        List<Message> result = new ArrayList<>();
        int offset = sub.getOffset();
        for (int i = 0; i < size; i++) {
            Message msg = store.read(offset + i);
            if (msg == null) break;
            msg.setOffset(offset + i);  // 填充 offset
            result.add(msg);
        }
        return result;
    }

    public int ack(String consumerId, int offset) {
        Subscription sub = subscriptions.get(consumerId);
        if (sub != null) sub.setOffset(offset + 1);
        return offset;
    }

    public Stat stat(String consumerId) {
        Subscription sub = subscriptions.get(consumerId);
        int position = sub == null ? 0 : sub.getOffset();
        return new Stat(store.size(), position);
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
}
