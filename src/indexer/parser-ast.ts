/**
 * AST-based Java parser, built on the `java-parser` package (Chevrotain CST).
 *
 * This is the opt-in replacement for the regex-based `parser.ts`. It mirrors
 * the same `ParsedClass` output shape so callers don't change; the dispatch
 * in `parser.ts` picks one or the other based on `MCDEV_AST_PARSER`.
 *
 * Behavioural differences from the regex parser worth knowing:
 *   - Multi-line annotations / extends / implements parse cleanly (the regex
 *     was line-anchored and silently lost data here).
 *   - `lineEnd` for methods is derived from the CST node's `location.endLine`,
 *     not by counting braces — string literals containing `{`/`}` no longer
 *     fool the parser.
 *   - Only direct members of the top-level type are indexed. The regex
 *     parser's global scan accidentally folded nested-type members into the
 *     outer type's list; that drift is gone.
 *   - Interface fields (`constantDeclaration` in the JLS grammar) and
 *     interface methods (`interfaceMethodDeclaration`) are now picked up
 *     — the regex parser only matched declarations with explicit modifiers,
 *     so interface defaults could go missing.
 */

import * as fs from 'fs';
import { parse, BaseJavaCstVisitorWithDefaults, type CstNode, type IToken } from 'java-parser';
import { FieldInfo, MethodInfo, ClassKind } from '../utils/types.js';
import type { ParsedClass } from './parser.js';

// ---------------------------------------------------------------------------
// Public entry points (signature-compatible with src/indexer/parser.ts).
// ---------------------------------------------------------------------------

export function parseJavaFileAst(filePath: string): ParsedClass | null {
    const content = fs.readFileSync(filePath, 'utf-8');
    return parseJavaContentAst(content, filePath);
}

export function parseJavaContentAst(content: string, filePath: string): ParsedClass | null {
    let pkgName: string;
    let top: TopLevelType | null;
    try {
        const cst = parse(content) as CstNode;
        const visitor = new JavaIndexVisitor();
        visitor.visit(cst);
        pkgName = visitor.pkgName;
        top = visitor.topLevel;
    } catch {
        // Match the regex parser's contract: on unparseable input, return
        // null rather than throwing. buildIndex() then skips this file
        // instead of aborting a whole-tree index build.
        return null;
    }

    if (!top || !top.className) return null;

    const relativePath = filePath.replace(/\\/g, '/');

    return {
        packageName: pkgName,
        className: top.className,
        fullName: pkgName ? `${pkgName}.${top.className}` : top.className,
        info: {
            kind: top.kind,
            super: top.superClass,
            interfaces: top.interfaces,
            fields: top.fields,
            methods: top.methods,
            sourcePath: relativePath,
        },
    };
}

// ---------------------------------------------------------------------------
// Visitor.
//
// The Chevrotain CST is verbose; we walk only the bits we need. The visitor
// keeps a depth counter so we can pick up the *outermost* type declaration
// and skip nested ones (matching the regex parser's intent — one type per
// file, the way Minecraft sources are organised).
// ---------------------------------------------------------------------------

interface TopLevelType {
    className: string;
    kind: ClassKind;
    superClass: string | null;
    interfaces: string[];
    fields: FieldInfo[];
    methods: MethodInfo[];
}

class JavaIndexVisitor extends BaseJavaCstVisitorWithDefaults {
    // Named `pkgName` (not `packageName`) because `packageName` collides with
    // the base visitor's same-named CST-node handler signature.
    pkgName: string = '';
    topLevel: TopLevelType | null = null;
    private depth: number = 0;

    constructor() {
        super();
        this.validateVisitor();
    }

    // -- package --------------------------------------------------------------

    packageDeclaration(ctx: Record<string, unknown>): void {
        // ctx.Identifier is IToken[] but the typed Ctx defs are heavy to wire
        // in here; the package decl is just dotted identifiers.
        const idents = (ctx as { Identifier?: IToken[] }).Identifier ?? [];
        this.pkgName = idents.map(t => t.image).join('.');
    }

    // -- type declarations ----------------------------------------------------

    normalClassDeclaration(ctx: Record<string, unknown>): void {
        this.handleType(ctx, 'class');
    }
    normalInterfaceDeclaration(ctx: Record<string, unknown>): void {
        this.handleType(ctx, 'interface');
    }
    enumDeclaration(ctx: Record<string, unknown>): void {
        this.handleType(ctx, 'enum');
    }
    recordDeclaration(ctx: Record<string, unknown>): void {
        this.handleType(ctx, 'record');
    }

    private handleType(ctx: Record<string, unknown>, kind: ClassKind): void {
        // Only the outermost (depth-0) declaration is captured. Nested types
        // would otherwise overwrite the top-level one — and their members
        // already live in their own files (Minecraft's decompiled sources
        // split nested classes into separate `.java` files per type).
        if (this.depth === 0) {
            const className = readTypeName(ctx);
            if (className) {
                const { superClass, interfaces } = readSupersAndInterfaces(ctx, kind);
                this.topLevel = {
                    className,
                    kind,
                    superClass,
                    interfaces,
                    fields: [],
                    methods: [],
                };
            }
        }

        this.depth++;
        try {
            // Visit the body to collect fields and methods of this type only.
            // The depth guard prevents recursive sub-types from polluting the
            // outermost type's lists.
            const body = (ctx.classBody as CstNode[] | undefined)
                ?? (ctx.interfaceBody as CstNode[] | undefined)
                ?? (ctx.enumBody as CstNode[] | undefined)
                ?? (ctx.recordBody as CstNode[] | undefined);
            if (body) this.visit(body);

            // Record components ARE fields conceptually (parameter list of the
            // canonical constructor). Pick them up so `record Foo(int a, String b)`
            // surfaces `a` and `b` in field listings.
            if (kind === 'record' && this.depth === 1) {
                const header = (ctx.recordHeader as CstNode[] | undefined)?.[0];
                if (header) this.visitRecordHeader(header);
            }
        } finally {
            this.depth--;
        }
    }

    private visitRecordHeader(headerNode: CstNode): void {
        if (!this.topLevel) return;
        const listNode = (headerNode.children as Record<string, CstNode[] | undefined>).recordComponentList?.[0];
        if (!listNode) return;
        const components = (listNode.children as Record<string, CstNode[] | undefined>).recordComponent ?? [];
        for (const comp of components) {
            const c = comp.children as Record<string, CstNode[] | IToken[] | undefined>;
            const idTok = (c.Identifier as IToken[] | undefined)?.[0];
            const typeNode = (c.unannType as CstNode[] | undefined)?.[0];
            if (idTok && typeNode) {
                this.topLevel.fields.push({
                    name: idTok.image,
                    type: typeTextOf(typeNode),
                    modifiers: ['final'], // record components are implicitly final
                });
            }
        }
    }

    // -- members (fields, methods, interface constants/methods) --------------
    //
    // The depth guard means we only record members of the *outermost* type.
    // Nested-class members hit these visitor methods too, but at depth > 1.

    fieldDeclaration(ctx: Record<string, unknown>): void {
        if (this.depth !== 1 || !this.topLevel) return;
        const modifiers = readModifiers(ctx.fieldModifier as CstNode[] | undefined);
        const typeText = typeTextOf((ctx.unannType as CstNode[])[0]);
        const list = (ctx.variableDeclaratorList as CstNode[] | undefined)?.[0];
        if (!list) return;
        const decls = (list.children as Record<string, CstNode[]>).variableDeclarator ?? [];
        for (const d of decls) {
            const idNode = (d.children as Record<string, CstNode[]>).variableDeclaratorId?.[0];
            const idTok = (idNode?.children as Record<string, IToken[]> | undefined)?.Identifier?.[0];
            if (idTok) {
                this.topLevel.fields.push({ name: idTok.image, type: typeText, modifiers });
            }
        }
    }

    constantDeclaration(ctx: Record<string, unknown>): void {
        // Interface fields use this CST node instead of fieldDeclaration.
        if (this.depth !== 1 || !this.topLevel) return;
        const modifiers = readModifiers(ctx.constantModifier as CstNode[] | undefined);
        // Interface fields are implicitly public/static/final per JLS.
        for (const implicit of ['public', 'static', 'final']) {
            if (!modifiers.includes(implicit)) modifiers.push(implicit);
        }
        const typeText = typeTextOf((ctx.unannType as CstNode[])[0]);
        const list = (ctx.variableDeclaratorList as CstNode[] | undefined)?.[0];
        if (!list) return;
        const decls = (list.children as Record<string, CstNode[]>).variableDeclarator ?? [];
        for (const d of decls) {
            const idNode = (d.children as Record<string, CstNode[]>).variableDeclaratorId?.[0];
            const idTok = (idNode?.children as Record<string, IToken[]> | undefined)?.Identifier?.[0];
            if (idTok) {
                this.topLevel.fields.push({ name: idTok.image, type: typeText, modifiers });
            }
        }
    }

    methodDeclaration(ctx: Record<string, unknown>): void {
        if (this.depth !== 1) return;
        this.collectMethod(ctx, 'methodModifier');
    }

    interfaceMethodDeclaration(ctx: Record<string, unknown>): void {
        if (this.depth !== 1) return;
        this.collectMethod(ctx, 'interfaceMethodModifier');
    }

    private collectMethod(ctx: Record<string, unknown>, modifierKey: string): void {
        if (!this.topLevel) return;

        const modifiers = readModifiers(ctx[modifierKey] as CstNode[] | undefined);
        const headerNode = (ctx.methodHeader as CstNode[] | undefined)?.[0];
        if (!headerNode) return;
        const header = headerNode.children as Record<string, CstNode[] | undefined>;

        // Return type: header.result.{unannType|Void}
        const resultNode = header.result?.[0];
        const returnType = resultNode ? returnTextOf(resultNode) : '';

        // Name + params from the declarator.
        const declarator = header.methodDeclarator?.[0];
        if (!declarator) return;
        const dc = declarator.children as Record<string, CstNode[] | IToken[] | undefined>;
        const nameTok = (dc.Identifier as IToken[] | undefined)?.[0];
        if (!nameTok) return;

        const params = readParams((dc.formalParameterList as CstNode[] | undefined)?.[0]);

        // Line range: nameTok for `lineStart`; for `lineEnd` we want the
        // closing brace of the method body (or the trailing semicolon on an
        // abstract method). The CST gives us this via the `methodBody`
        // child's `.location.endLine` — `ctx` itself is the children dict,
        // not the CstNode, so it has no `.location` property of its own.
        // Falling back through methodHeader → name token end keeps abstract
        // methods sensible even if methodBody is missing for any reason.
        const lineStart = nameTok.startLine ?? 0;
        const bodyEnd = (ctx.methodBody as CstNode[] | undefined)?.[0]?.location?.endLine;
        const headerEnd = headerNode?.location?.endLine;
        const lineEnd = bodyEnd ?? headerEnd ?? nameTok.endLine ?? lineStart;

        this.topLevel.methods.push({
            name: nameTok.image,
            returnType,
            params,
            modifiers,
            lineStart,
            lineEnd,
        });
    }
}

// ---------------------------------------------------------------------------
// CST helpers — these absorb the per-shape pickiness so the visitor reads
// cleanly. Each one's contract: given a CST subtree, return the bit we care
// about; tolerate missing optional children rather than throwing.
// ---------------------------------------------------------------------------

function readTypeName(ctx: Record<string, unknown>): string | null {
    const typeId = (ctx.typeIdentifier as CstNode[] | undefined)?.[0];
    const idTok = (typeId?.children as Record<string, IToken[]> | undefined)?.Identifier?.[0];
    return idTok ? idTok.image : null;
}

function readSupersAndInterfaces(
    ctx: Record<string, unknown>,
    kind: ClassKind,
): { superClass: string | null; interfaces: string[] } {
    if (kind === 'interface') {
        // Interfaces use `interfaceExtends` for their (possibly multiple)
        // parent interfaces. There's no notion of "implements".
        const ext = (ctx.interfaceExtends as CstNode[] | undefined)?.[0];
        return {
            superClass: null,
            interfaces: ext ? readInterfaceTypeList(ext) : [],
        };
    }
    // class / enum / record
    const ext = (ctx.classExtends as CstNode[] | undefined)?.[0];
    const impl = (ctx.classImplements as CstNode[] | undefined)?.[0];

    let superClass: string | null = null;
    if (ext) {
        const classTypeNode = (ext.children as Record<string, CstNode[]>).classType?.[0];
        if (classTypeNode) superClass = simpleNameOfClassType(classTypeNode);
    }

    const interfaces = impl ? readInterfaceTypeList(impl) : [];
    return { superClass, interfaces };
}

function readInterfaceTypeList(node: CstNode): string[] {
    const list = (node.children as Record<string, CstNode[]>).interfaceTypeList?.[0];
    if (!list) return [];
    const items = (list.children as Record<string, CstNode[]>).interfaceType ?? [];
    return items
        .map(it => {
            const classTypeNode = (it.children as Record<string, CstNode[]>).classType?.[0];
            return classTypeNode ? simpleNameOfClassType(classTypeNode) : null;
        })
        .filter((s): s is string => s !== null && s.length > 0);
}

function simpleNameOfClassType(classTypeNode: CstNode): string {
    // classType is a dotted name with optional type arguments at each segment.
    // The regex parser kept only the first segment (`Foo.Bar<Baz>` → `Foo`),
    // and matched on that everywhere downstream — replicate that exactly so
    // `findHierarchy` and existing tests behave the same.
    const ids = (classTypeNode.children as Record<string, IToken[] | undefined>).Identifier ?? [];
    if (ids.length === 0) return '';
    return ids[0].image;
}

const MODIFIER_TOKENS = [
    'Public', 'Protected', 'Private',
    'Static', 'Final', 'Abstract',
    'Synchronized', 'Volatile', 'Transient', 'Native',
    'Default',
];

function readModifiers(modifierNodes: CstNode[] | undefined): string[] {
    if (!modifierNodes) return [];
    const found: string[] = [];
    for (const node of modifierNodes) {
        const c = node.children as Record<string, IToken[] | undefined>;
        for (const key of MODIFIER_TOKENS) {
            if (c[key] && c[key]!.length > 0) {
                found.push(key.toLowerCase());
            }
        }
    }
    // Dedupe while preserving order — the same keyword can show up across
    // multiple modifier CST nodes in pathological grammars.
    return Array.from(new Set(found));
}

function typeTextOf(unannType: CstNode): string {
    // Match the regex parser's `cleanTypeName`: strip generics + brackets,
    // keep the first dotted segment. Walk the subtree, concatenating
    // identifier tokens at the surface level.
    const collected = collectIdentifiers(unannType);
    if (collected.length === 0) return '';
    return collected[0];
}

function collectIdentifiers(node: CstNode | undefined): string[] {
    if (!node) return [];
    const out: string[] = [];
    walk(node);
    return out;
    function walk(n: CstNode): void {
        const c = n.children as Record<string, (CstNode | IToken)[] | undefined>;
        // Primitive types come as named tokens (`Int`, `Boolean`, etc.) at this layer.
        const primitiveKeys = ['Boolean', 'Byte', 'Short', 'Int', 'Long', 'Char', 'Float', 'Double', 'Void'];
        for (const key of primitiveKeys) {
            const tok = c[key]?.[0] as IToken | undefined;
            if (tok && 'image' in tok) {
                out.push(tok.image);
                return;
            }
        }
        // Otherwise grab the first Identifier we can reach.
        const idTok = c.Identifier?.[0] as IToken | undefined;
        if (idTok && 'image' in idTok) {
            out.push(idTok.image);
            return;
        }
        // Recurse into the first non-token child until we find an identifier.
        for (const key of Object.keys(c)) {
            const arr = c[key];
            if (!arr) continue;
            for (const child of arr) {
                if (child && typeof child === 'object' && 'children' in child) {
                    walk(child as CstNode);
                    if (out.length > 0) return;
                }
            }
        }
    }
}

function returnTextOf(resultNode: CstNode): string {
    const c = resultNode.children as Record<string, (CstNode | IToken)[] | undefined>;
    // result := unannType | Void
    if (c.Void && c.Void.length > 0) return 'void';
    const u = c.unannType?.[0];
    return u && 'children' in u ? typeTextOf(u as CstNode) : '';
}

function readParams(listNode: CstNode | undefined): { name: string; type: string }[] {
    if (!listNode) return [];
    const params: { name: string; type: string }[] = [];
    const items = (listNode.children as Record<string, CstNode[] | undefined>).formalParameter ?? [];
    for (const p of items) {
        const pc = p.children as Record<string, CstNode[] | undefined>;
        const reg = pc.variableParaRegularParameter?.[0];
        const vari = pc.variableArityParameter?.[0];
        if (reg) {
            const rc = reg.children as Record<string, CstNode[] | undefined>;
            const type = typeTextOf(rc.unannType![0]);
            const idNode = rc.variableDeclaratorId?.[0];
            const idTok = (idNode?.children as Record<string, IToken[]> | undefined)?.Identifier?.[0];
            if (idTok) params.push({ name: idTok.image, type });
        } else if (vari) {
            // varargs: `Type... name`
            const vc = vari.children as Record<string, CstNode[] | IToken[] | undefined>;
            const type = typeTextOf((vc.unannType as CstNode[])[0]);
            const idTok = (vc.Identifier as IToken[] | undefined)?.[0];
            if (idTok) params.push({ name: idTok.image, type });
        }
    }
    return params;
}
