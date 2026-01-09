const path = require('path');
const webpack = require('webpack');
const CopyPlugin = require('copy-webpack-plugin');
const { BundleAnalyzerPlugin } = require('webpack-bundle-analyzer');

module.exports = {
  mode: 'production',
  target: 'webworker',
  entry: {
    extension: './src/extension.ts'
  },
  output: {
    filename: '[name].js',
    path: path.resolve(__dirname, 'dist', 'web'),
    libraryTarget: 'commonjs',
    devtoolModuleFilenameTemplate: '../[resource]',
    globalObject: 'self'
  },
  devtool: 'source-map',
  externals: {
    vscode: 'commonjs vscode'
  },
  resolve: {
    extensions: ['.ts', '.js'],
    fallback: {
        "path": require.resolve("path-browserify"),
        "fs": false,
        "os": false,
        "crypto": require.resolve("crypto-browserify"),
        "stream": require.resolve("stream-browserify"),
        "buffer": require.resolve("buffer/"),
        "util": require.resolve("util/"),
        "assert": require.resolve("assert/"),
        "url": require.resolve("url/"),
        "http": require.resolve("stream-http"),
        "https": require.resolve("https-browserify"),
        "process": require.resolve("process/browser"),
    }
  },
  module: {
    rules: [
      {
        test: /\.ts$/,
        exclude: /node_modules/,
        use: [{ loader: 'ts-loader' }],
      },
    ],
  },
  plugins: [
    new webpack.ProvidePlugin({
        process: 'process/browser',
        Buffer: ['buffer', 'Buffer'],
    }),
    new CopyPlugin({
      patterns: [
        {
          from: 'webview-src/dist',
          to: 'webview-src/dist',
          noErrorOnMissing: true
        }
      ]
    }),
    new BundleAnalyzerPlugin({
      analyzerMode: process.env.ANALYZE ? 'server' : 'disabled',
      reportFilename: 'bundle-report-web.html',
      openAnalyzer: true,
      generateStatsFile: true,
      statsFilename: 'bundle-stats-web.json'
    })
  ]
};