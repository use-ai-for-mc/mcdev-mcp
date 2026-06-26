import * as fs from 'fs';
import { ClassInfo, FieldInfo, MethodInfo, ClassKind } from '../utils/types.js';
import { isEnvOn } from '../utils/env.js';
import { parseJavaFileAst, parseJavaContentAst } from './parser-ast.js';

export interface ParsedClass {
  packageName: string;
  className: string;
  fullName: string;
  info: ClassInfo;
}

/**
 * Choice of parser backend. The regex parser (`'regex'`) is the legacy
 * default and is brittle around modern Java (multi-line annotations, nested
 * generics, records, lambda-initialised fields, `}`-bearing string literals
 * — see .dream/review.md). The AST parser (`'ast'`) is `java-parser`-backed
 * (Chevrotain CST) and handles all of those.
 *
 * Toggle with `MCDEV_AST_PARSER=1` (or `true`). The AST parser remains opt-in
 * while users compare index quality against the regex baseline.
 */
export type ParserBackend = 'regex' | 'ast';

export function getParserBackend(): ParserBackend {
  return isEnvOn('MCDEV_AST_PARSER') ? 'ast' : 'regex';
}

export function parseJavaFile(filePath: string): ParsedClass | null {
  return parseJavaFileWithBackend(filePath, getParserBackend());
}

export function parseJavaFileWithBackend(filePath: string, backend: ParserBackend): ParsedClass | null {
  if (backend === 'ast') return parseJavaFileAst(filePath);
  const content = fs.readFileSync(filePath, 'utf-8');
  return parseJavaContentRegex(content, filePath);
}

function extractDeclarationBlock(content: string): string | null {
  const match = content.match(
    /(?:public|protected|private)?\s*(?:abstract|final|sealed)?\s*(?:class|interface|enum|record)\s+\w+[^{]*\{/s
  );
  return match ? match[0] : null;
}

function parseDeclaration(block: string): {
  className: string | null;
  kind: ClassKind;
  superClass: string | null;
  interfaces: string[];
} {
  const normalized = block.replace(/\s+/g, ' ').trim();

  const nameMatch = normalized.match(/(?:class|interface|enum|record)\s+(\w+)/);
  const className = nameMatch ? nameMatch[1] : null;

  let kind: ClassKind = 'class';
  if (/\binterface\b/.test(normalized)) kind = 'interface';
  else if (/\benum\b/.test(normalized)) kind = 'enum';
  else if (/\brecord\b/.test(normalized)) kind = 'record';

  let superClass: string | null = null;
  let interfaces: string[] = [];

  if (kind === 'interface') {
    const extendsMatch = normalized.match(/extends\s+([^{]+)/);
    if (extendsMatch) {
      interfaces = extendsMatch[1].split(',').map(s => cleanTypeName(s.trim())).filter(s => s);
    }
  } else {
    const extendsMatch = normalized.match(/extends\s+([^\s{<,]+(?:<[^>]+>)?)/);
    superClass = extendsMatch ? cleanTypeName(extendsMatch[1]) : null;

    const implementsMatch = normalized.match(/implements\s+([^{]+)/);
    if (implementsMatch) {
      interfaces = implementsMatch[1].split(',').map(s => cleanTypeName(s.trim())).filter(s => s);
    }
  }

  return { className, kind, superClass, interfaces };
}

export function parseJavaContent(content: string, filePath: string): ParsedClass | null {
  return parseJavaContentWithBackend(content, filePath, getParserBackend());
}

export function parseJavaContentWithBackend(
  content: string,
  filePath: string,
  backend: ParserBackend
): ParsedClass | null {
  if (backend === 'ast') return parseJavaContentAst(content, filePath);
  return parseJavaContentRegex(content, filePath);
}

function parseJavaContentRegex(content: string, filePath: string): ParsedClass | null {
  const lines = content.split('\n');

  const packageName = extractPackage(content);
  const declBlock = extractDeclarationBlock(content);

  if (!declBlock) return null;

  const { className, kind, superClass, interfaces } = parseDeclaration(declBlock);

  if (!className) return null;

  const fullName = packageName ? `${packageName}.${className}` : className;

  const fields = extractFields(content, lines);
  const methods = extractMethods(content, lines);

  const relativePath = filePath.replace(/\\/g, '/');

  return {
    packageName,
    className,
    fullName,
    info: {
      kind,
      super: superClass,
      interfaces,
      fields,
      methods,
      sourcePath: relativePath,
    },
  };
}

function extractPackage(content: string): string {
  const match = content.match(/package\s+([\w.]+)\s*;/);
  return match ? match[1] : '';
}

function cleanTypeName(type: string): string {
  return type.replace(/<.*>/, '').replace(/[[\]]/g, '').replace(/\s+/g, '').trim().split('.')[0] || type;
}

function extractFields(content: string, lines: string[]): FieldInfo[] {
  const fields: FieldInfo[] = [];
  
  const fieldRegex = /(?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?(\w+(?:<[^>]+>)?(?:\[\])*)\s+(\w+)\s*(?:=|;)/g;
  
  let match;
  while ((match = fieldRegex.exec(content)) !== null) {
    const fullMatch = match[0];
    const lineNum = content.substring(0, match.index).split('\n').length;
    const lineContent = lines[lineNum - 1] || '';
    
    if (lineContent.includes('(') || lineContent.includes(')')) continue;
    
    const modifiers = extractModifiers(fullMatch);
    
    fields.push({
      name: match[2],
      type: cleanTypeName(match[1]),
      modifiers,
    });
  }
  
  return fields;
}

function extractMethods(content: string, _lines: string[]): MethodInfo[] {
  const methods: MethodInfo[] = [];
  
  const methodRegex = /(?:@[\w.]+(?:\([^)]*\))?\s*)*(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+)?(?:abstract\s+)?(?:synchronized\s+)?(\w+(?:<[^>]+>)?(?:\[\])*)\s+(\w+)\s*\(([^)]*)\)\s*(?:throws\s+[\w.,\s]+)?\s*(?:\{|;)/g;
  
  let match;
  while ((match = methodRegex.exec(content)) !== null) {
    const returnType = match[1];
    const methodName = match[2];
    const paramsStr = match[3];
    
    if (returnType === 'class' || methodName === 'if' || methodName === 'while' || methodName === 'for' || methodName === 'switch' || methodName === 'catch') {
      continue;
    }
    
    if (methodName.match(/^[A-Z]/) && !returnType) {
      continue;
    }
    
    const lineStart = content.substring(0, match.index).split('\n').length;
    const lineEnd = findMethodEnd(content, match.index) || lineStart + 10;
    
    const modifiers = extractModifiers(match[0]);
    const params = parseParams(paramsStr);
    
    methods.push({
      name: methodName,
      returnType: cleanTypeName(returnType),
      params,
      modifiers,
      lineStart,
      lineEnd,
    });
  }
  
  return methods;
}

function extractModifiers(declaration: string): string[] {
  const modifiers: string[] = [];
  const modKeywords = ['public', 'protected', 'private', 'static', 'final', 'abstract', 'synchronized', 'volatile', 'transient', 'native'];
  
  for (const mod of modKeywords) {
    if (new RegExp(`\\b${mod}\\b`).test(declaration)) {
      modifiers.push(mod);
    }
  }
  
  return modifiers;
}

function parseParams(paramsStr: string): { name: string; type: string }[] {
  if (!paramsStr.trim()) return [];
  
  const params: { name: string; type: string }[] = [];
  const parts = splitParams(paramsStr);
  
  for (const part of parts) {
    const trimmed = part.trim();
    if (!trimmed) continue;
    
    const paramMatch = trimmed.match(/(?:final\s+)?(\w+(?:<[^>]+>)?(?:\[\])*)\s+(\w+)$/);
    if (paramMatch) {
      params.push({
        type: cleanTypeName(paramMatch[1]),
        name: paramMatch[2],
      });
    }
  }
  
  return params;
}

function splitParams(paramsStr: string): string[] {
  const result: string[] = [];
  let depth = 0;
  let current = '';
  
  for (const char of paramsStr) {
    if (char === '<' || char === '(') depth++;
    else if (char === '>' || char === ')') depth--;
    else if (char === ',' && depth === 0) {
      result.push(current);
      current = '';
      continue;
    }
    current += char;
  }
  
  if (current.trim()) result.push(current);
  
  return result;
}

function findMethodEnd(content: string, startIndex: number): number | null {
  let braceCount = 0;
  let foundOpen = false;
  
  for (let i = startIndex; i < content.length; i++) {
    if (content[i] === '{') {
      braceCount++;
      foundOpen = true;
    } else if (content[i] === '}') {
      braceCount--;
      if (foundOpen && braceCount === 0) {
        return content.substring(0, i).split('\n').length;
      }
    }
  }
  
  return null;
}
