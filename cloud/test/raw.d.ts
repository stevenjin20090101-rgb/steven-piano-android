/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

// A file's text, as Vite imports it with `?raw` (hygiene.test.ts reads the deployed configurations so).
declare module '*?raw' {
  const text: string;
  export default text;
}
