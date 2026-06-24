/**
 * Tests that target the AST parser specifically — the failure modes the
 * dream report (.dream/review.md, the regex-parser theme) enumerated.
 * These all PARSE under java-parser but the regex parser miscounts /
 * misses entries on at least some of them.
 *
 * The existing parser.test.ts and parser-declaration.test.ts cover the
 * basic happy paths and run against whichever backend MCDEV_AST_PARSER
 * picks; this file is AST-only and forces the env on/off explicitly so
 * the suite is self-contained.
 */

import { afterEach, beforeEach, describe, expect, it } from '@jest/globals';
import { parseJavaContentAst } from '../src/indexer/parser-ast.js';

describe('AST parser — dream-report failure modes', () => {
    it('records and their components are picked up as kind=record + fields', () => {
        const src = `
package net.minecraft.network.chat;

public record ChatType(
    ChatTypeDecoration chat,
    ChatTypeDecoration narration
) {
}
`;
        const r = parseJavaContentAst(src, '/x/ChatType.java');
        expect(r).not.toBeNull();
        expect(r?.info.kind).toBe('record');
        expect(r?.info.fields.map(f => f.name)).toEqual(['chat', 'narration']);
        // Record components are implicitly final.
        expect(r?.info.fields[0].modifiers).toContain('final');
    });

    it('handles multi-line annotations + nested generics in the declaration line', () => {
        const src = `
package x;

@Deprecated(
    since = "1.21",
    forRemoval = false
)
public final class Tricky<T extends Comparable<? super T> & Cloneable>
        extends AbstractMap<String, java.util.List<? extends T>>
        implements Cloneable, Serializable {
    public int demo() { return 1; }
}
`;
        const r = parseJavaContentAst(src, '/x/Tricky.java');
        expect(r).not.toBeNull();
        expect(r?.className).toBe('Tricky');
        expect(r?.info.kind).toBe('class');
        expect(r?.info.super).toBe('AbstractMap');
        expect(r?.info.interfaces).toEqual(['Cloneable', 'Serializable']);
        expect(r?.info.methods.find(m => m.name === 'demo')).toBeDefined();
    });

    it('lambda-initialised fields are classified as fields, not methods', () => {
        const src = `
package x;
public class Lambdas {
    private static final Runnable HOOK = () -> { System.out.println("hi"); };
    public void real() {}
}
`;
        const r = parseJavaContentAst(src, '/x/Lambdas.java');
        expect(r).not.toBeNull();
        expect(r?.info.fields.find(f => f.name === 'HOOK')).toBeDefined();
        expect(r?.info.methods.map(m => m.name)).toEqual(['real']);
    });

    it("doesn't mistake braces inside string literals for method bodies", () => {
        const src = `
package x;
public class StringBraces {
    public String dangerous() {
        return "{ \\"open\\": 1, \\"close\\": } }";
    }
}
`;
        const r = parseJavaContentAst(src, '/x/StringBraces.java');
        expect(r).not.toBeNull();
        const m = r?.info.methods.find(x => x.name === 'dangerous');
        expect(m).toBeDefined();
        // lineEnd should land on the real closing brace, not inside the string.
        expect(m?.lineEnd).toBeGreaterThan(m?.lineStart ?? 0);
        expect(m?.lineEnd).toBeLessThanOrEqual(6);
    });

    it('sealed interfaces with permits parse, kind is interface', () => {
        const src = `
package x;
public sealed interface Shape permits Circle, Square {
    double area();
}
`;
        const r = parseJavaContentAst(src, '/x/Shape.java');
        expect(r).not.toBeNull();
        expect(r?.info.kind).toBe('interface');
        expect(r?.info.methods.map(m => m.name)).toEqual(['area']);
    });

    it('interface constants and default methods are extracted', () => {
        const src = `
package x;
public interface WithConstants {
    int LIMIT = 256;
    String NAME = "default";

    String getName();

    default int doubled() {
        return LIMIT * 2;
    }
}
`;
        const r = parseJavaContentAst(src, '/x/WithConstants.java');
        expect(r).not.toBeNull();
        expect(r?.info.fields.map(f => f.name).sort()).toEqual(['LIMIT', 'NAME']);
        // Interface constants are implicitly public/static/final per JLS.
        const limit = r?.info.fields.find(f => f.name === 'LIMIT');
        expect(limit?.modifiers).toEqual(expect.arrayContaining(['public', 'static', 'final']));
        expect(r?.info.methods.map(m => m.name).sort()).toEqual(['doubled', 'getName']);
        // The default keyword surfaces as a modifier on the default method.
        const doubled = r?.info.methods.find(m => m.name === 'doubled');
        expect(doubled?.modifiers).toContain('default');
    });

    it('varargs parameters are kept and the type is the element type', () => {
        const src = `
package x;
public class Varargs {
    public static <T> java.util.List<T> of(T... items) { return null; }
}
`;
        const r = parseJavaContentAst(src, '/x/Varargs.java');
        expect(r).not.toBeNull();
        const of = r?.info.methods.find(m => m.name === 'of');
        expect(of?.params).toEqual([{ name: 'items', type: 'T' }]);
    });

    it("doesn't fold nested-type members into the outer type", () => {
        const src = `
package x;
public class Outer {
    public int outerField;
    public void outerMethod() {}

    public static class Inner {
        public int innerField;
        public void innerMethod() {}
    }
}
`;
        const r = parseJavaContentAst(src, '/x/Outer.java');
        expect(r).not.toBeNull();
        expect(r?.className).toBe('Outer');
        expect(r?.info.fields.map(f => f.name)).toEqual(['outerField']);
        expect(r?.info.methods.map(m => m.name)).toEqual(['outerMethod']);
    });

    it('parses interface extending multiple parents (multi-line)', () => {
        const src = `
package net.minecraft.network.chat;
public interface Component
        extends Message,
                FormattedText {
}
`;
        const r = parseJavaContentAst(src, '/x/Component.java');
        expect(r).not.toBeNull();
        expect(r?.info.kind).toBe('interface');
        expect(r?.info.super).toBeNull();
        expect(r?.info.interfaces).toEqual(['Message', 'FormattedText']);
    });

    it('returns null on unparseable input instead of throwing', () => {
        // Match the regex parser contract: unparseable input → null,
        // not a thrown error, so buildIndex() can skip bad files.
        const r = parseJavaContentAst('this is { not valid Java }', '/x/Bad.java');
        expect(r).toBeNull();
    });

    it('reports realistic line ranges for methods (lineStart < lineEnd)', () => {
        const src = `
package x;
public class Lines {
    public int small() { return 1; }

    public int big(int a) {
        int x = a;
        for (int i = 0; i < 10; i++) {
            x += i;
        }
        return x;
    }
}
`;
        const r = parseJavaContentAst(src, '/x/Lines.java');
        expect(r).not.toBeNull();
        const small = r?.info.methods.find(m => m.name === 'small');
        const big = r?.info.methods.find(m => m.name === 'big');
        expect(small?.lineStart).toBeGreaterThan(0);
        expect(small?.lineEnd).toBeGreaterThanOrEqual(small?.lineStart ?? 0);
        expect(big?.lineEnd).toBeGreaterThan(big?.lineStart ?? 0);
        // big() runs from `public int big(...) {` to its closing brace, which
        // is six lines (start line + 5 body lines + close brace), so lineEnd
        // should be strictly greater than small().lineEnd.
        expect((big?.lineEnd ?? 0)).toBeGreaterThan((small?.lineEnd ?? 0));
    });
});

// ---------------------------------------------------------------------------
// Parametrised: every test the legacy parser test file runs should ALSO pass
// when the AST parser is the backend. We don't import parseJavaContent here
// — that would bake in whichever env was set at module load — but flipping
// the env around per-test forces the dispatch each call.
// ---------------------------------------------------------------------------

describe('AST parser ↔ regex parser parity (selected cases)', () => {
    const ORIG = process.env.MCDEV_AST_PARSER;
    afterEach(() => {
        if (ORIG === undefined) delete process.env.MCDEV_AST_PARSER;
        else process.env.MCDEV_AST_PARSER = ORIG;
    });

    async function parseBoth(src: string, file: string): Promise<{ ast: unknown; regex: unknown }> {
        // Force-reload parser.ts per call because `getParserBackend` reads
        // the env at call time — straightforward.
        const mod = await import('../src/indexer/parser.js');
        process.env.MCDEV_AST_PARSER = '1';
        const ast = mod.parseJavaContent(src, file);
        delete process.env.MCDEV_AST_PARSER;
        const regex = mod.parseJavaContent(src, file);
        return { ast, regex };
    }

    it('agrees on package + className + kind for simple classes', async () => {
        const src = `
package net.minecraft.test;
public class Simple {
    public int x;
    public void hello() {}
}
`;
        const { ast, regex } = await parseBoth(src, '/Simple.java');
        const a = ast as { packageName: string; className: string; info: { kind: string } };
        const r = regex as { packageName: string; className: string; info: { kind: string } };
        expect(a.packageName).toBe(r.packageName);
        expect(a.className).toBe(r.className);
        expect(a.info.kind).toBe(r.info.kind);
    });

    it('agrees on super + interfaces for a class with both', async () => {
        const src = `
package x;
public class C extends Base implements Foo, Bar {}
`;
        const { ast, regex } = await parseBoth(src, '/C.java');
        const a = ast as { info: { super: string | null; interfaces: string[] } };
        const r = regex as { info: { super: string | null; interfaces: string[] } };
        expect(a.info.super).toBe(r.info.super);
        expect(a.info.interfaces).toEqual(r.info.interfaces);
    });
});

describe('AST parser — memory shape', () => {
    it('does not retain rawContent on parse results', () => {
        const src = `
package net.minecraft.test;

public class MemoryShape {
    private int value;
    public void run() {}
}
`;
        const r = parseJavaContentAst(src, '/MemoryShape.java');
        expect(r).not.toBeNull();
        expect(r?.info.fields.length).toBeGreaterThan(0);
        expect(r?.info.methods.length).toBeGreaterThan(0);
        expect(Object.prototype.hasOwnProperty.call(r, 'rawContent')).toBe(false);
    });
});
