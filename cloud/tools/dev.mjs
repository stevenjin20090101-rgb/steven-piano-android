#!/usr/bin/env node
/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

// `npm run dev`: the relay on http://localhost:8787 and the console on http://localhost:8788, both
// under `wrangler dev` on this Mac only (127.0.0.1), sharing one local D1 (.wrangler/state), after
// the migrations are applied to it. The console skips Access here (DEV_BYPASS, localhost only). The
// Android emulator reaches the relay at 10.0.2.2:8787. Ctrl-C stops both.

import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const cloud = fileURLToPath(new URL('..', import.meta.url));
const wrangler = fileURLToPath(new URL('../node_modules/.bin/wrangler', import.meta.url));
const env = { ...process.env, WRANGLER_SEND_METRICS: 'false' };

const migrate = spawnSync(wrangler, ['d1', 'migrations', 'apply', 'steven-piano', '--local', '-c', 'wrangler.relay.jsonc'], {
  cwd: cloud,
  env: { ...env, CI: 'true' },
  stdio: 'inherit',
});
if (migrate.status !== 0) {
  console.error('The local database could not be migrated.');
  process.exit(migrate.status ?? 1);
}

const workers = [
  { name: 'relay  ', args: ['dev', '-c', 'wrangler.relay.jsonc', '--var', 'PUBLIC_HOST:localhost:8787'] },
  { name: 'console', args: ['dev', '-c', 'wrangler.console.jsonc', '--var', 'DEV_BYPASS:1', '--var', 'RELAY_URL:http://localhost:8787'] },
];

const children = [];

let stopping = false;
function stop() {
  if (stopping) return;
  stopping = true;
  for (const child of children) if (child.exitCode === null) child.kill('SIGINT');
  setTimeout(() => process.exit(0), 3000).unref();
}
process.on('SIGINT', stop);
process.on('SIGTERM', stop);

/** Starts one Worker; resolves when it is ready (or has stopped). */
function start({ name, args }) {
  return new Promise((ready) => {
    const child = spawn(wrangler, args, { cwd: cloud, env, stdio: ['ignore', 'pipe', 'pipe'] });
    children.push(child);
    const prefix = (stream, out) => {
      let rest = '';
      stream.on('data', (data) => {
        const lines = (rest + data.toString()).split('\n');
        rest = lines.pop() ?? '';
        for (const line of lines) {
          out.write(`${name} │ ${line}\n`);
          if (line.includes('Ready on')) ready();
        }
      });
    };
    prefix(child.stdout, process.stdout);
    prefix(child.stderr, process.stderr);
    child.on('exit', (code) => {
      console.log(`${name} │ stopped (${code ?? 'signal'})`);
      ready();
      stop();
    });
  });
}

// One after the other: two runtimes starting at once can reach for the same free port.
for (const worker of workers) {
  if (stopping) break;
  await start(worker);
}

if (!stopping) console.log('\nRelay:   http://localhost:8787  (the emulator: 10.0.2.2:8787)\nConsole: http://localhost:8788\nA stand-in tablet: node tools/fake-tablet.mjs --new\n');
