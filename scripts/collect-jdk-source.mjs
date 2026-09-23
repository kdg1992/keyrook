// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
import {createHash} from 'node:crypto';
import {createReadStream, createWriteStream} from 'node:fs';
import {mkdir, readFile, rename, rm, copyFile} from 'node:fs/promises';
import {Readable} from 'node:stream';
import {pipeline} from 'node:stream/promises';
import path from 'node:path';

const evidence = 'licenses/native-evidence/temurin-25.0.4.1+1';
const provenance = JSON.parse(await readFile(`${evidence}/provenance.json`, 'utf8'));
const url = new URL(provenance.sourceArchive);
if (url.protocol !== 'https:' || url.hostname !== 'github.com' ||
    !url.pathname.startsWith('/adoptium/temurin25-binaries/releases/download/') ||
    !/^[a-f0-9]{64}$/.test(provenance.sourceArchiveSha256)) {
    throw Error('Invalid pinned JDK source provenance');
}
const directory = 'build/jdk-source';
await mkdir(directory, {recursive: true});
const target = path.join(directory, path.basename(url.pathname));
const temporary = `${target}.partial`;
try {
    const response = await fetch(url, {signal: AbortSignal.timeout(300000)});
    if (!response.ok || !response.body) throw Error(`JDK source download failed: ${response.status}`);
    await pipeline(Readable.fromWeb(response.body), createWriteStream(temporary, {flags: 'wx'}));
    const hash = createHash('sha256');
    for await (const chunk of createReadStream(temporary)) hash.update(chunk);
    if (hash.digest('hex') !== provenance.sourceArchiveSha256) {
        throw Error('JDK source archive checksum mismatch');
    }
    await rename(temporary, target);
    await copyFile(`${evidence}/provenance.json`, `${directory}/JDK-SOURCE-PROVENANCE.json`);
    console.log('Pinned JDK source archive verified');
} finally {
    await rm(temporary, {force: true});
}
