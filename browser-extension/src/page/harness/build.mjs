// 確かめ用の拡張を dist/ に作る。node build.mjs
import { build } from 'esbuild';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const out = path.join(here, 'dist');
fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(out, { recursive: true });

const common = { bundle: true, format: 'iife', target: ['chrome111', 'firefox128'], logLevel: 'warning' };
await build({ ...common, entryPoints: [path.join(here, '..', 'page-main.ts')], outfile: path.join(out, 'page-main.js') });
await build({ ...common, entryPoints: [path.join(here, 'dev-content.ts')], outfile: path.join(out, 'dev-content.js') });
fs.copyFileSync(path.join(here, 'manifest.json'), path.join(out, 'manifest.json'));
fs.copyFileSync(path.join(here, 'background.js'), path.join(out, 'background.js'));
fs.copyFileSync(path.join(here, 'dev-main.js'), path.join(out, 'dev-main.js'));
console.log('built', out);
