package dev.mcdevmcp.mcp.tool.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface InputProperty {
    String description() default "";

    boolean required() default false;

    String minimum() default "";

    String maximum() default "";

    String defaultValue() default "";
}
