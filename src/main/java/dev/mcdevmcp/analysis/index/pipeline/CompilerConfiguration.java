package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.index.IndexBuildException;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class CompilerConfiguration {
    private CompilerConfiguration() {
    }

    static JavaCompiler compiler() throws IndexBuildException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IndexBuildException("The JDK system Java compiler is required for source indexing");
        }
        return compiler;
    }

    static StandardJavaFileManager fileManager(JavaCompiler compiler) throws IOException {
        return compiler.getStandardFileManager(null, java.util.Locale.ROOT, StandardCharsets.UTF_8);
    }

    static List<String> options() {
        return List.of("--release", "25", "-proc:none", "-implicit:none", "-encoding", "UTF-8", "-Xmaxerrs", "0", "-Xmaxwarns", "0");
    }
}
