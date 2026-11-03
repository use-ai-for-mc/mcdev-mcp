package dev.mcdevmcp.analysis.index.pipeline;

import javax.tools.SimpleJavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;

final class MemoryClassFileObject extends SimpleJavaFileObject {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    MemoryClassFileObject(String className) {
        super(URI.create("memory:///classes/" + className.replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
    }

    @Override
    public OutputStream openOutputStream() {
        output.reset();
        return output;
    }
}
