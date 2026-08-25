const fs = require('fs');

function analyzeStats(statsFile) {
  if (!fs.existsSync(statsFile)) {
    console.log(`Stats file not found: ${statsFile}`);
    return;
  }

  const stats = JSON.parse(fs.readFileSync(statsFile, 'utf8'));
  const modules = stats.modules || [];

  const sorted = modules
    .filter(m => m.name && m.size)
    .map(m => ({
      name: m.name.replace(/^.*node_modules\//, '').substring(0, 70),
      size: m.size
    }))
    .sort((a, b) => b.size - a.size)
    .slice(0, 20);

  console.log('\n📊 TOP 20 LARGEST MODULES:\n');
  console.log('='.repeat(90));

  sorted.forEach((m, i) => {
    const sizeKB = (m.size / 1024).toFixed(2);
    const index = `${i + 1}.`.padStart(3);
    console.log(`${index} ${m.name.padEnd(72)} ${sizeKB.padStart(10)} KB`);
  });

  console.log('='.repeat(90));

  const totalSize = stats.assets.find(a => a.name === 'extension.js')?.size || 0;
  console.log(`\nTotal bundle size: ${(totalSize / 1024).toFixed(2)} KB\n`);

  const byPackage = {};
  modules.forEach(m => {
    if (m.name && m.name.includes('node_modules')) {
      const match = m.name.match(/node_modules\/(@[^/]+\/[^/]+|[^/]+)/);
      if (match) {
        const pkg = match[1];
        byPackage[pkg] = (byPackage[pkg] || 0) + m.size;
      }
    }
  });

  const packagesSorted = Object.entries(byPackage)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 10);

  console.log('\n📦 TOP 10 PACKAGES BY SIZE:\n');
  console.log('='.repeat(90));

  packagesSorted.forEach(([pkg, size], i) => {
    const sizeKB = (size / 1024).toFixed(2);
    const index = `${i + 1}.`.padStart(3);
    console.log(`${index} ${pkg.padEnd(72)} ${sizeKB.padStart(10)} KB`);
  });

  console.log('='.repeat(90) + '\n');
}

console.log('\n' + '='.repeat(90));
console.log('🔍 WEBPACK BUNDLE ANALYSIS');
console.log('='.repeat(90));

const extensionStats = 'dist/bundle-stats-extension.json';
const webStats = 'dist/web/bundle-stats-web.json';

if (fs.existsSync(extensionStats)) {
  console.log('\n🔹 EXTENSION BUNDLE (Node.js)');
  analyzeStats(extensionStats);
}

if (fs.existsSync(webStats)) {
  console.log('\n🔹 WEB BUNDLE (Browser)');
  analyzeStats(webStats);
}

if (!fs.existsSync(extensionStats) && !fs.existsSync(webStats)) {
  console.log('\n❌ No stats files found. Run "npm run analyze:extension" or "npm run analyze:web" first.\n');
}
