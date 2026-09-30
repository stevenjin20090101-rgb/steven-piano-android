/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import { describe, expect, it } from 'vitest';
import * as consoleWorker from '../src/console/index';
import * as relayWorker from '../src/relay/index';

// workerd takes every export of a Worker's main module for an entrypoint: anything else stops it.
describe("the Workers' main modules", () => {
  it('export only their handlers (and the relay its Durable Object)', () => {
    expect(Object.keys(relayWorker).sort()).toEqual(['PianoRoom', 'default']);
    expect(Object.keys(consoleWorker)).toEqual(['default']);
  });
});
