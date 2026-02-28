package com.malinghan.mamq.server;

import com.malinghan.mamq.model.Message;
import com.malinghan.mamq.model.Result;
import com.malinghan.mamq.model.Stat;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/mamq")
public class MQServer {

    // Topic → MessageQueue，动态创建
            // messageQueue(topic、messages、subscriptions)
                                            //subscriptions → Subscription[topic、consumerId、offset]
    private final Map<String, MessageQueue> queues = new ConcurrentHashMap<>();

    private MessageQueue getOrCreate(String topic) {
        return queues.computeIfAbsent(topic, MessageQueue::new);
    }

    @PostMapping("/send")
    public Result<Integer> send(@RequestParam String t,
                                @RequestBody Message message) {
        int offset = getOrCreate(t).send(message);
        return Result.ok(offset);
    }

    @GetMapping("/sub")
    public Result<String> sub(@RequestParam String t,
                              @RequestParam String cid) {
        getOrCreate(t).subscribe(cid);
        return Result.ok("OK");
    }

    @GetMapping("/unsub")
    public Result<String> unsub(@RequestParam String t,
                                @RequestParam String cid) {
        getOrCreate(t).unsubscribe(cid);
        return Result.ok("OK");
    }

    @GetMapping("/recv")
    public Result<Message> recv(@RequestParam String t,
                                @RequestParam String cid) {
        Message msg = getOrCreate(t).recv(cid);
        return Result.ok(msg);
    }

    @GetMapping("/ack")
    public Result<Integer> ack(@RequestParam String t,
                               @RequestParam String cid,
                               @RequestParam int offset) {
        int result = getOrCreate(t).ack(cid, offset);
        return Result.ok(result);
    }

    @GetMapping("/stat")
    public Result<Stat> stat(@RequestParam String t,
                             @RequestParam String cid) {
        Stat stat = getOrCreate(t).stat(cid);
        return Result.ok(stat);
    }
}
