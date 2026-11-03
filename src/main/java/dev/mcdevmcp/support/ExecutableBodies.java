package dev.mcdevmcp.support;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;

import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;

/**
 * The parts of a compilation unit in which code runs: its blocks and its variable initializers.
 * A diagnostic confined to one of them concerns what the code does, not what the unit declares.
 */
public value class ExecutableBodies {
    private static final String PREMATURE_END_OF_FILE = "compiler.err.premature.eof";
    private final List<Range> blocks;
    private final List<Range> initializers;

    private ExecutableBodies(List<Range> blocks, List<Range> initializers) {
        this.blocks = List.copyOf(blocks);
        this.initializers = List.copyOf(initializers);
    }

    public static ExecutableBodies none() {
        return new ExecutableBodies(List.of(), List.of());
    }

    public static ExecutableBodies of(CompilationUnitTree unit, SourcePositions positions) {
        List<Range> blocks = new ArrayList<>();
        List<Range> initializers = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitBlock(BlockTree node, Void unused) {
                add(blocks, positions.getStartPosition(node), positions.getEndPosition(node));
                return super.visitBlock(node, unused);
            }

            @Override
            public Void visitVariable(VariableTree node, Void unused) {
                if (node.getInitializer() != null) {
                    add(initializers, positions.getStartPosition(node.getInitializer()), positions.getEndPosition(node.getInitializer()));
                }
                return super.visitVariable(node, unused);
            }
        }.scan(unit, null);
        return new ExecutableBodies(blocks, initializers);
    }

    private static void add(List<Range> ranges, long start, long end) {
        if (start >= 0 && end >= start) {
            ranges.add(new Range(start, end));
        }
    }

    /**
     * Whether the diagnostic lies within a block or a variable initializer.
     */
    public boolean contain(Diagnostic<?> diagnostic) {
        long start = diagnostic.getStartPosition();
        long end = Math.max(diagnostic.getEndPosition(), start);
        return start >= 0 && (blocks.stream().anyMatch(block -> block.start() <= start && end <= block.end()) || initializers.stream().anyMatch(initializer -> initializer.start() <= start && end <= initializer.end()));
    }

    /**
     * Whether a syntax error reaches beyond the bodies, so that the declarations of the unit can
     * no longer be trusted. It does not when it lies past the opening brace of a block, which is
     * where a decompiler leaves what it could not express as Java. An error at the brace itself
     * belongs to the header before it, and a unit that ends early is incomplete wherever the
     * parser happened to be.
     */
    public boolean escapedBy(Diagnostic<?> syntaxError) {
        long start = syntaxError.getStartPosition();
        long end = Math.max(syntaxError.getEndPosition(), start);
        return PREMATURE_END_OF_FILE.equals(syntaxError.getCode()) || blocks.stream().noneMatch(block -> block.start() < start && end <= block.end());
    }

    private value record Range(long start, long end) {
    }
}
