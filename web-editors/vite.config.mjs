// Builds the native apps' editor pages into dist/: the board editor (index.html, web/main.js + the vendored sol board
// editor in web/sol) and the document editor (document.html, web/document.js + the vendored sol document editor).
// Run through scripts/build-board-editor.sh (Xcode runs it for the app targets).
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const fontsDir = path.join(here, 'node_modules', '@excalidraw', 'excalidraw', 'dist', 'prod', 'fonts');
const FONT_TYPES = new Set(['.woff2', '.woff', '.ttf']);

function walk(dir, base = dir) {
  return readdirSync(dir).flatMap(name => {
    const full = path.join(dir, name);
    return statSync(full).isDirectory() ? walk(full, base) : [path.relative(base, full)];
  });
}

function dependencyNotices() {
  const modules = path.join(here, 'node_modules');
  const folders = readdirSync(modules).filter(n => !n.startsWith('.')).flatMap(n => n.startsWith('@')
    ? readdirSync(path.join(modules, n)).map(child => path.join(modules, n, child)) : [path.join(modules, n)]);
  const text = [readFileSync(path.join(here, 'LICENSES.md'), 'utf8')];
  for (const dir of folders.sort()) {
    const manifest = path.join(dir, 'package.json');
    if (!existsSync(manifest)) continue;
    const pkg = JSON.parse(readFileSync(manifest, 'utf8'));
    text.push(`\n=== ${pkg.name} ${pkg.version} (${typeof pkg.license === 'string' ? pkg.license : JSON.stringify(pkg.license)}) ===\n`);
    for (const file of readdirSync(dir).filter(n => /^(licen[sc]e|copying)(\..*)?$/i.test(n))) {
      if (statSync(path.join(dir, file)).isFile()) text.push(readFileSync(path.join(dir, file), 'utf8'));
    }
  }
  for (const file of readdirSync(path.join(here, 'licenses')).filter(n => n.endsWith('.txt')).sort()) text.push(readFileSync(path.join(here, 'licenses', file), 'utf8'));
  return text.join('\n');
}

const excalidrawFonts = {
  name: 'excalidraw-fonts',
  generateBundle() {
    this.emitFile({ type: 'asset', fileName: 'THIRD_PARTY_NOTICES.txt', source: dependencyNotices() });
    if (!existsSync(fontsDir)) throw new Error(`Excalidraw fonts are missing at ${fontsDir}; run scripts/build-board-editor.sh.`);
    for (const relative of walk(fontsDir)) {
      if (!FONT_TYPES.has(path.extname(relative))) continue;
      this.emitFile({ type: 'asset', fileName: `excalidraw-assets/fonts/${relative.split(path.sep).join('/')}`, source: readFileSync(path.join(fontsDir, relative)) });
    }
  },
};

export default {
  root: path.join(here, 'web'),
  base: './',
  logLevel: 'warn',
  plugins: [excalidrawFonts],
  build: {
    outDir: path.join(here, 'dist'),
    emptyOutDir: true,
    target: 'es2022',
    modulePreload: false,
    chunkSizeWarningLimit: 8000,
    rollupOptions: { input: { index: path.join(here, 'web', 'index.html'), document: path.join(here, 'web', 'document.html') } },
  },
};
