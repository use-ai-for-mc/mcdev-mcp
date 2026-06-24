export interface ParsedClass {
  packageName: string;
  className: string;
  fullName: string;
  info: import('../utils/types.js').ClassInfo;
}

export interface IndexBuildResult {
  minecraftVersion: string;
  fabricApiVersion: string | null;
  packagesIndexed: number;
  classesIndexed: number;
}
