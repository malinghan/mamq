package com.malinghan.mamq.client;

import com.malinghan.mamq.model.Message;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MABroker {

    private static final MABroker DEFAULT = new MABroker("http://localhost:8765");

    private final String baseUrl;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    // 记录所有注册了 listener 的 consumer，后台线程轮询
    private final List<MAConsumer<?>> consumers = new CopyOnWriteArrayList<>();

    private MABroker(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public static MABroker getDefault() {
        return DEFAULT;
    }

    public static MABroker of(String host, int port) {
        return new MABroker("http://" + host + ":" + port);
    }

    public MAProducer createProducer() {
        return new MAProducer(baseUrl);
    }

    public <T> MAConsumer<T> createConsumer(String topic, String consumerId) {
        MAConsumer<T> consumer = new MAConsumer<>(baseUrl, topic, consumerId, null);
        consumers.add(consumer);
        return consumer;
    }

    // 启动后台轮询（在 listen() 注册后调用）
    public void startPolling(MAConsumer<?> consumer) {
        executor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    pollOnce(consumer);
                } catch (Exception e) {
                    // 轮询异常不中断循环
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private <T> void pollOnce(MAConsumer<T> consumer) throws InterruptedException {
        Message<T> msg = consumer.recv();
        if (msg == null) {
            Thread.sleep(100);  // 无消息时等待 100ms
            return;
        }
        if (consumer.listener != null) {
            consumer.listener.onMessage(msg);
            consumer.ack(msg.getOffset());  // 回调成功后自动 ACK
        }
        // 有消息时不 sleep，立即取下一条
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
