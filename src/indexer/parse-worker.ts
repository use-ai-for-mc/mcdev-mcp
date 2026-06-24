import { parseJavaFile, type ParsedClass } from './parser.js';

interface ParseRequest {
  type: 'parse';
  files: string[];
}

interface ParseResult {
  type: 'result';
  parsed: ParsedClass[];
}

interface ParseFailure {
  type: 'error';
  error: string;
}

function sendAndExit(message: ParseResult | ParseFailure, exitCode: number): void {
  if (process.send) {
    process.send(message, () => {
      process.exit(exitCode);
    });
    return;
  }
  process.exit(exitCode);
}

process.on('message', (message: ParseRequest) => {
  if (!message || message.type !== 'parse' || !Array.isArray(message.files)) {
    sendAndExit({ type: 'error', error: 'Invalid parse worker request.' }, 1);
    return;
  }

  try {
    const parsed: ParsedClass[] = [];
    for (const file of message.files) {
      const result = parseJavaFile(file);
      if (result) parsed.push(result);
    }
    sendAndExit({ type: 'result', parsed }, 0);
  } catch (error) {
    sendAndExit({
      type: 'error',
      error: error instanceof Error ? error.stack ?? error.message : String(error),
    }, 1);
  }
});
