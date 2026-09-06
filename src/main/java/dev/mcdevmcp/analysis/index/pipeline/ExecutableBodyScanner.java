package dev.mcdevmcp.analysis.index.pipeline;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;

import java.util.ArrayList;
import java.util.List;

final class ExecutableBodyScanner extends TreeScanner<Void, Void> {
    private final CompilationUnitTree unit;
    private final SourcePositions positions;
    private final List<OffsetRange> ranges = new ArrayList<>();

    ExecutableBodyScanner(CompilationUnitTree unit, SourcePositions positions) {
        this.unit = unit;
        this.positions = positions;
    }

    List<OffsetRange> scan() {
        scan(unit, null);
        return List.copyOf(ranges);
    }

    @Override
    public Void visitBlock(BlockTree node, Void unused) {
        long start = positions.getStartPosition(unit, node);
        long end = positions.getEndPosition(unit, node);
        if (start >= 0 && end >= start) {
            ranges.add(new OffsetRange(start, end));
        }
        return super.visitBlock(node, unused);
    }

    @Override
    public Void visitVariable(VariableTree node, Void unused) {
        if (node.getInitializer() != null) {
            long start = positions.getStartPosition(unit, node.getInitializer());
            long end = positions.getEndPosition(unit, node.getInitializer());
            if (start >= 0 && end >= start) {
                ranges.add(new OffsetRange(start, end));
            }
        }
        return super.visitVariable(node, unused);
    }
}
