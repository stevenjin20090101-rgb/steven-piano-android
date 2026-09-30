/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import { fileURLToPath } from 'node:url';
import { cloudflareTest, readD1Migrations } from '@cloudflare/vitest-pool-workers';
import { defineConfig } from 'vitest/config';

// The tests run inside the Workers runtime (workerd, through Miniflare), against the relay as
// wrangler.relay.jsonc describes it: its Durable Object, its rate limiters, a local D1 with the
// migrations applied (test/setup.ts). The console is exercised by calling its handler with the
// same bindings.
export default defineConfig(async () => {
  const migrations = await readD1Migrations(fileURLToPath(new URL('./migrations', import.meta.url)));
  return {
    plugins: [
      cloudflareTest({
        wrangler: { configPath: './wrangler.relay.jsonc' },
        miniflare: {
          bindings: { TEST_MIGRATIONS: migrations },
        },
      }),
    ],
    test: {
      include: ['test/**/*.test.ts'],
      setupFiles: ['./test/setup.ts'],
      testTimeout: 60_000,
      hookTimeout: 60_000,
    },
  };
});
