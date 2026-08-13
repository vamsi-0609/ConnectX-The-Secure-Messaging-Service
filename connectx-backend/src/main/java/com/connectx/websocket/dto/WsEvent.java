package com.connectx.websocket.dto;

import java.util.Map;

public class WsEvent {

    private String type;
    private String requestId;
    private Map<String, Object> payload;

    public WsEvent() {}

    public WsEvent(String type, String requestId, Map<String, Object> payload) {
        this.type = type;
        this.requestId = requestId;
        this.payload = payload;
    }

    public static WsEvent of(String type, Map<String, Object> payload) {
        return new WsEvent(type, null, payload);
    }

    public static WsEvent of(String type, String requestId, Map<String, Object> payload) {
        return new WsEvent(type, requestId, payload);
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }
}
