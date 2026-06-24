#!/usr/bin/env node

import * as fs from 'fs';
import * as path from 'path';
import { performance } from 'perf_hooks';
import { glob } from 'glob';
import { parseJavaContentAst } from '../dist/indexer/parser-ast.js';
import { parseJavaContentTreeSitter } from '../dist/indexer/parser-tree-sitter.js';

const sourceDir = process.argv[2];
const limitArg = process.argv[3];
const limit = limitArg ? Number.parseInt(limitArg, 10) : Infinity;
const failOnMismatch = process.argv.includes('--fail-on-mismatch');

if (!sourceDir) {
  console.error('Usage: npm run compare:parsers -- <java-source-dir> [max-files] [--fail-on-mismatch]');
  process.exit(2);
}

const absoluteSourceDir = path.resolve(sourceDir);
const files = (await glob('**/*.java', {
  cwd: absoluteSourceDir,
  absolute: true,
  nodir: true,
})).sort((a, b) => a.localeCompare(b)).slice(0, Number.isFinite(limit) ? limit : undefined);

function summarize(parsed) {
  if (!parsed) return null;
  return {
    packageName: parsed.packageName,
    className: parsed.className,
    kind: parsed.info.kind,
    super: parsed.info.super,
    interfaces: parsed.info.interfaces,
    fields: parsed.info.fields.map(field => field.name).sort(),
    methods: parsed.info.methods.map(method => method.name).sort(),
  };
}

async function runBackend(name, parse) {
  const before = process.memoryUsage().rss;
  const started = performance.now();
  const summaries = new Map();
  let parsed = 0;
  let failed = 0;

  for (const file of files) {
    const source = fs.readFileSync(file, 'utf-8');
    const result = parse(source, file);
    const summary = summarize(result);
    if (summary) {
      parsed++;
      summaries.set(file, summary);
    } else {
      failed++;
    }
  }

  const elapsedMs = performance.now() - started;
  const after = process.memoryUsage().rss;
  return {
    name,
    parsed,
    failed,
    elapsedMs: Math.round(elapsedMs),
    rssDeltaMb: Math.round((after - before) / 1024 / 1024),
    summaries,
  };
}

const ast = await runBackend('java-parser', parseJavaContentAst);
global.gc?.();
const treeSitter = await runBackend('tree-sitter', parseJavaContentTreeSitter);

const mismatches = [];
for (const file of files) {
  const a = ast.summaries.get(file);
  const b = treeSitter.summaries.get(file);
  if (JSON.stringify(a) !== JSON.stringify(b)) {
    mismatches.push({
      file: path.relative(absoluteSourceDir, file),
      javaParser: a,
      treeSitter: b,
    });
  }
}

const output = {
  sourceDir: absoluteSourceDir,
  files: files.length,
  backends: [
    {
      name: ast.name,
      parsed: ast.parsed,
      failed: ast.failed,
      elapsedMs: ast.elapsedMs,
      rssDeltaMb: ast.rssDeltaMb,
    },
    {
      name: treeSitter.name,
      parsed: treeSitter.parsed,
      failed: treeSitter.failed,
      elapsedMs: treeSitter.elapsedMs,
      rssDeltaMb: treeSitter.rssDeltaMb,
    },
  ],
  mismatches: mismatches.slice(0, 25),
  mismatchCount: mismatches.length,
};

console.log(JSON.stringify(output, null, 2));
process.exit(failOnMismatch && mismatches.length > 0 ? 1 : 0);
