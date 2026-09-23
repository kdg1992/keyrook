// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
import {createHash} from 'node:crypto';
import {createReadStream, createWriteStream} from 'node:fs';
import {mkdir, readFile, rename, rm, copyFile, appendFile} from 'node:fs/promises';
import {Readable} from 'node:stream';
import {pipeline} from 'node:stream/promises';
import path from 'node:path';

const evidence = 'licenses/native-evidence/temurin-25.0.4.1+1';
const provenance = JSON.parse(await readFile(`${evidence}/provenance.json`, 'utf8'));
const mode = process.argv[2] ?? 'source';
if (!['source', 'runtime'].includes(mode) || process.argv.length > 3) throw Error('Invalid archive mode');
const targetPlatform = {win32: 'windows', linux: 'linux', darwin: 'macos'}[process.platform];
let archive = {url: provenance.sourceArchive, sha256: provenance.sourceArchiveSha256};
if (mode === 'runtime') {
    const records = JSON.parse(await readFile('licenses/native-evidence/ci-2026-09-23/provenance.json', 'utf8'));
    const record = records.platforms.find(item => item.target === `${targetPlatform}-${process.arch}`);
    if (!record || record.jdkVersion !== '25.0.4.1+1-LTS') throw Error('No matching JDK runtime evidence');
    archive = record.jdkArchive;
}
const url = new URL(archive.url);
if (url.protocol !== 'https:' || url.hostname !== 'github.com' ||
    !url.pathname.startsWith('/adoptium/temurin25-binaries/releases/download/') ||
    !/^[a-f0-9]{64}$/.test(archive.sha256)) {
    throw Error('Invalid pinned JDK archive provenance');
}
const directory = `build/jdk-${mode}`;
await mkdir(directory, {recursive: true});
const target = path.join(directory, path.basename(url.pathname));
const temporary = `${target}.partial`;
try {
    const response = await fetch(url, {signal: AbortSignal.timeout(300000)});
    if (!response.ok || !response.body) throw Error(`JDK archive download failed: ${response.status}`);
    await pipeline(Readable.fromWeb(response.body), createWriteStream(temporary, {flags: 'wx'}));
    const hash = createHash('sha256');
    for await (const chunk of createReadStream(temporary)) hash.update(chunk);
    if (hash.digest('hex') !== archive.sha256) {
        throw Error('JDK archive checksum mismatch');
    }
    await rename(temporary, target);
    if (mode === 'source') {
        await copyFile(`${evidence}/provenance.json`, `${directory}/JDK-SOURCE-PROVENANCE.json`);
    } else if (process.env.GITHUB_OUTPUT) {
        await appendFile(process.env.GITHUB_OUTPUT, `archive-path=${path.resolve(target)}\n`);
    }
    console.log(`Pinned JDK ${mode} archive verified`);
} finally {
    await rm(temporary, {force: true});
}
