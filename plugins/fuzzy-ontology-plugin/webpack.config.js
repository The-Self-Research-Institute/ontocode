const path = require('path');

module.exports = {
  mode: 'production',
  entry: './src/index.ts',
  output: {
    filename: 'index.js',
    path: path.resolve(__dirname, 'dist'),
    library: {
      name: 'FuzzyOntologyPlugin',
      type: 'umd',
      export: 'default'
    },
    globalObject: 'typeof self !== \'undefined\' ? self : this',
    umdNamedDefine: true
  },
  resolve: {
    extensions: ['.ts', '.tsx', '.js', '.jsx']
  },
  module: {
    rules: [
      {
        test: /\.tsx?$/,
        use: {
          loader: 'ts-loader',
          options: {
            // Only transpile, don't check types to avoid old extension.ts errors
            transpileOnly: true
          }
        },
        exclude: /node_modules/
      }
    ]
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
    }
  }
};
