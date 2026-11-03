package dev.mcdevmcp.tools.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;

enum EntityItemSlot {
    @JsonProperty("mainhand") MAINHAND, @JsonProperty("offhand") OFFHAND, @JsonProperty("feet") FEET, @JsonProperty("legs") LEGS, @JsonProperty("chest") CHEST, @JsonProperty("head") HEAD, @JsonProperty("body") BODY, @JsonProperty("saddle") SADDLE, @JsonProperty("frame") FRAME, @JsonProperty("display") DISPLAY;

    String bridgeValue() {
        return switch (this) {
            case MAINHAND -> "MAINHAND";
            case OFFHAND -> "OFFHAND";
            case FEET -> "FEET";
            case LEGS -> "LEGS";
            case CHEST -> "CHEST";
            case HEAD -> "HEAD";
            case BODY -> "BODY";
            case SADDLE -> "SADDLE";
            case FRAME -> "FRAME";
            case DISPLAY -> "DISPLAY";
        };
    }
}