package dev.mcdevmcp.analysis.index.pipeline;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavacSourceParserVisibilityTest {
    @Test
    void parserIsInternalToThePipelinePackage() {
        assertFalse(Modifier.isPublic(JavacSourceParser.class.getModifiers()));
        assertTrue(Arrays.stream(JavacSourceParser.class.getDeclaredConstructors()).noneMatch(constructor -> Modifier.isPublic(constructor.getModifiers())));
    }
}
