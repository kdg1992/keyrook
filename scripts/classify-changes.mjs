// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
// Classifies the changes between BASE_SHA and GITHUB_SHA for workflows that skip expensive steps.
// Writes docs-only=true to GITHUB_OUTPUT only when every changed path is Markdown other than CHANGELOG.md.
// A missing, zero or malformed base (manual dispatch, new branch) always yields docs-only=false.
// Renames count as deletion plus addition, so renaming a source file to Markdown still requires verification.
import fs from 'node:fs';
import {execFileSync} from 'node:child_process';

const base = process.env.BASE_SHA || '';
const head = process.env.GITHUB_SHA || '';
let docsOnly = false;
if (/^[a-f0-9]{40}$/.test(base) && !/^0+$/.test(base) && /^[a-f0-9]{40}$/.test(head)) {
  const paths = execFileSync('git', ['diff', '--no-renames', '--name-only', '-z', base, head], {encoding: 'utf8'})
    .split('\0').filter(Boolean);
  docsOnly = paths.length > 0 && paths.every(path =>
    path.endsWith('.md') && path.split('/').at(-1) !== 'CHANGELOG.md');
}
console.log(`Documentation-only change: ${docsOnly}`);
fs.appendFileSync(process.env.GITHUB_OUTPUT, `docs-only=${docsOnly}\n`);
