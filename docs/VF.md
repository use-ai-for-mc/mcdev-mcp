# Vineflower Decompilation

mcdev-mcp embeds Vineflower in the shaded release JAR. Decompilation is an
in-process Java service: users do not install Python, clone a helper project, or
download a separate decompiler launcher.

## Pipeline

For `init -v <version>`, `MinecraftDecompiler` coordinates:

1. Fetch Mojang's version manifest and selected version metadata.
2. Download the client JAR and verify the advertised SHA-1.
3. Download official client mappings when that version publishes them.
4. Convert mappings and remap the client with Tiny Remapper.
5. Run embedded Vineflower against the remapped JAR.
6. Validate the candidate output and publish the version cache atomically.

Versions without official mappings follow the supported direct-decompilation
path rather than invoking an alternate external toolchain.

## Ownership

| Java package/class | Responsibility |
|---|---|
| `analysis.decompile.VersionManifestClient` | Mojang manifest and download metadata. |
| `analysis.decompile.DownloadService` | Bounded HTTP downloads and hash verification. |
| `analysis.decompile.MappingConverter` | Official mapping conversion. |
| `analysis.decompile.MinecraftRemapper` | Tiny Remapper integration. |
| `analysis.decompile.MinecraftDecompiler` | Embedded Vineflower invocation, locking, validation, and publication. |
| `storage.PlatformPaths` | Versioned cache destinations. |

The exact dependency versions are pinned in `gradle/libs.versions.toml` and are
part of the one release JAR.

## Cache Shape

```text
<cache-root>/cache/<minecraft-version>/
|-- client/                              decompiled sources and resources
|-- jars/<version>_unobfuscated.jar      remapped analysis input
`-- callgraph/client-remapped.jar         class-file scan input
```

Temporary candidates stay beneath managed scratch directories. Publication
rejects redirected paths and never replaces a valid cache with an incomplete
decompilation.

## Commands

```powershell
java -jar mcdev-mcp-3.0.0.jar init -v 1.21.11
java -jar mcdev-mcp-3.0.0.jar clean --cache -v 1.21.11
java -jar mcdev-mcp-3.0.0.jar status -v 1.21.11
```

`rebuild` operates on already prepared sources; use `init` when decompiled
sources or remapped inputs are absent.

The obsolete DecompilerMC fork proposal is retained only as design history in
[`fork.md`](fork.md).
