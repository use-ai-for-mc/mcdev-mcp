package dev.mcdevmcp.mcp.tool.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.math.BigDecimal;
import java.util.Objects;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({@JsonSubTypes.Type(value = TestUnion.Numeric.class, name = "numeric", names = "number"), @JsonSubTypes.Type(value = TestUnion.Text.class, name = "text")})
sealed interface TestUnion {
    value record Numeric(@InputProperty(required = true) BigDecimal value) implements TestUnion {
        public Numeric {
            Objects.requireNonNull(value, "value");
        }
    }

    value record Text(@InputProperty(required = true) String value) implements TestUnion {
        public Text {
            Objects.requireNonNull(value, "value");
        }
    }
}