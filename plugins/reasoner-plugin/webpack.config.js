const path = require('path');

module.exports = {
  mode: 'production',
  entry: './src/index.ts',
  output: {
    path: path.resolve(__dirname, 'dist'),
    filename: 'index.js',
    library: {
      name: 'ReasonerPlugin',
      type: 'umd',
      export: 'default',
    },
    globalObject: 'typeof self !== \'undefined\' ? self : this',
    umdNamedDefine: true,
    clean: true,
  },
  resolve: {
    extensions: ['.ts', '.tsx', '.js', '.jsx'],
  },
  module: {
    rules: [
      {
        test: /\.tsx?$/,
        use: {
          loader: 'ts-loader',
          options: {
            transpileOnly: true
          }
        },
        exclude: /node_modules/,
      },
      {
        test: /\.css$/,
        use: ['style-loader', 'css-loader'],
      },
    ],
  },
  externals: {
    'react': {
      root: 'React',
      commonjs2: 'React',
      commonjs: 'React',
      amd: 'React'
    },
    'react-dom': {
      root: 'ReactDOM',
      commonjs2: 'ReactDOM',
      commonjs: 'ReactDOM',
      amd: 'ReactDOM'
    },
    'lucide-react': {
      root: 'LucideReact',
      commonjs2: 'LucideReact',
      commonjs: 'LucideReact',
      amd: 'LucideReact'
    }
  },
  optimization: {
    minimize: true,
  },
  performance: {
    maxEntrypointSize: 512000,
    maxAssetSize: 512000,
  },
};
