package dev.mcdevmcp.mcp.transport;

import dev.mcdevmcp.mcp.ServerDefinition;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSdkAdapterVisibilityTest {
    @Test
    void exposesOnlyTheDeliberateTransportFacade() throws NoSuchMethodException {
        assertTrue(Arrays.stream(McpSdkAdapter.class.getDeclaredConstructors()).noneMatch(constructor -> Modifier.isPublic(constructor.getModifiers())));

        var publicMethods = Arrays.stream(McpSdkAdapter.class.getDeclaredMethods()).filter(method -> Modifier.isPublic(method.getModifiers())).toList();
        assertEquals(3, publicMethods.size());
        assertPublicStaticMethod("startStdio", StdioServer.class, McpJsonMapper.class, InputStream.class, OutputStream.class, ServerDefinition.class, ExecutorService.class, AutoCloseable.class);
        assertPublicStaticMethod("startStreamable", McpSdkAdapter.StreamableServer.class, McpJsonMapper.class, McpStreamableServerTransportProvider.class, ServerDefinition.class, ExecutorService.class);
        assertPublicStaticMethod("startStreamable", McpSdkAdapter.StreamableServer.class, McpJsonMapper.class, McpStreamableServerTransportProvider.class, ServerDefinition.class, ExecutorService.class, McpSdkAdapter.AsyncServerExtensions.class);

        assertTrue(Arrays.stream(StdioServer.class.getDeclaredConstructors()).noneMatch(constructor -> Modifier.isPublic(constructor.getModifiers())));
        var streamablePublicMethods = Arrays.stream(McpSdkAdapter.StreamableServer.class.getDeclaredMethods()).filter(method -> Modifier.isPublic(method.getModifiers())).toList();
        assertEquals(1, streamablePublicMethods.size());
        assertEquals("close", streamablePublicMethods.getFirst().getName());
        assertEquals(void.class, streamablePublicMethods.getFirst().getReturnType());
        assertEquals(0, streamablePublicMethods.getFirst().getParameterCount());
    }

    private static void assertPublicStaticMethod(String name, Class<?> returnType, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = McpSdkAdapter.class.getDeclaredMethod(name, parameterTypes);
        assertEquals(returnType, method.getReturnType());
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertTrue(Modifier.isStatic(method.getModifiers()));
    }
}
