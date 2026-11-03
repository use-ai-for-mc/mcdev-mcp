package dev.mcdevmcp.analysis.index.pipeline;

import javax.tools.SimpleJavaFileObject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;

final class MemoryInputClassFileObject extends SimpleJavaFileObject {
    private final CompilerClassFile classFile;

    MemoryInputClassFileObject(CompilerClassFile classFile) {
        super(URI.create("memory:///classpath/" + classFile.binaryName().replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
        this.classFile = classFile;
    }

    CompilerClassFile classFile() {
        return classFile;
    }

    @Override
    public InputStream openInputStream() {
        return new ByteArrayInputStream(classFile.bytes());
    }
}
