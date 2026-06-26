export interface MojangVersionManifest {
  latest: {
    release: string;
    snapshot: string;
  };
  versions: MojangVersion[];
}

export interface MojangVersion {
  id: string;
  type: 'release' | 'snapshot' | 'old_beta' | 'old_alpha';
  url: string;
  time: string;
  releaseTime: string;
}

export interface MojangVersionDetail {
  id: string;
  type: string;
  downloads: {
    client?: MojangDownload;
    server?: MojangDownload;
    client_mappings?: MojangDownload;
    server_mappings?: MojangDownload;
  };
}

export interface MojangDownload {
  sha1: string;
  size: number;
  url: string;
}

export interface FabricMavenMetadata {
  versions: string[];
}

export interface IndexManifest {
  minecraftVersion: string;
  fabricApiVersion: string | null;
  generated: string;
  /**
   * Which parser backend produced this index. Omitted on legacy manifests
   * (pre-2026-05-08), which were always produced by the regex parser; the
   * loader treats `undefined` as `'regex'`. New runs stamp this explicitly
   * so an upgrade to the AST parser can detect stale-but-still-usable
   * indices and prompt for a `rebuild`.
   */
  indexerVersion?: 'regex' | 'ast';
  packages: {
    minecraft: string[];
    fabric: string[];
  };
}

export interface PackageIndex {
  package: string;
  classes: Record<string, ClassInfo>;
}

export type ClassKind = 'class' | 'interface' | 'enum' | 'record';

export interface ClassInfo {
  kind: ClassKind;
  super: string | null;
  interfaces: string[];
  fields: FieldInfo[];
  methods: MethodInfo[];
  sourcePath: string;
}

export interface FieldInfo {
  name: string;
  type: string;
  modifiers: string[];
}

export interface MethodInfo {
  name: string;
  returnType: string;
  params: { name: string; type: string }[];
  modifiers: string[];
  lineStart: number;
  lineEnd: number;
}

/**
 * Discriminated union of search hit shapes.
 *
 * Previously this was a single interface with every per-kind field marked
 * optional, which let producers forget to set required fields and consumers
 * touch fields that don't exist for a given hit type — TS couldn't catch
 * either side. Splitting into three branches keyed on `type` lets the
 * compiler verify each producer fills the fields its branch needs and each
 * consumer narrows correctly with `if (r.type === 'class') { ... }`.
 */
interface SearchResultBase {
  className: string;
  name: string;
  sourcePath: string;
}

export interface ClassSearchResult extends SearchResultBase {
  type: 'class';
  /** Class kind (class/interface/enum/record). */
  kind: ClassKind;
  /** Direct superclass (null for Object / interfaces / records). */
  superClass: string | null;
  /** Implemented interfaces. */
  interfaces: string[];
  /** Counts for the inline summary line — "(13 fields, 247 methods)". */
  fieldCount: number;
  methodCount: number;
}

export interface MethodSearchResult extends SearchResultBase {
  type: 'method';
  /** Full signature including return type and params. */
  signature: string;
  lineStart: number;
  /** Modifiers — public/private/static/final/etc. */
  modifiers: string[];
}

export interface FieldSearchResult extends SearchResultBase {
  type: 'field';
  /** Inline declaration: `<type> <name>`. */
  signature: string;
  /** Modifiers — public/private/static/final/etc. */
  modifiers: string[];
}

export type SearchResult = ClassSearchResult | MethodSearchResult | FieldSearchResult;
