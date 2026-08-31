# Third-party code shipped inside the app

## JsSIP

`app/src/main/assets/webphone/jssip.js` is a browser bundle of
[JsSIP](https://jssip.net), the SIP stack the handset registers with. It is
loaded by `webphone.html` inside a headless WebView.

| | |
| --- | --- |
| Upstream | https://github.com/versatica/JsSIP |
| Version | 3.13.8 (published 2026-05-06) |
| Licence | MIT, notice retained in the file header |
| npm tarball | `jssip-3.13.8.tgz`, sha256 `9ba24b71c6ece8375cf259408ff23004969208a31a9826bb9d705e26c5fdc178` |
| Bundle | sha256 `6d416030f28649710ad0014cebdd0c41436d12c9c364642a84e7c511493b3e22` |

### Reproducing it

```
./tools/build-jssip.sh
```

That runs `npm ci` and esbuild inside `node:22-bookworm-slim`, so nothing has to
be installed on the host but Docker. Versions are frozen by
`tools/jssip/package-lock.json`; `npm ci` refuses to drift from it. Two runs on
two machines produce the same bytes, and the script prints the sha256 so it can
be checked against the table above.

The esbuild options in `tools/jssip/build.mjs` are upstream's own, copied from
the `build` function of JsSIP's `npm-scripts.mjs`. Two deliberate differences:

- **Not minified.** Minified code in a public repository is code nobody can
  read. The cost is a larger asset, which all but disappears once the APK is
  compressed.
- **Fixed copyright year in the banner.** Upstream interpolates
  `new Date().getFullYear()`, so the same sources would produce a different file
  next year. A build that depends on the date is not reproducible.

### Why this exists

Until 2026-08-30 the app shipped `jssip.min.js`, a bundle that matched **no
published JsSIP release**. It embedded a `package.json` announcing 3.11.1, but
with devDependencies (esbuild, jest, eslint 9) that the published 3.11.1 does
not have — that one still used gulp. It was a local build of an unreleased
upstream state, and there was nothing to compare it against: upstream ships no
`dist/` on npm, jsDelivr or GitHub.

So the SIP stack parsing everything that arrived from the network could not be
verified by anyone, and it was two minor lines behind. Both are fixed by
building from a pinned published release, with the command and the checksum
written down.
