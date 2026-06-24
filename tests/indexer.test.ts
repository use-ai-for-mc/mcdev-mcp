import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { buildIndex, loadPackageIndex } from '../src/indexer/index.js';
import { getVersionedIndexManifestPath } from '../src/utils/paths.js';
import { readJsonFileOrNull } from '../src/utils/json-file.js';
import type { IndexManifest } from '../src/utils/types.js';

describe('Index Builder', () => {
  const tempDir = path.join(os.tmpdir(), 'mcdev-mcp-test-' + Date.now());
  
  beforeEach(() => {
    if (!fs.existsSync(tempDir)) {
      fs.mkdirSync(tempDir, { recursive: true });
    }
  });
  
  afterEach(() => {
    if (fs.existsSync(tempDir)) {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });
  
  test('builds index from test sources', async () => {
    const testPackageDir = path.join(tempDir, 'net', 'minecraft', 'test');
    fs.mkdirSync(testPackageDir, { recursive: true });
    
    const javaCode = `
package net.minecraft.test;

public class TestClass extends BaseClass implements TestInterface {
    public static final int CONSTANT = 1;
    private String name;
    
    public void doSomething() {
    }
    
    public int calculate(int a, int b) {
        return a + b;
    }
}
`;
    
    fs.writeFileSync(path.join(testPackageDir, 'TestClass.java'), javaCode);
    
    const indexDir = path.join(tempDir, 'index');
    const manifestPath = path.join(indexDir, 'manifest.json');
    
    const result = await buildIndex({
      minecraftSourceDir: tempDir,
      fabricApiSourceDir: null,
      minecraftVersion: '1.0.0-test',
      fabricApiVersion: null,
    });
    
    expect(result.minecraftPackages).toContain('net.minecraft.test');
    expect(result.totalClasses).toBeGreaterThan(0);
  });

  test('merges package index flushes when package files are non-contiguous', async () => {
    const version = `split-package-${Date.now()}`;
    const files = [
      ['aaa', 'One.java', 'net.shared', 'One'],
      ['bbb', 'Middle.java', 'net.other', 'Middle'],
      ['ccc', 'Two.java', 'net.shared', 'Two'],
    ];

    for (const [dir, fileName, pkg, className] of files) {
      const targetDir = path.join(tempDir, dir);
      fs.mkdirSync(targetDir, { recursive: true });
      fs.writeFileSync(path.join(targetDir, fileName), `
package ${pkg};

public class ${className} {
    public void run() {}
}
`);
    }

    await buildIndex({
      minecraftSourceDir: tempDir,
      fabricApiSourceDir: null,
      minecraftVersion: version,
      fabricApiVersion: null,
    });

    const sharedPackage = loadPackageIndex('minecraft', 'net.shared', version);
    expect(Object.keys(sharedPackage?.classes ?? {}).sort()).toEqual(['One', 'Two']);
  });
});

describe('AST parser worker index build', () => {
  const ORIG_AST = process.env.MCDEV_AST_PARSER;
  const ORIG_TREE = process.env.MCDEV_TREE_SITTER_PARSER;
  const ORIG_WORKERS = process.env.MCDEV_INDEX_WORKERS;
  const ORIG_BATCH = process.env.MCDEV_INDEX_BATCH_SIZE;
  const ORIG_WORKER_PATH = process.env.MCDEV_INDEX_PARSE_WORKER_PATH;
  const ORIG_MARKER = process.env.MCDEV_INDEX_WORKER_MARKER;
  const tempDir = path.join(os.tmpdir(), 'mcdev-mcp-ast-worker-' + Date.now());

  beforeEach(() => {
    process.env.MCDEV_AST_PARSER = '1';
    delete process.env.MCDEV_TREE_SITTER_PARSER;
    process.env.MCDEV_INDEX_WORKERS = '1';
    process.env.MCDEV_INDEX_BATCH_SIZE = '1';
    if (!fs.existsSync(tempDir)) {
      fs.mkdirSync(tempDir, { recursive: true });
    }
  });

  afterEach(() => {
    if (ORIG_AST === undefined) delete process.env.MCDEV_AST_PARSER;
    else process.env.MCDEV_AST_PARSER = ORIG_AST;
    if (ORIG_TREE === undefined) delete process.env.MCDEV_TREE_SITTER_PARSER;
    else process.env.MCDEV_TREE_SITTER_PARSER = ORIG_TREE;
    if (ORIG_WORKERS === undefined) delete process.env.MCDEV_INDEX_WORKERS;
    else process.env.MCDEV_INDEX_WORKERS = ORIG_WORKERS;
    if (ORIG_BATCH === undefined) delete process.env.MCDEV_INDEX_BATCH_SIZE;
    else process.env.MCDEV_INDEX_BATCH_SIZE = ORIG_BATCH;
    if (ORIG_WORKER_PATH === undefined) delete process.env.MCDEV_INDEX_PARSE_WORKER_PATH;
    else process.env.MCDEV_INDEX_PARSE_WORKER_PATH = ORIG_WORKER_PATH;
    if (ORIG_MARKER === undefined) delete process.env.MCDEV_INDEX_WORKER_MARKER;
    else process.env.MCDEV_INDEX_WORKER_MARKER = ORIG_MARKER;
    if (fs.existsSync(tempDir)) {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  test('builds through the compiled parse worker and records an ast manifest', async () => {
    const workerPath = path.join(process.cwd(), 'dist', 'indexer', 'parse-worker.js');
    expect(fs.existsSync(workerPath)).toBe(true);
    process.env.MCDEV_INDEX_PARSE_WORKER_PATH = workerPath;

    const markerPath = path.join(tempDir, 'worker-used.txt');
    process.env.MCDEV_INDEX_WORKER_MARKER = markerPath;

    const pkgDir = path.join(tempDir, 'worker');
    fs.mkdirSync(pkgDir, { recursive: true });
    for (let i = 0; i < 3; i++) {
      fs.writeFileSync(path.join(pkgDir, `Worker${i}.java`), `
package worker.test;

public class Worker${i} {
    public int value${i}() { return ${i}; }
}
`);
    }

    const version = `ast-worker-${Date.now()}`;
    const result = await buildIndex({
      minecraftSourceDir: tempDir,
      fabricApiSourceDir: null,
      minecraftVersion: version,
      fabricApiVersion: null,
    });

    expect(result.totalClasses).toBe(3);
    expect(fs.readFileSync(markerPath, 'utf-8').trim().split('\n')).toHaveLength(3);

    const manifest = readJsonFileOrNull<IndexManifest>(
      getVersionedIndexManifestPath(version),
      'test/worker-manifest'
    );
    expect(manifest?.indexerVersion).toBe('ast');
  }, 20000);
});

describe('Package Index Loader', () => {
  test('returns null for non-existent package', () => {
    const result = loadPackageIndex('minecraft', 'non.existent.package');
    expect(result).toBeNull();
  });
});
