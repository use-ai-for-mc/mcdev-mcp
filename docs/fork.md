# DecompilerMC Fork Proposal: Abandoned

This document records a rejected design. mcdev-mcp does not clone, patch, or
execute DecompilerMC.

## Original Proposal

The earlier server considered maintaining a Python fork that could handle
development snapshots and load external decompiler/remapping JARs from a local
tools directory. That would have required a repository clone, a Python runtime,
several downloaded launchers, and another subprocess protocol.

## Shipped Decision

The Java server instead owns the complete pipeline:

- `VersionManifestClient` resolves Mojang metadata.
- `DownloadService` downloads and verifies artifacts.
- `MappingConverter` converts official mappings.
- `MinecraftRemapper` embeds Tiny Remapper.
- `MinecraftDecompiler` embeds Vineflower and atomically publishes sources.

All of those classes live in `dev.mcdevmcp.analysis.decompile`. Their
dependencies are shaded into the single release JAR. There is no `lib/`
toolchain directory, downloaded analysis-tool launcher, Python requirement, or
DecompilerMC source in the repository.

## Operational Consequences

Users run only the Java CLI:

```powershell
java -jar mcdev-mcp-3.0.0.jar init -v 1.21.11
```

Source-cache cleanup and retry are likewise owned by the CLI:

```powershell
java -jar mcdev-mcp-3.0.0.jar clean --cache -v 1.21.11
java -jar mcdev-mcp-3.0.0.jar init -v 1.21.11
```

See [`VF.md`](VF.md) for the current pipeline and ownership map.
