const path = require('path');
const webpack = require('webpack');
const CopyPlugin = require('copy-webpack-plugin');
const { BundleAnalyzerPlugin } = require('webpack-bundle-analyzer');
const TerserPlugin = require('terser-webpack-plugin');

module.exports = {
  mode: 'production',
  target: 'webworker',
  cache: {
    type: 'filesystem',
    buildDependencies: {
      config: [__filename]
    }
  },
  entry: {
    extension: './src/extension.web.ts'
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
    alias: {
        '_stream_readable': 'readable-stream/lib/_stream_readable',
        '_stream_writable': 'readable-stream/lib/_stream_writable',
        '_stream_duplex': 'readable-stream/lib/_stream_duplex',
        '_stream_transform': 'readable-stream/lib/_stream_transform',
        '_stream_passthrough': 'readable-stream/lib/_stream_passthrough'
    },
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
        "tty": false,
        "zlib": require.resolve("./src/zlib-shim.js"),
        "vm": false
    }
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
        use: [{ loader: 'ts-loader' }],
      },
    ],
  },
  plugins: [
    new webpack.ProvidePlugin({
        process: 'process/browser',
        Buffer: ['buffer', 'Buffer'],
    }),
    new webpack.DefinePlugin({
        'process.env.NODE_DEBUG': JSON.stringify(false),
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