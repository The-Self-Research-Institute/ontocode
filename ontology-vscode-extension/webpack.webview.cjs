const path = require('path');
const HtmlWebpackPlugin = require('html-webpack-plugin');
const MiniCssExtractPlugin = require('mini-css-extract-plugin'); // Make sure this is correctly required at the top
const CssMinimizerPlugin = require('css-minimizer-webpack-plugin');
const TerserPlugin = require('terser-webpack-plugin');

module.exports = (env, argv) => {
  const isProduction = argv.mode === 'production';

  return {
    mode: isProduction ? 'production' : 'development',
    entry: './webview-src/index.tsx', // Make sure this is .tsx now!
    output: {
      path: path.resolve(__dirname, 'webview-src/dist'),
      filename: 'static/js/[name].js',
      clean: true,
      publicPath: '/'
    },
    resolve: {
      extensions: ['.js', '.jsx', '.ts', '.tsx'],
    },
    module: {
      rules: [
        {
          test: /\.(js|jsx|ts|tsx)$/,
          exclude: /node_modules/,
          use: {
            loader: 'babel-loader',
            options: {
              presets: [
                '@babel/preset-env',
                '@babel/preset-react',
                '@babel/preset-typescript'
              ],
            },
          },
        },
        {
          test: /\.css$/,
          use: [
            MiniCssExtractPlugin.loader, // Correctly uses the loader
            'css-loader',
          ],
        },
        {
          test: /\.(png|svg|jpg|jpeg|gif)$/i,
          type: 'asset/resource',
          generator: {
            filename: 'static/media/[name].[hash][ext]'
          }
        },
      ],
    },
    plugins: [
      // ⭐⭐⭐ ENSURE THIS IS PRESENT AND CORRECT ⭐⭐⭐
      new HtmlWebpackPlugin({
        template: './webview-src/index.html',
        filename: 'index.html',
        inject: 'body',
        minify: isProduction ? {
          removeComments: true,
          collapseWhitespace: true,
          removeRedundantAttributes: true,
          useShortDoctype: true,
          removeEmptyAttributes: true,
          removeStyleLinkTypeAttributes: true,
          keepClosingSlash: true,
          minifyCSS: true,
          minifyJS: true,
          minifyURLs: true,
        } : false,
      }),
      // ⭐⭐⭐ THIS IS THE MISSING/INCORRECT LINE ⭐⭐⭐
      new MiniCssExtractPlugin({
        filename: 'static/css/[name].css',
      }),
      // ⭐⭐⭐ Ensure CssMinimizerPlugin and TerserPlugin are correctly defined if you want them for production builds ⭐⭐⭐
      // (They are usually placed here as well)
    ],
    optimization: {
      minimize: isProduction,
      minimizer: [
        new TerserPlugin({
          terserOptions: {
            format: {
              comments: false,
            },
          },
          extractComments: false,
        }),
        new CssMinimizerPlugin(),
      ],
      splitChunks: {
        chunks: 'all',
        name: false,
      },
    },
    devServer: {
      static: {
        directory: path.join(__dirname, 'webview-src/public'),
      },
      compress: true,
      port: 3000,
      hot: true,
      open: false
    },
  };
};