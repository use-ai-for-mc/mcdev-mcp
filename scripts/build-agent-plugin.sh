#!/usr/bin/env bash
#
# Build an Agent Plugins package (https://agent-plugins.org) for mcdev-mcp.
#
# Agent Plugins is a directory-based portable package format: a root
# plugin.json plus fixed component locations (skills/, mcp.json). This script
# stages the same self-contained server tree that build-mcpb.sh produces and
# lays out the Agent Plugins manifest + MCP config on top of it, WITHOUT
# touching or replacing the Anthropic MCPB packaging (manifest.json /
# scripts/build-mcpb.sh). Both formats can be shipped side-by-side from the
# same source.
#
# Usage:
#   scripts/build-agent-plugin.sh                        # default output dir
#   scripts/build-agent-plugin.sh path/to/output-dir   # custom output dir
#
# Output: <output>/plugin.json  + mcp.json + skills/ + dist/ + node_modules
#   (a fully self-contained Agent Plugins package you can zip/install)
#
# Requires: node >= 18, npm. Same tooling as build-mcpb.sh.
#
# Layout note: the Node server needs `node dist/cli.js serve` to run. In the
# package, `dist/` must sit next to `plugin.json` (the package root), and the
# `command`/`args` in mcp.json resolve plugin-relative via `./`. resources/
# ships alongside dist/ because the runtime reads those files from disk.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

VERSION="$(node -p 'require("./package.json").version')"

OUTPUT="${1:-dist-agent-plugin}"
rm -rf "$OUTPUT"
mkdir -p "$OUTPUT"

echo ">> Building TypeScript..."
npm run build

echo ">> Validating plugin.json..."
node -e '
  const fs = require("fs");
  const p = JSON.parse(fs.readFileSync("plugin.json", "utf8"));
  if (!p.$schema || !p.name) throw new Error("plugin.json missing $schema or name");
  if (!/^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$/.test(p.name)) throw new Error("plugin name invalid");
  console.log("    OK:", p.name, p.version || "(no version)");
  const m = JSON.parse(fs.readFileSync("mcp.json", "utf8"));
  if (!m.$schema || !m.mcpServers) throw new Error("mcp.json missing $schema or mcpServers");
  for (const [k, s] of Object.entries(m.mcpServers)) {
    if (!s.type || !s.command) throw new Error("mcp server " + k + " missing type/command");
  }
  console.log("    mcp.json OK:", Object.keys(m.mcpServers).join(", "));
'

echo ">> Staging files in $OUTPUT..."
cp plugin.json mcp.json "$OUTPUT/"
cp package.json "$OUTPUT/"
[[ -f README.md ]] && cp README.md "$OUTPUT/"
[[ -f LICENSE ]]  && cp LICENSE  "$OUTPUT/"
cp -R dist         "$OUTPUT/dist"
[[ -d resources ]] && cp -R resources "$OUTPUT/resources"
[[ -d skills ]]    && cp -R skills    "$OUTPUT/skills"

echo ">> Installing production dependencies..."
(
  cd "$OUTPUT"
  npm install --omit=dev --no-fund --no-audit --loglevel=error
)

echo
echo "✓ Built $OUTPUT ($(du -sh "$OUTPUT" | cut -f1))"
echo "  Contains: plugin.json mcp.json skills/ dist/ resources/"