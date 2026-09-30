/* ============================================================================
   Steven Piano - Android player for the self-playing acoustic piano
   Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
   Original author & creator: Steven Jin.
   Licensed under the MIT License (see LICENSE). This copyright and attribution
   notice MUST be preserved in all copies or substantial portions of the work.
   Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
   ============================================================================ */

import { API_CSP, SECURITY_HEADERS, HSTS } from '../shared/http';

/**
 * The page a browser gets for a piano whose tablet isn't connected (503): the panel's own paper and
 * ink, no script, and a look again every 30 seconds. Its one inline style is allowed by its hash.
 */

const STYLE = `
:root {
  --surface: #F4F1EA; --elevated: #FBF9F4; --hairline: #D8D3C8;
  --primary: #141414; --secondary: #5C5851; --tertiary: #6E6A62;
  color-scheme: light;
  --sans: system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
}
@media (prefers-color-scheme: dark) {
  :root { --surface: #0E0E0E; --elevated: #1A1A1A; --hairline: #2A2A2A; --primary: #F2F2F2; --secondary: #A3A3A3; --tertiary: #8A8A8A; color-scheme: dark; }
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--surface); color: var(--primary); font: 400 17px/24px var(--sans); -webkit-font-smoothing: antialiased; }
main { min-height: 100vh; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; padding: 32px 16px; text-align: center; }
h1 { margin: 0; font-size: 28px; line-height: 34px; font-weight: 500; letter-spacing: -0.3px; }
p { margin: 0; max-width: 32em; }
.eyebrow { font-size: 11px; line-height: 16px; font-weight: 500; letter-spacing: 1.3px; text-transform: uppercase; color: var(--tertiary); }
.note { font-size: 15px; line-height: 21px; color: var(--secondary); }
.meta { display: flex; align-items: center; gap: 8px; font-size: 13px; line-height: 18px; color: var(--secondary); letter-spacing: 0.3px; }
.dot { display: inline-block; width: 8px; height: 8px; border: 1.5px solid var(--tertiary); border-radius: 50%; }
`;

const PAGE = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<meta http-equiv="refresh" content="30">
<title>Steven Piano · offline</title>
<style>${STYLE}</style>
</head>
<body>
<main>
<p class="eyebrow">Steven Piano</p>
<h1>The piano is offline</h1>
<p class="note">Its tablet isn't connected just now. This page looks again every 30 seconds.</p>
<p class="meta"><span class="dot" aria-hidden="true"></span>Offline</p>
</main>
</body>
</html>
`;

let styleHash: Promise<string> | null = null;

async function hashOf(text: string): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text)));
  let binary = '';
  for (const b of digest) binary += String.fromCharCode(b);
  return btoa(binary);
}

/** The offline page's policy: its one style by hash, and nothing else. */
export async function offlineCsp(): Promise<string> {
  styleHash ??= hashOf(STYLE);
  return `default-src 'none'; style-src 'sha256-${await styleHash}'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`;
}

/** 503 for a piano whose tablet isn't here: JSON for the API (and anything not a page), the page for a page. */
export async function offline(api: boolean, method: string): Promise<Response> {
  const headers = new Headers({ 'Cache-Control': 'no-store', 'Retry-After': '30' });
  for (const [name, value] of SECURITY_HEADERS) headers.set(name, value);
  headers.set(HSTS[0], HSTS[1]);
  if (api || (method !== 'GET' && method !== 'HEAD')) {
    headers.set('Content-Type', 'application/json; charset=utf-8');
    headers.set('Content-Security-Policy', API_CSP);
    return new Response(JSON.stringify({ error: 'offline', message: 'The piano is offline.' }), { status: 503, headers });
  }
  headers.set('Content-Type', 'text/html; charset=utf-8');
  headers.set('Content-Security-Policy', await offlineCsp());
  return new Response(method === 'HEAD' ? null : PAGE, { status: 503, headers });
}

export const OFFLINE_PAGE = PAGE;
