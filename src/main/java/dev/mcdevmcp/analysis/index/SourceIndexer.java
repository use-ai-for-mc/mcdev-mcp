package dev.mcdevmcp.analysis.index;

import dev.mcdevmcp.analysis.index.pipeline.SourceIndexPipeline;

import java.util.Objects;

public value class SourceIndexer {
    private final SourceIndexPipeline pipeline;

    public SourceIndexer() {
        this(new SourceIndexPipeline());
    }

    SourceIndexer(SourceIndexPipeline pipeline) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    }

    public IndexSummary build(IndexRequest request) throws IndexBuildException {
        return pipeline.build(request);
    }

    public IndexSummary build(IndexRequest request, PublishedSourceRoot publishedSourceRoot) throws IndexBuildException {
        return pipeline.build(request, publishedSourceRoot);
    }
}
