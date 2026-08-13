//@ts-check

'use strict';

const path = require('path');
const fs = require('fs');
const webpack = require('webpack');
const { BundleAnalyzerPlugin } = require('webpack-bundle-analyzer');
const TerserPlugin = require('terser-webpack-plugin');

function loadCloudEnv() {
  const envPath = process.env.ENV_FILE
    ? path.resolve(__dirname, process.env.ENV_FILE)
    : path.resolve(__dirname, 'webview-src', '.env.production');
  const out = {};
  try {
    for (const line of fs.readFileSync(envPath, 'utf-8').split(/\r?\n/)) {
      const m = line.match(/^\s*([A-Za-z0-9_]+)\s*=\s*(.*?)\s*$/);
      if (m && !line.trim().startsWith('#')) out[m[1]] = m[2];
    }
  } catch { /* no env file → empty → extension.ts uses its prod defaults */ }
  return out;
}
const cloudEnv = loadCloudEnv();
const pick = (...keys) => {
  for (const k of keys) if (cloudEnv[k]) return cloudEnv[k];
  return '';
};

const config = {
  target: 'node', // vscode extensions run in a Node.js-context 📖 https://webpack.js.org/configuration/node/
  cache: {
    type: 'filesystem',
    buildDependencies: {
      config: [__filename]
    }
  },
  entry: './src/extension.ts', // the entry point of this extension
  output: {
    path: path.resolve(__dirname, 'dist'),
    filename: 'extension.js',
    libraryTarget: 'commonjs2',
    devtoolModuleFilenameTemplate: '../[resourceBaseName]'
  },
  devtool: 'source-map',
  externals: {
    vscode: 'commonjs vscode' // the vscode-module is created on the fly and must be excluded.
  },
  resolve: {
    extensions: ['.ts', '.js']
  },
  optimization: {
    minimize: true,
    minimizer: [
      new TerserPlugin({
        terserOptions: {
          compress: {
            drop_console: false,
            passes: 2
          },
          mangle: true
        }
      })
    ],
    usedExports: true,
    sideEffects: false
  },
  module: {
    rules: [
      {
        test: /\.ts$/,
        exclude: /node_modules/,
        use: [
          {
            loader: 'ts-loader'
          }
        ]
      }
    ]
  },
  plugins: [
    new webpack.DefinePlugin({
      'process.env.CLOUD_GATEWAY_URL': JSON.stringify(pick('CLOUD_GATEWAY_URL', 'VITE_CLOUD_GATEWAY_URL')),
      'process.env.CLOUD_EDITOR_URL': JSON.stringify(pick('CLOUD_EDITOR_URL', 'VITE_CLOUD_EDITOR_URL')),
      'process.env.CLOUD_PLUGIN_URL': JSON.stringify(pick('CLOUD_PLUGIN_URL', 'VITE_CLOUD_PLUGIN_URL')),
    }),
    new BundleAnalyzerPlugin({
      analyzerMode: process.env.ANALYZE ? 'server' : 'disabled',
      reportFilename: 'bundle-report-extension.html',
      openAnalyzer: true,
      generateStatsFile: true,
      statsFilename: 'bundle-stats-extension.json'
    })
  ]
};
module.exports = config;