/**
 * Experimental Java index parser backed by Tree-sitter.
 *
 * The indexer only needs a shallow structural summary, so a concrete syntax
 * tree is enough: package, top-level type, direct fields, direct methods, and
 * declaration line ranges. Keep this backend behind MCDEV_TREE_SITTER_PARSER
 * until corpus comparisons show it matches the java-parser backend closely.
 */

import * as fs from 'fs';
import { createRequire } from 'module';
import type Parser from 'tree-sitter';
import { ClassKind, FieldInfo, MethodInfo } from '../utils/types.js';
import type { ParsedClass } from './parser.js';

type SyntaxNode = Parser.SyntaxNode;
type ParserConstructor = new () => Parser;

const require = createRequire(import.meta.url);
let ParserCtor: ParserConstructor | null = null;
let javaLanguage: unknown | null = null;
let sharedParser: Parser | null = null;

export function parseJavaFileTreeSitter(filePath: string): ParsedClass | null {
  const content = fs.readFileSync(filePath, 'utf-8');
  return parseJavaContentTreeSitter(content, filePath);
}

export function parseJavaContentTreeSitter(content: string, filePath: string): ParsedClass | null {
  let tree: Parser.Tree;
  try {
    tree = getParser().parse(content);
  } catch {
    return null;
  }
  const rootNode = readRootNode(tree);
  if (!rootNode || rootNode.hasError) return null;

  const packageName = readPackageName(rootNode);
  const top = findTopLevelType(rootNode);
  if (!top) return null;

  const className = childText(top, 'name');
  if (!className) return null;

  const kind = kindForNode(top);
  const relativePath = filePath.replace(/\\/g, '/');
  const fields = readFields(top, kind);
  const methods = readMethods(top);

  return {
    packageName,
    className,
    fullName: packageName ? `${packageName}.${className}` : className,
    info: {
      kind,
      super: readSuperClass(top, kind),
      interfaces: readInterfaces(top, kind),
      fields,
      methods,
      sourcePath: relativePath,
    },
  };
}

function getParser(): Parser {
  if (!sharedParser) {
    const { Parser, Java } = loadTreeSitter();
    sharedParser = new Parser();
    sharedParser.setLanguage(Java);
  }
  return sharedParser;
}

function loadTreeSitter(): { Parser: ParserConstructor; Java: unknown } {
  if (!ParserCtor || !javaLanguage) {
    ParserCtor = require('tree-sitter') as ParserConstructor;
    javaLanguage = require('tree-sitter-java') as unknown;
  }
  return { Parser: ParserCtor, Java: javaLanguage };
}

function readRootNode(tree: Parser.Tree | null | undefined): SyntaxNode | null {
  if (!tree) return null;

  try {
    const rootNode = tree.rootNode;
    if (rootNode) return rootNode;
  } catch {
    // Fall through to rootNodeWithOffset below.
  }

  try {
    return tree.rootNodeWithOffset(0, { row: 0, column: 0 }) ?? null;
  } catch {
    return null;
  }
}

function readPackageName(root: SyntaxNode): string {
  const decl = root.namedChildren.find(child => child.type === 'package_declaration');
  if (!decl) return '';
  const nameNode = decl.namedChildren.find(child =>
    child.type === 'scoped_identifier' ||
    child.type === 'identifier'
  );
  return nameNode?.text ?? '';
}

function findTopLevelType(root: SyntaxNode): SyntaxNode | null {
  return root.namedChildren.find(child =>
    child.type === 'class_declaration' ||
    child.type === 'interface_declaration' ||
    child.type === 'enum_declaration' ||
    child.type === 'record_declaration'
  ) ?? null;
}

function kindForNode(node: SyntaxNode): ClassKind {
  if (node.type === 'interface_declaration') return 'interface';
  if (node.type === 'enum_declaration') return 'enum';
  if (node.type === 'record_declaration') return 'record';
  return 'class';
}

function childText(node: SyntaxNode, fieldName: string): string | null {
  return node.childForFieldName(fieldName)?.text ?? null;
}

function readSuperClass(node: SyntaxNode, kind: ClassKind): string | null {
  if (kind === 'interface') return null;
  const superclass = node.childForFieldName('superclass');
  const typeNode = superclass?.namedChildren.find(isTypeNode);
  return typeNode ? simpleTypeName(typeNode.text) : null;
}

function readInterfaces(node: SyntaxNode, kind: ClassKind): string[] {
  const container = kind === 'interface'
    ? node.namedChildren.find(child => child.type === 'extends_interfaces')
    : node.namedChildren.find(child => child.type === 'super_interfaces');
  if (!container) return [];
  const typeList = container.namedChildren.find(child => child.type === 'type_list') ?? container;
  return typeList
    .namedChildren
    .filter(isTypeNode)
    .map(child => simpleTypeName(child.text))
    .filter(Boolean);
}

function readFields(typeNode: SyntaxNode, kind: ClassKind): FieldInfo[] {
  const body = readBody(typeNode);
  const fields: FieldInfo[] = [];

  if (kind === 'record') {
    const params = typeNode.namedChildren.find(child => child.type === 'formal_parameters');
    if (params) {
      for (const param of params.namedChildren.filter(child => child.type === 'formal_parameter')) {
        const field = readParamAsField(param);
        if (field) fields.push(field);
      }
    }
  }

  if (!body) return fields;
  const declarations = memberContainers(body).flatMap(container =>
    container.namedChildren.filter(child =>
      child.type === 'field_declaration' ||
      child.type === 'constant_declaration'
    )
  );

  for (const declaration of declarations) {
    const modifiers = readModifiers(declaration);
    if (declaration.type === 'constant_declaration') {
      for (const implicit of ['public', 'static', 'final']) {
        if (!modifiers.includes(implicit)) modifiers.push(implicit);
      }
    }
    const typeText = readDeclaredType(declaration);
    for (const variable of declaration.namedChildren.filter(child => child.type === 'variable_declarator')) {
      const name = childText(variable, 'name') ?? variable.namedChildren.find(child => child.type === 'identifier')?.text;
      if (name) fields.push({ name, type: typeText, modifiers: [...modifiers] });
    }
  }

  return fields;
}

function readParamAsField(param: SyntaxNode): FieldInfo | null {
  const name = childText(param, 'name') ?? param.namedChildren.find(child => child.type === 'identifier')?.text;
  const typeNode = param.childForFieldName('type') ?? param.namedChildren.find(isTypeNode);
  if (!name || !typeNode) return null;
  return {
    name,
    type: simpleTypeName(typeNode.text),
    modifiers: ['final'],
  };
}

function readMethods(typeNode: SyntaxNode): MethodInfo[] {
  const body = readBody(typeNode);
  if (!body) return [];

  return memberContainers(body)
    .flatMap(container => container.namedChildren.filter(child => child.type === 'method_declaration'))
    .map(readMethod)
    .filter((method): method is MethodInfo => method !== null);
}

function readMethod(node: SyntaxNode): MethodInfo | null {
  const name = childText(node, 'name');
  if (!name) return null;
  const typeNode = node.childForFieldName('type') ?? node.namedChildren.find(isTypeNode);
  const returnType = typeNode ? simpleTypeName(typeNode.text) : 'void';
  const paramsNode = node.childForFieldName('parameters') ?? node.namedChildren.find(child => child.type === 'formal_parameters');

  return {
    name,
    returnType,
    params: paramsNode ? readParams(paramsNode) : [],
    modifiers: readModifiers(node),
    lineStart: node.startPosition.row + 1,
    lineEnd: node.endPosition.row + 1,
  };
}

function readParams(paramsNode: SyntaxNode): { name: string; type: string }[] {
  return paramsNode.namedChildren
    .filter(child => child.type === 'formal_parameter' || child.type === 'spread_parameter')
    .map(param => {
      const name = childText(param, 'name') ?? param.namedChildren.find(child => child.type === 'identifier')?.text;
      const typeNode = param.childForFieldName('type') ?? param.namedChildren.find(isTypeNode);
      if (!name || !typeNode) return null;
      return { name, type: simpleTypeName(typeNode.text) };
    })
    .filter((param): param is { name: string; type: string } => param !== null);
}

function readBody(node: SyntaxNode): SyntaxNode | null {
  return node.namedChildren.find(child =>
    child.type === 'class_body' ||
    child.type === 'interface_body' ||
    child.type === 'enum_body'
  ) ?? null;
}

function memberContainers(body: SyntaxNode): SyntaxNode[] {
  return [
    body,
    ...body.namedChildren.filter(child => child.type === 'enum_body_declarations'),
  ];
}

function readModifiers(node: SyntaxNode): string[] {
  const modifiers = node.namedChildren.find(child => child.type === 'modifiers');
  if (!modifiers) return [];
  return modifiers.text
    .split(/\s+/)
    .map(text => text.trim())
    .filter(text => text.length > 0 && !text.startsWith('@'));
}

function readDeclaredType(node: SyntaxNode): string {
  const typeNode = node.childForFieldName('type') ?? node.namedChildren.find(isTypeNode);
  return typeNode ? simpleTypeName(typeNode.text) : '';
}

function isTypeNode(node: SyntaxNode): boolean {
  return isTypeNameNode(node) ||
    node.type === 'integral_type' ||
    node.type === 'floating_point_type' ||
    node.type === 'boolean_type' ||
    node.type === 'void_type' ||
    node.type === 'array_type' ||
    node.type === 'generic_type';
}

function isTypeNameNode(node: SyntaxNode): boolean {
  return node.type === 'type_identifier' ||
    node.type === 'scoped_type_identifier' ||
    node.type === 'identifier';
}

function simpleTypeName(type: string): string {
  const withoutGenerics = type.replace(/<.*>/s, '');
  const withoutArrays = withoutGenerics.replace(/[[\]]/g, '');
  const firstToken = withoutArrays.trim().split(/\s+/)[0] ?? '';
  const segments = firstToken.split('.');
  return segments[segments.length - 1] || firstToken;
}
