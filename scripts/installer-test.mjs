// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
// Helpers for the Release workflow's installer tests.
//   prepare <deb|rpm|msi|dmg>  verifies this run's installer and downloads the previous release's one
//   self-test <launcher>       runs an installed launcher's runtime check against a new report file
import {createHash} from 'node:crypto';
import {spawnSync, execFileSync} from 'node:child_process';
import {appendFileSync, existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';

const SUCCESS = 'Keyrook runtime check passed\n';
const [command, argument, ...extra] = process.argv.slice(2);
if (extra.length > 0 || !argument) throw Error('Usage: installer-test.mjs prepare <extension> | self-test <launcher>');

const digest = file => createHash('sha256').update(readFileSync(file)).digest('hex');
const posix = file => path.relative(process.cwd(), file).split(path.sep).join('/');
const parse = version => version.split('.').map(Number);
const older = (a, b) => {
    const [x, y] = [parse(a), parse(b)];
    for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] < y[i];
    return false;
};
const output = (name, value) => {
    if (/[\r\n]/.test(value)) throw Error('Invalid step output');
    if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `${name}=${value}\n`);
    console.log(`${name}=${value}`);
};
const verifyChecksum = (manifest, file) => {
    const name = path.basename(file);
    const records = readFileSync(manifest, 'utf8').split('\n')
        .map(line => /^([0-9a-f]{64}) [ *]([A-Za-z0-9._-]+)$/.exec(line.trim()))
        .filter(match => match && match[2] === name);
    if (records.length !== 1 || !lstatSync(file).isFile() || digest(file) !== records[0][1]) {
        throw Error(`Checksum verification failed for ${name}`);
    }
};

function prepare(extension) {
    if (!['deb', 'rpm', 'msi', 'dmg'].includes(extension)) throw Error('Invalid installer extension');
    const platform = {win32: 'windows', linux: 'linux', darwin: 'macos'}[process.platform];
    if (!platform || !['x64', 'arm64'].includes(process.arch)) throw Error('Unsupported runner');
    const target = `${platform}-${process.arch}`;
    const directory = 'build/packages';
    const pattern = new RegExp(`^Keyrook-(\\d+\\.\\d+\\.\\d+)-${target}\\.${extension}$`);
    const current = readdirSync(directory).filter(name => pattern.test(name));
    if (current.length !== 1) throw Error(`Expected one ${target} ${extension} installer from this run`);
    const installer = path.join(directory, current[0]);
    verifyChecksum(path.join(directory, `SHA256SUMS-${target}.txt`), installer);
    const version = pattern.exec(current[0])[1];
    output('new-installer', posix(installer));
    output('new-version', version);

    // The release this run is building is still a draft, so the upgrade source is the highest published,
    // non-prerelease version below it that ships an installer for this target.
    const repository = process.env.GH_REPO;
    if (!repository) throw Error('GH_REPO is required');
    const listing = execFileSync('gh', ['api', '--paginate', `repos/${repository}/releases?per_page=100`, '--jq',
        '.[] | select((.draft | not) and (.prerelease | not)) | {tag: .tag_name, assets: [.assets[].name]}'],
        {encoding: 'utf8', maxBuffer: 16 * 1024 * 1024});
    let previous = null;
    for (const line of listing.split('\n').filter(Boolean)) {
        const release = JSON.parse(line);
        const match = /^v(\d+\.\d+\.\d+)$/.exec(release.tag);
        if (!match || !older(match[1], version)) continue;
        const asset = `Keyrook-${match[1]}-${target}.${extension}`;
        if (!release.assets.includes(asset) || !release.assets.includes('SHA256SUMS.txt')) continue;
        if (!previous || older(previous.version, match[1])) previous = {tag: release.tag, version: match[1], asset};
    }
    if (!previous) {
        console.log(`::notice title=Upgrade test skipped::No published release older than ${version} has a ${target} ${extension} installer`);
        output('old-installer', '');
        output('old-version', '');
        return;
    }
    const destination = `build/previous-release/${extension}`;
    rmSync(destination, {recursive: true, force: true});
    mkdirSync(destination, {recursive: true});
    execFileSync('gh', ['release', 'download', previous.tag, '--repo', repository, '--dir', destination,
        '--pattern', previous.asset, '--pattern', 'SHA256SUMS.txt'], {stdio: 'inherit'});
    const oldInstaller = path.join(destination, previous.asset);
    verifyChecksum(path.join(destination, 'SHA256SUMS.txt'), oldInstaller);
    console.log(`::notice title=Upgrade source::${previous.tag} ${previous.asset} -> ${version}`);
    output('old-installer', posix(oldInstaller));
    output('old-version', previous.version);
}

function selfTest(launcher) {
    if (!existsSync(launcher) || !lstatSync(launcher).isFile()) throw Error(`Installed launcher missing: ${launcher}`);
    const directory = mkdtempSync(path.join(tmpdir(), 'keyrook-self-test-'));
    try {
        const report = path.join(directory, 'report.txt');
        const result = spawnSync(launcher, ['--self-test', report], {
            timeout: 120000, windowsHide: true, stdio: 'pipe', maxBuffer: 1024 * 1024
        });
        if (result.error || result.status !== 0 || !existsSync(report) || readFileSync(report, 'utf8') !== SUCCESS) {
            process.stderr.write(result.stderr ?? '');
            throw Error(`Installed runtime check failed (status ${result.status}, ${result.error ?? 'no spawn error'})`);
        }
        console.log(`Installed runtime check passed: ${launcher}`);
    } finally {
        rmSync(directory, {recursive: true, force: true});
    }
}

if (command === 'prepare') prepare(argument);
else if (command === 'self-test') selfTest(path.resolve(argument));
else throw Error(`Unknown command: ${command}`);
