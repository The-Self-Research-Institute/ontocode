'use strict';

const { app } = require('electron');
const path = require('path');
const { createProcessGuard } = require('./ProcessGuard');

const userData = app.getPath('userData');

module.exports = createProcessGuard({
    registryFile: path.join(userData, 'children.json'),
    marker: userData,
});
