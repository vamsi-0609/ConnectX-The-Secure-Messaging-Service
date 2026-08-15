package com.connectx.push.dto;

import jakarta.validation.constraints.NotBlank;

public class PushSubscriptionRequestDto {

    @NotBlank(message = "Endpoint is required")
    private String endpoint;

    private KeysDto keys;

    public PushSubscriptionRequestDto() {}

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public KeysDto getKeys() {
        return keys;
    }

    public void setKeys(KeysDto keys) {
        this.keys = keys;
    }

    public static class KeysDto {
        private String p256dh;
        private String auth;

        public KeysDto() {}

        public String getP256dh() {
            return p256dh;
        }

        public void setP256dh(String p256dh) {
            this.p256dh = p256dh;
        }

        public String getAuth() {
            return auth;
        }

        public void setAuth(String auth) {
            this.auth = auth;
        }
    }
}
