import { describe, expect, it } from '@jest/globals';
import { parseJavaContent } from '../src/indexer/parser.js';
import { parseJavaContentAst } from '../src/indexer/parser-ast.js';
import { parseJavaContentTreeSitter } from '../src/indexer/parser-tree-sitter.js';

describe('Tree-sitter parser backend', () => {
  it('extracts the same top-level class summary as the java-parser AST backend', () => {
    const src = `
package net.minecraft.network.chat;

@Deprecated
public final class Tricky<T extends Comparable<? super T>>
        extends AbstractMap<String, java.util.List<? extends T>>
        implements Cloneable, Serializable {
    private static final int LIMIT = 256;
    public int demo(String name) { return LIMIT; }
}
`;

    const tree = parseJavaContentTreeSitter(src, '/x/Tricky.java');
    const ast = parseJavaContentAst(src, '/x/Tricky.java');

    expect(tree).not.toBeNull();
    expect(ast).not.toBeNull();
    expect(tree?.packageName).toBe(ast?.packageName);
    expect(tree?.className).toBe(ast?.className);
    expect(tree?.info.kind).toBe(ast?.info.kind);
    expect(tree?.info.super).toBe(ast?.info.super);
    expect(tree?.info.interfaces).toEqual(ast?.info.interfaces);
    expect(tree?.info.fields.map(field => field.name)).toEqual(['LIMIT']);
    expect(tree?.info.methods.map(method => method.name)).toEqual(['demo']);
  });

  it('extracts record components and interface members', () => {
    const record = parseJavaContentTreeSitter(`
package x;
public record Pair(int left, String right) {}
`, '/x/Pair.java');
    expect(record?.info.kind).toBe('record');
    expect(record?.info.fields.map(field => field.name)).toEqual(['left', 'right']);

    const iface = parseJavaContentTreeSitter(`
package x;
public interface Named extends Base, Marker {
    int LIMIT = 1;
    default String name() { return "named"; }
}
`, '/x/Named.java');
    expect(iface?.info.kind).toBe('interface');
    expect(iface?.info.interfaces).toEqual(['Base', 'Marker']);
    expect(iface?.info.fields.map(field => field.name)).toEqual(['LIMIT']);
    expect(iface?.info.methods.map(method => method.name)).toEqual(['name']);
  });

  it('can be selected through MCDEV_TREE_SITTER_PARSER', () => {
    const oldTree = process.env.MCDEV_TREE_SITTER_PARSER;
    const oldAst = process.env.MCDEV_AST_PARSER;
    process.env.MCDEV_TREE_SITTER_PARSER = '1';
    process.env.MCDEV_AST_PARSER = '1';
    try {
      const result = parseJavaContent(`
package selected;
public class Backend { public void run() {} }
`, '/Backend.java');
      expect(result?.packageName).toBe('selected');
      expect(result?.className).toBe('Backend');
    } finally {
      if (oldTree === undefined) delete process.env.MCDEV_TREE_SITTER_PARSER;
      else process.env.MCDEV_TREE_SITTER_PARSER = oldTree;
      if (oldAst === undefined) delete process.env.MCDEV_AST_PARSER;
      else process.env.MCDEV_AST_PARSER = oldAst;
    }
  });

  it('returns null on parse errors', () => {
    expect(parseJavaContentTreeSitter('this is { not valid Java }', '/Bad.java')).toBeNull();
  });
});
