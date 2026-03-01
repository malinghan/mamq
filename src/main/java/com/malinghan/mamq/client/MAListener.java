package com.malinghan.mamq.client;

import com.malinghan.mamq.model.Message;

@FunctionalInterface
public interface MAListener<T> {
    void onMessage(Message<T> message);
}
