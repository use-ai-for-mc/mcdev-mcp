package dev.mcdevmcp.mcp.transport;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@SuppressWarnings("SameParameterValue")
record SdkJsonProbe(URI uri, SdkJsonMode mode, List<SdkJsonItem> items, BigInteger integral, BigDecimal decimal, Duration duration, Instant instant) {
}
