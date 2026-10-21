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
  const hash = pbkdf2Sync(password, salt, 210_000, 32, 'sha256');
  stdout.write(`${salt.toString('hex')}:${hash.toString('hex')}:210000\n`);
} finally {
  terminal.close();
}
