package com.malinghan.mamq.store;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

public class Indexer {

    @Data
    @AllArgsConstructor
    public static class Entry {
        private int position;  // 消息在文件中的字节起始位置
        private int length;    // 消息体字节长度（不含10字节头）
    }

    // offset（消息序号）→ Entry
    private final Map<Integer, Entry> index = new HashMap<>();
    private int nextOffset = 0;

    public void addEntry(int position, int length) {
        index.put(nextOffset++, new Entry(position, length));
    }

    public Entry getEntry(int offset) {
        return index.get(offset);
    }

    public int size() {
        return nextOffset;
    }
}