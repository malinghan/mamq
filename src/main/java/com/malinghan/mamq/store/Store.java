package com.malinghan.mamq.store;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

import com.alibaba.fastjson.JSON;
import com.malinghan.mamq.model.Message;

public class Store {

    private static final int HEADER_LEN = 10;  // 长度头固定10字节
    private static final int FILE_SIZE = 1024 * 1024 * 10;  // 10MB，可调整

    private final MappedByteBuffer buffer;
    private final Indexer indexer = new Indexer();
    private int writePosition = 0;  // 当前写入位置

    public Store(String topic) {
        try {
            File dir = new File("mamq-data");
            dir.mkdirs();
            File file = new File(dir, topic + ".dat");
            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            raf.setLength(FILE_SIZE);
            this.buffer = raf.getChannel().map(
                    FileChannel.MapMode.READ_WRITE, 0, FILE_SIZE);
            // 启动时扫描文件，重建索引
            init();
        } catch (IOException e) {
            throw new RuntimeException("Store init failed", e);
        }
    }

    // 启动时扫描 .dat 文件，重建 Indexer
    private void init() {
        //?
        buffer.position(0);
        while (buffer.position() < FILE_SIZE) {
            // 读 10 字节长度头
            byte[] header = new byte[HEADER_LEN];
            buffer.get(header);
            String lenStr = new String(header).trim();
            if (lenStr.isEmpty()) break;  // 到达未写入区域

            int length = Integer.parseInt(lenStr);
            int position = buffer.position();  // 消息体起始位置
            indexer.addEntry(position, length);
            buffer.position(position + length);  // 跳过消息体
            writePosition = buffer.position();
        }
    }

    // 写入消息，返回 offset
    public synchronized int write(Message message) {
        String json = JSON.toJSONString(message);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        // 10字节长度头，左补零
        String header = String.format("%010d", body.length);
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);

        int msgPosition = writePosition + HEADER_LEN;  // 消息体起始位置
        buffer.position(writePosition);
        buffer.put(headerBytes);
        buffer.put(body);
        writePosition = buffer.position();

        indexer.addEntry(msgPosition, body.length);
        return indexer.size() - 1;  // 返回 offset
    }

    // 按 offset 读取消息
    public Message read(int offset) {
        Indexer.Entry entry = indexer.getEntry(offset);
        if (entry == null) return null;

        byte[] body = new byte[entry.getLength()];
        buffer.position(entry.getPosition());
        buffer.get(body);
        return JSON.parseObject(new String(body, StandardCharsets.UTF_8), Message.class);
    }

    public int size() {
        return indexer.size();
    }
}
