#!/usr/bin/env node
import { pbkdf2Sync, randomBytes } from 'node:crypto';
import { createInterface } from 'node:readline/promises';
import { stdin, stdout } from 'node:process';

const terminal = createInterface({ input: stdin, output: stdout });
try {
  let password = '';
  while (password.length < 12) {
    password = await terminal.question(password ? 'Password must be at least 12 characters. Try again: ' : 'Admin password (at least 12 characters): ');
  }
  const salt = randomBytes(16);
  // Cloudflare Workers WebCrypto caps PBKDF2 at 100,000 iterations.
  const iterations = 100_000;
  const hash = pbkdf2Sync(password, salt, iterations, 32, 'sha256');
  stdout.write(`${salt.toString('hex')}:${hash.toString('hex')}:${iterations}\n`);
} finally {
  terminal.close();
}
