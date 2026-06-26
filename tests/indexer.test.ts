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
  const ORIG_HEAP = process.env.MCDEV_INDEX_WORKER_HEAP_MB;
  const ORIG_RETRY_HEAP = process.env.MCDEV_INDEX_WORKER_RETRY_HEAP_MB;
  const ORIG_WORKER_PATH = process.env.MCDEV_INDEX_PARSE_WORKER_PATH;
  const ORIG_MARKER = process.env.MCDEV_INDEX_WORKER_MARKER;
  const ORIG_SINGLE_FILE_FALLBACK = process.env.MCDEV_INDEX_SINGLE_FILE_FALLBACK;
  const ORIG_ARGV_CAPTURE = process.env.MCDEV_ARGV_CAPTURE;
  const ORIG_EXEC_ARGV = [...process.execArgv];
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
    if (ORIG_HEAP === undefined) delete process.env.MCDEV_INDEX_WORKER_HEAP_MB;
    else process.env.MCDEV_INDEX_WORKER_HEAP_MB = ORIG_HEAP;
    if (ORIG_RETRY_HEAP === undefined) delete process.env.MCDEV_INDEX_WORKER_RETRY_HEAP_MB;
    else process.env.MCDEV_INDEX_WORKER_RETRY_HEAP_MB = ORIG_RETRY_HEAP;
    if (ORIG_WORKER_PATH === undefined) delete process.env.MCDEV_INDEX_PARSE_WORKER_PATH;
    else process.env.MCDEV_INDEX_PARSE_WORKER_PATH = ORIG_WORKER_PATH;
    if (ORIG_MARKER === undefined) delete process.env.MCDEV_INDEX_WORKER_MARKER;
    else process.env.MCDEV_INDEX_WORKER_MARKER = ORIG_MARKER;
    if (ORIG_SINGLE_FILE_FALLBACK === undefined) delete process.env.MCDEV_INDEX_SINGLE_FILE_FALLBACK;
    else process.env.MCDEV_INDEX_SINGLE_FILE_FALLBACK = ORIG_SINGLE_FILE_FALLBACK;
    if (ORIG_ARGV_CAPTURE === undefined) delete process.env.MCDEV_ARGV_CAPTURE;
    else process.env.MCDEV_ARGV_CAPTURE = ORIG_ARGV_CAPTURE;
    process.execArgv.splice(0, process.execArgv.length, ...ORIG_EXEC_ARGV);
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

  test('falls back to a lightweight parser when a single-file AST worker keeps crashing', async () => {
    const workerPath = path.join(tempDir, 'crashing-worker.cjs');
    fs.writeFileSync(workerPath, `
process.on('message', () => {
  process.exit(134);
});
`);
    process.env.MCDEV_INDEX_PARSE_WORKER_PATH = workerPath;
    process.env.MCDEV_INDEX_WORKER_HEAP_MB = '128';
    process.env.MCDEV_INDEX_WORKER_RETRY_HEAP_MB = '256';
    process.env.MCDEV_INDEX_SINGLE_FILE_FALLBACK = 'regex';
    const markerPath = path.join(tempDir, 'fallback-worker-used.txt');
    process.env.MCDEV_INDEX_WORKER_MARKER = markerPath;

    const pkgDir = path.join(tempDir, 'fallback');
    fs.mkdirSync(pkgDir, { recursive: true });
    fs.writeFileSync(path.join(pkgDir, 'Fallback.java'), `
package worker.fallback;

public class Fallback {
    public int value() { return 1; }
}
`);

    const version = `ast-worker-fallback-${Date.now()}`;
    const result = await buildIndex({
      minecraftSourceDir: tempDir,
      fabricApiSourceDir: null,
      minecraftVersion: version,
      fabricApiVersion: null,
    });

    expect(result.totalClasses).toBe(1);
    const indexed = loadPackageIndex('minecraft', 'worker.fallback', version);
    expect(indexed?.classes.Fallback).toBeDefined();
    expect(fs.readFileSync(markerPath, 'utf-8').trim().split('\n')).toHaveLength(1);
  }, 20000);

  test('does not pass parent heap percentage flags to parse workers', async () => {
    const workerPath = path.join(tempDir, 'argv-worker.cjs');
    const argvPath = path.join(tempDir, 'argv.json');
    fs.writeFileSync(workerPath, `
const fs = require('fs');
process.on('message', () => {
  fs.writeFileSync(process.env.MCDEV_ARGV_CAPTURE, JSON.stringify(process.execArgv));
  process.send({ type: 'result', parsed: [] }, () => process.exit(0));
});
`);
    process.env.MCDEV_INDEX_PARSE_WORKER_PATH = workerPath;
    process.env.MCDEV_ARGV_CAPTURE = argvPath;
    process.env.MCDEV_INDEX_WORKER_HEAP_MB = '256';
    process.execArgv.push('--max-old-space-size-percentage=75');
    process.execArgv.push('--max-old-space-size=12345');

    const pkgDir = path.join(tempDir, 'argv');
    fs.mkdirSync(pkgDir, { recursive: true });
    fs.writeFileSync(path.join(pkgDir, 'Argv.java'), `
package worker.argv;

public class Argv {}
`);

    await buildIndex({
      minecraftSourceDir: tempDir,
      fabricApiSourceDir: null,
      minecraftVersion: `ast-worker-argv-${Date.now()}`,
      fabricApiVersion: null,
    });

    const childArgv = JSON.parse(fs.readFileSync(argvPath, 'utf-8')) as string[];
    expect(childArgv).toContain('--max-old-space-size=256');
    expect(childArgv).not.toContain('--max-old-space-size=12345');
    expect(childArgv.filter(arg => arg.startsWith('--max-old-space-size-percentage'))).toEqual([]);
  }, 20000);
});

describe('Package Index Loader', () => {
  test('returns null for non-existent package', () => {
    const result = loadPackageIndex('minecraft', 'non.existent.package');
    expect(result).toBeNull();
  });
});
