//@ts-check

'use strict';

const path = require('path');
const fs = require('fs');
const webpack = require('webpack');
const { BundleAnalyzerPlugin } = require('webpack-bundle-analyzer');
const TerserPlugin = require('terser-webpack-plugin');

const { cloudDefines } = require('./scripts/cloud-env');

const config = {
  target: 'node', // vscode extensions run in a Node.js-context 📖 https://webpack.js.org/configuration/node/
  cache: false, // was filesystem cache keyed only on this file's mtime — didn't invalidate when ENV_FILE content changed, serving stale CLOUD_GATEWAY_URL values across builds
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
    new webpack.DefinePlugin(cloudDefines()),
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