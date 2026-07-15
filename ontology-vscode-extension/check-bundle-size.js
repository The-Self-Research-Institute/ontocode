const fs = require('fs');
const path = require('path');

function getDirectorySize(dirPath) {
  if (!fs.existsSync(dirPath)) {
    return 0;
  }

  let size = 0;
  const files = fs.readdirSync(dirPath);

  for (const file of files) {
    const filePath = path.join(dirPath, file);
    const stats = fs.statSync(filePath);

    if (stats.isDirectory()) {
      size += getDirectorySize(filePath);
    } else {
      size += stats.size;
    }
  }

  return size;
}

function getFileSize(filePath) {
  if (!fs.existsSync(filePath)) {
    return 0;
  }
  return fs.statSync(filePath).size;
}

function formatBytes(bytes) {
  if (bytes === 0) return '0 Bytes';
  const k = 1024;
  const sizes = ['Bytes', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i];
}

function formatPercentage(value, total) {
  return ((value / total) * 100).toFixed(1) + '%';
}

console.log('\n' + '='.repeat(60));
console.log('📦  BUNDLE SIZE REPORT');
console.log('='.repeat(60) + '\n');

// Extension bundles
const extensionBundle = getFileSize('dist/extension.js');
const webBundle = getFileSize('dist/web/extension.js');
const distTotal = getDirectorySize('dist');

console.log('🔹 Extension Bundles:');
console.log(`   Node.js (dist/extension.js):     ${formatBytes(extensionBundle)}`);
console.log(`   Web (dist/web/extension.js):     ${formatBytes(webBundle)}`);
console.log(`   Total dist/ folder:              ${formatBytes(distTotal)}`);
console.log();

// Webview builds
const webviewDistSize = getDirectorySize('webview-src/dist');

console.log('🔹 Webview Build:');
console.log(`   webview-src/dist/:               ${formatBytes(webviewDistSize)}`);
console.log();

// TypeScript output
const outSize = getDirectorySize('out');

console.log('🔹 TypeScript Output (out/):');
console.log(`   Size:                            ${formatBytes(outSize)}`);
console.log();

// VSIX package (if exists)
const vsixFiles = fs.readdirSync('.').filter(f => f.endsWith('.vsix'));
if (vsixFiles.length > 0) {
  console.log('🔹 VSIX Packages:');
  vsixFiles.forEach(vsix => {
    const vsixSize = getFileSize(vsix);
    console.log(`   ${vsix}:`.padEnd(40) + formatBytes(vsixSize));

    if (vsixSize > 50 * 1024 * 1024) {
      console.log(`   ❌ EXCEEDS HARD LIMIT (50 MB)!`);
    } else if (vsixSize > 25 * 1024 * 1024) {
      console.log(`   ⚠️  EXCEEDS DEFAULT LIMIT (25 MB)`);
    } else {
      console.log(`   ✅ Within limits`);
    }
  });
  console.log();
}

// Totals
const totalSize = distTotal + webviewDistSize + outSize;
console.log('='.repeat(60));
console.log('📊 TOTAL SIZE BREAKDOWN:');
console.log('='.repeat(60));
console.log(`   Extension bundles (dist/):       ${formatBytes(distTotal).padEnd(15)} (${formatPercentage(distTotal, totalSize)})`);
console.log(`   Webview build:                   ${formatBytes(webviewDistSize).padEnd(15)} (${formatPercentage(webviewDistSize, totalSize)})`);
console.log(`   TypeScript output (out/):        ${formatBytes(outSize).padEnd(15)} (${formatPercentage(outSize, totalSize)})`);
console.log('   ' + '-'.repeat(56));
console.log(`   TOTAL:                           ${formatBytes(totalSize)}`);
console.log();

// Limits
console.log('⚠️  VSIX PACKAGE LIMITS:');
console.log('   Default limit:                   25 MB');
console.log('   Hard maximum:                    50 MB');
console.log();

// Recommendations
console.log('💡 RECOMMENDATIONS:');
if (extensionBundle > 500 * 1024) {
  console.log('   ⚠️  Extension bundle >500KB - consider analyzing with webpack-bundle-analyzer');
}
if (webviewDistSize > 5 * 1024 * 1024) {
  console.log('   ⚠️  Webview bundle >5MB - consider code splitting and lazy loading');
}
if (outSize > 1 * 1024 * 1024) {
  console.log('   ℹ️  TypeScript output is large - ensure .vscodeignore excludes out/ folder');
}
if (vsixFiles.length === 0) {
  console.log('   ℹ️  No VSIX package found - run "npm run package" to create one');
}
console.log();
console.log('='.repeat(60));
console.log();
