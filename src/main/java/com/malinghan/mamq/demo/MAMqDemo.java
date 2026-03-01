package com.malinghan.mamq.demo;

import com.malinghan.mamq.client.MABroker;
import com.malinghan.mamq.client.MAConsumer;
import com.malinghan.mamq.client.MAProducer;
import com.malinghan.mamq.model.Message;

import java.util.Scanner;

public class MAMqDemo {

    public static void main(String[] args) throws Exception {
        MABroker broker = MABroker.getDefault();
        MAProducer producer = broker.createProducer();
        MAConsumer<String> consumer = broker.createConsumer("demo.topic", "demo-c1");
        consumer.sub();

        Scanner scanner = new Scanner(System.in);
        System.out.println("命令: p=生产 c=消费 s=统计 b=批量生产10条 l=启动push监听 q=退出");

        while (scanner.hasNextLine()) {
            String cmd = scanner.nextLine().trim();
            switch (cmd) {
                case "p" -> {
                    long id = System.currentTimeMillis();
                    int offset = producer.send("demo.topic",
                            new Message<>(id, "msg-" + id, null, null));
                    System.out.println("sent, offset=" + offset);
                }
                case "c" -> {
                    Message<String> msg = consumer.recv();
                    if (msg == null) {
                        System.out.println("no message");
                    } else {
                        System.out.println("recv: " + msg);
                        consumer.ack(msg.getOffset());
                    }
                }
                case "s" -> System.out.println("stat: " + consumer.stat());
                case "b" -> {
                    for (int i = 0; i < 10; i++) {
                        producer.send("demo.topic",
                                new Message<>((long) i, "batch-" + i, null, null));
                    }
                    System.out.println("sent 10 messages");
                }
                case "l" -> {
                    consumer.listen(msg -> System.out.println("push recv: " + msg.getBody()));
                    broker.startPolling(consumer);
                    System.out.println("push listener started");
                }
                case "q" -> {
                    broker.shutdown();
                    System.exit(0);
                }
            }
        }
    }
}