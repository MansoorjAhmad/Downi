// Tab-switch gate: tapping a tab must leave exactly ONE view on screen and
// exactly ONE nav item lit.
//
// Why this exists. The v3.5.0 web cut removed the Vault, the Fetcher and the
// Core by finding and deleting code that named them. Two of those deletions
// were whole statements that happened to name 'viewVault' / 'navVault':
//
//   ['viewGrab','viewQueue','viewVault','viewSettings'].forEach(id => {
//     document.getElementById(id)?.classList.add('hidden');
//   });
//   ['navGrab','navQueue','navVault','navSettings'].forEach(id => {
//     document.getElementById(id)?.classList.remove('on');
//   });
//
// switchTab() still unhid the tab you tapped, so the app looked right on the
// screen you were on. What broke was everything you were NOT looking at: the
// view you came from stayed on screen, stacked underneath, and its nav item
// stayed lit. Tap Queue and you scrolled through the Grab screen to reach it;
// tap Settings and all three were stacked, all three nav items lit. Gradle and
// the JVM tests cannot see any of that, and `assembleDebug` was green the whole
// time. A browser run caught it on 2026-10-20, before v3.5.0 was tagged.
//
// What it does. Extracts the REAL switchTab() source from a built copy of
// index.html and runs it against a small DOM stub - the same classList
// bookkeeping the app relies on, with no browser and no device. It walks the
// tabs the way a user does, including a repeat visit, and fails if any switch
// leaks a view or a nav.
//
// What it does NOT prove. It is a stub, not a browser: it says nothing about
// layout, animation, or whether the screens look right. It checks exactly one
// thing - that the tab state is cleared - which is the one thing a silent
// deletion can take away without breaking the build.
//
// Usage
//   node tools/_tabcheck.js                       # the two copies that ship
//   node tools/_tabcheck.js <index.html> [...]    # or explicit copies
// Exit 0 = every copy behaves; 1 = a copy leaks a view or a nav; 2 = bad usage.
//
// Pair it with tools/_jscheck.py: that one proves the web layer parses, this
// one proves the tab state is still cleared.

const fs = require('fs');
const path = require('path');

// The two copies of the UI that must agree: the source of truth, and the copy
// Capacitor bundles into the APK.
const DEFAULT_TARGETS = [
  'www/index.html',
  'android/app/src/main/assets/public/index.html',
];

const VIEWS = ['viewGrab', 'viewQueue', 'viewSettings'];
const NAVS = ['navGrab', 'navQueue', 'navSettings'];

function extractSwitchTab(src) {
  const start = src.indexOf('function switchTab');
  if (start < 0) throw new Error('switchTab() not found - was it renamed or removed?');
  const open = src.indexOf('{', start);
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') {
      depth--;
      if (depth === 0) return src.slice(start, i + 1);
    }
  }
  throw new Error('switchTab() braces are unbalanced');
}

// The markup's own starting state: Grab is the open view and the lit nav item.
function makeDom() {
  const els = {};
  for (const id of [...VIEWS, ...NAVS]) {
    const classes = new Set();
    if (id === 'viewQueue' || id === 'viewSettings') classes.add('hidden');
    if (id === 'navGrab') classes.add('on');
    els[id] = {
      classList: {
        add: (c) => classes.add(c),
        remove: (c) => classes.delete(c),
        contains: (c) => classes.has(c),
        toggle: (c, force) => (force === undefined ? (classes.has(c) ? classes.delete(c) : classes.add(c)) : (force ? classes.add(c) : classes.delete(c))),
      },
      offsetWidth: 0,
      style: {},
      _classes: classes,
    };
  }
  return { els, document: { getElementById: (id) => els[id] || null } };
}

function check(source) {
  const fnSrc = extractSwitchTab(source);
  const dom = makeDom();
  const build = new Function(
    'document', 'triggerHaptic', 'renderQueueTab', 'renderJobCards',
    'let currentTab = "grab";\n' + fnSrc + '\nreturn switchTab;'
  );
  const switchTab = build(dom.document, () => {}, () => {}, () => {});

  const failures = [];
  for (const tab of ['queue', 'settings', 'grab', 'settings', 'queue']) {
    switchTab(tab);
    const shown = VIEWS.filter((id) => !dom.els[id].classList.contains('hidden'));
    const lit = NAVS.filter((id) => dom.els[id].classList.contains('on'));
    const wantView = 'view' + tab[0].toUpperCase() + tab.slice(1);
    const wantNav = 'nav' + tab[0].toUpperCase() + tab.slice(1);
    if (shown.length !== 1 || shown[0] !== wantView) {
      failures.push(`tab=${tab}: views on screen = [${shown.join(', ')}] - want only ${wantView}`);
    }
    if (lit.length !== 1 || lit[0] !== wantNav) {
      failures.push(`tab=${tab}: navs lit = [${lit.join(', ')}] - want only ${wantNav}`);
    }
  }
  return failures;
}

const targets = process.argv.slice(2).length ? process.argv.slice(2) : DEFAULT_TARGETS;
const root = path.resolve(__dirname, '..');

let bad = 0;
for (const target of targets) {
  const file = path.isAbsolute(target) ? target : path.join(root, target);
  const shown = path.relative(root, file).replace(/\\/g, '/') || file;
  let source;
  try {
    source = fs.readFileSync(file, 'utf8');
  } catch (e) {
    console.log(`FAIL  ${shown}\n      cannot read: ${e.message}`);
    bad++;
    continue;
  }
  let failures;
  try {
    failures = check(source);
  } catch (e) {
    console.log(`FAIL  ${shown}\n      ${e.message}`);
    bad++;
    continue;
  }
  if (failures.length === 0) {
    console.log(`ok    ${shown}  (five tab switches in, one view and one nav each time)`);
  } else {
    bad++;
    console.log(`FAIL  ${shown}`);
    for (const f of failures) console.log(`      ${f}`);
  }
}
process.exit(bad === 0 ? 0 : 1);
