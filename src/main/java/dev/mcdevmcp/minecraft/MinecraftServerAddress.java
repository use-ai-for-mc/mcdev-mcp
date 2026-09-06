package dev.mcdevmcp.minecraft;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.net.IDN;

/**
 * A validated server-address spelling accepted by Minecraft's server resolver.
 *
 * <p>The spelling is deliberately retained as supplied (apart from the
 * surrounding {@link String#trim()}), because the bridge performs the final
 * version-specific validation and parsing.</p>
 */
public record MinecraftServerAddress(String value) {
    private static final int MAXIMUM_LENGTH = 256;

    public MinecraftServerAddress {
        value = validate(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static MinecraftServerAddress fromJson(String value) {
        return new MinecraftServerAddress(value);
    }

    @JsonValue
    public String wireValue() {
        return value;
    }

    private static String validate(String supplied) {
        if (supplied == null) {
            throw new IllegalArgumentException("Minecraft server address must not be null");
        }
        String spelling = supplied.trim();
        if (spelling.isEmpty() || spelling.length() > MAXIMUM_LENGTH) {
            throw new IllegalArgumentException("Minecraft server address must be 1-256 characters after trimming");
        }
        HostPort hostPort = split(spelling);
        if (hostPort.host().isEmpty()) {
            throw invalid(spelling);
        }
        try {
            IDN.toASCII(hostPort.host());
        } catch (IllegalArgumentException exception) {
            throw invalid(spelling, exception);
        }
        return spelling;
    }

    private static HostPort split(String spelling) {
        if (spelling.charAt(0) == '[') {
            return splitBracketed(spelling);
        }
        if (containsBracket(spelling)) {
            throw invalid(spelling);
        }
        int firstColon = spelling.indexOf(':');
        if (firstColon >= 0 && firstColon == spelling.lastIndexOf(':')) {
            parsePort(spelling.substring(firstColon + 1), spelling);
            return new HostPort(spelling.substring(0, firstColon));
        }
        // HostAndPort treats an unbracketed multi-colon spelling as a host,
        // which is what permits bracketless IPv6 literals here.
        return new HostPort(spelling);
    }

    private static HostPort splitBracketed(String spelling) {
        int firstColon = spelling.indexOf(':');
        int closeBracket = spelling.lastIndexOf(']');
        if (firstColon < 0 || closeBracket <= firstColon) {
            throw invalid(spelling);
        }
        String host = spelling.substring(1, closeBracket);
        String suffix = spelling.substring(closeBracket + 1);
        if (suffix.isEmpty()) {
            return new HostPort(host);
        }
        if (suffix.charAt(0) != ':') {
            throw invalid(spelling);
        }
        parsePort(suffix.substring(1), spelling);
        return new HostPort(host);
    }

    private static boolean containsBracket(String spelling) {
        return spelling.indexOf('[') >= 0 || spelling.indexOf(']') >= 0;
    }

    private static void parsePort(String port, String spelling) {
        if (port.isEmpty()) {
            return;
        }
        int parsed = 0;
        for (int index = 0; index < port.length(); index++) {
            char character = port.charAt(index);
            if (character < '0' || character > '9') {
                throw invalid(spelling);
            }
            int digit = character - '0';
            if (parsed > (65535 - digit) / 10) {
                throw invalid(spelling);
            }
            parsed = parsed * 10 + digit;
        }
    }

    private static IllegalArgumentException invalid(String spelling) {
        return new IllegalArgumentException("Invalid Minecraft server address: " + spelling);
    }

    private static IllegalArgumentException invalid(String spelling, Throwable cause) {
        return new IllegalArgumentException("Invalid Minecraft server address: " + spelling, cause);
    }

    private record HostPort(String host) {
    }
}
