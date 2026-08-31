// Bâtit `app/src/main/assets/webphone/jssip.js` depuis le paquet npm `jssip`.
//
// Les options reproduisent celles de `npm-scripts.mjs` de l'amont (fonction
// `build`), pour que le fichier obtenu soit celui que JsSIP publierait lui-même
// s'il publiait un `dist/`. Deux écarts assumés :
//
//   1. `minify: false`. Un paquet minifié dans un dépôt public est du code que
//      personne ne peut relire. Le surcoût est négligeable une fois l'APK
//      compressé.
//   2. La bannière est FIXE. Celle de l'amont interpole
//      `new Date().getFullYear()`, donc le même code source rendrait un fichier
//      différent l'an prochain. Une bâtie qui dépend de la date n'est pas
//      reproductible.
import esbuild from 'esbuild';
import path from 'path';
import { createRequire } from 'module';

const require = createRequire(import.meta.url);
const pkg = require('jssip/package.json');
const entry = require.resolve('jssip/lib/JsSIP.js');
const outfile = path.resolve(process.argv[2]);

const banner = `
 /*
  * JsSIP ${pkg.version}
  * ${pkg.description}
  * Copyright: 2012-2026 ${pkg.contributors.join(' ')}
  * Homepage: ${pkg.homepage}
  * License: ${pkg.license}
  */`;

await esbuild.build({
  entryPoints: [entry],
  outfile,
  bundle: true,
  minify: false,
  sourcemap: false,
  format: 'iife',
  globalName: 'JsSIP',
  platform: 'browser',
  target: ['es2015'],
  supported: { 'template-literal': false },
  banner: { js: banner },
});

console.log(`jssip ${pkg.version} -> ${outfile}`);
