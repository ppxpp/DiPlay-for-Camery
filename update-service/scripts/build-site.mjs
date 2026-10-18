import { cpSync, existsSync, rmSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const site = join(root, 'site');
const output = join(root, 'dist');
const staticOverrides = join(root, 'public');

function run(command, args, cwd) {
  const result = spawnSync(command, args, { cwd, stdio: 'inherit' });
  if (result.status !== 0) process.exit(result.status ?? 1);
}

if (!existsSync(join(site, 'node_modules'))) {
  run('npm', ['ci'], site);
}

run('npm', ['run', 'build'], site);

if (!existsSync(join(site, 'dist', 'index.html'))) {
  console.error('Site build did not produce index.html');
  process.exit(1);
}

rmSync(output, { recursive: true, force: true });
cpSync(join(site, 'dist'), output, { recursive: true });
cpSync(staticOverrides, output, { recursive: true });

console.log(`Assembled Pages output in ${output}`);
