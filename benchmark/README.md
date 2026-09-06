# Corpus Qualification And Benchmarks

The release gate uses complete, reviewed Minecraft `1.21.11` and `26.1` inputs
on Java 26. Qualification runs with one worker and with `min(4, available CPUs)`
workers. The blocking benchmark uses G1; manually requested Parallel GC runs
are advisory. Synthetic fixtures validate the harness and cannot qualify a
real corpus.

## Immutable Compiler Dependencies

Both `CorpusQualificationMain` and `AnalysisBenchmarkMain` require
`--classpath-manifest <path>`. Compiler dependencies are explicit inputs;
neither the launcher classpath nor an ambient library directory supplies them.
Callgraph scanning remains limited to the remapped Minecraft JAR.

Each corpus has a dedicated immutable directory containing its classpath
manifest, the exact official metadata files, and the library JARs at their
relative Maven paths. Paths resolve from the manifest's parent. Preserve this
whole tree when moving the corpus; do not flatten library filenames. The
workflow stages the tree and checks its logical hash before using it, and
rejects symbolic links in that tree. Keep source trees and mutable outputs
outside this directory.

The classpath manifest uses schema 1:

```json
{
  "schemaVersion": 1,
  "kind": "MOJANG",
  "minecraftVersion": "1.21.11",
  "metadataSha256": "<SHA-256 of the exact version metadata bytes>",
  "metadata": {
    "globalManifestPath": "version_manifest_v2.json",
    "globalManifestSha256": "<SHA-256 of the exact global manifest bytes>",
    "versionManifestPath": "version.json",
    "versionManifestUrl": "<exact URL in the global manifest entry>",
    "versionManifestSha1": "<SHA-1 in the global manifest entry>"
  },
  "artifacts": [
    {
      "relativePath": "org/example/library/1.0/library-1.0.jar",
      "size": 123,
      "sha256": "<SHA-256 of the library bytes>"
    }
  ]
}
```

The example artifact is illustrative. Preparation must enumerate the complete
selected non-native compiler-library set from the requested version's official
metadata, in original metadata order. It must verify official artifact SHA-1
and byte length and retain that evidence before recording SHA-256. A malformed
selected artifact fails preparation. The loader verifies the complete selected
set, metadata linkage, relative paths, lengths, and hashes. Duplicate or escaping
paths and overlap with mutable output roots are rejected. Unlisted JARs are not
compiler inputs.

For `1.21.11`, use that version's library metadata and separately retain the
special unobfuscated client provenance. The client's provenance does not change
the requested library version.

Classpath identity is lowercase SHA-256 over the following UTF-8 byte sequence:
`mcdev-corpus-classpath-v1`, NUL, kind, NUL, requested Minecraft version, NUL,
metadata SHA-256, NUL, then each artifact in manifest order as relative path,
NUL, decimal byte length, NUL, lowercase artifact SHA-256, NUL. The identity is
independent of machine paths. The raw manifest SHA-256 is pinned separately;
even a formatting-only manifest edit requires a new reviewed raw hash.

## Workflow Inputs And Evidence

The `corpus_manifest` workflow input, or `MCDEV_BENCHMARK_CORPUS_MANIFEST`
repository variable, identifies an outer schema-1 manifest with exactly two
`corpora` entries, IDs and Minecraft versions `1.21.11` and `26.1`. Each entry
provides these paths and hashes:

| Path field | Integrity field |
| --- | --- |
| `sourceRoot` | `sourceLogicalHash` |
| `remappedJar` | `remappedJarSha256` |
| `nodeBaseline` | `nodeBaselineSha256` |
| `nodeCallgraph` | `nodeCallgraphSha256` |
| `nodeCallgraphGenerator` | `nodeCallgraphGeneratorSha256` |
| `expectation` | `expectationSha256` |
| `classpathManifest` | `classpathManifestSha256` |

Each entry also includes `id`, `minecraftVersion`, and `productionCacheRoot`.
Paths identify prepared runner inputs. `productionCacheRoot` protects the real
cache from qualification and benchmark outputs. Source logical hashes include
sorted portable relative filenames, NUL, file bytes, and NUL for every file.

Reviewed expectations use schema 2 and add `classpathIdentity` and
`classpathManifestSha256`. Bind those fields to the reviewed dependency inputs
before qualification; candidate observations alone do not establish accepted
expectations. Frozen Node baseline schema, counts, probes, and source/JAR
identities are unchanged by compiler dependencies.

Qualification and benchmark reports use schema 2 and include `classpath` with
`kind`, `identity`, `manifestSha256`, `metadataSha256`, and `artifacts`. The
workflow requires `MOJANG`, matches identity and raw hash to the expectation,
and matches metadata hash and ordered artifacts to the staged manifest. Worker
comparisons include that evidence alongside symbol and callgraph hashes.
Explicit `SYNTHETIC` manifests are restricted to component tests.

The benchmark parent forwards `--classpath-manifest` to each child, pins
`--classpath-identity` and `--classpath-manifest-sha256`, and validates input
integrity before and after each child outside its timed interval. Child output
uses schema 2 and echoes those two identities. Actual compiler loading remains
inside indexing time. Reports retain five measurements, runtime/GC metadata,
memory metrics, input hashes, and producer JAR identity. Downloaded benchmark
producer bytes must match the canonical candidate SHA-256 before they count as
release evidence.

The workflow requires the self-hosted Linux `mcdev-benchmark` runner. A local
qualification pass does not supply its benchmark evidence. Keep this gate open
when the runner is unavailable while completing other available release checks.
