import { describe, expect, it } from 'vitest';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const cli = path.join(root, 'node_modules', 'tailwindcss', 'lib', 'cli.js');
const pluginOnlyClass = String.raw`.bg-\[\#252525\]`;

function build(extraArgs: string[]) {
  return execFileSync(process.execPath, [cli, '-i', 'index.css', ...extraArgs], {
    cwd: root,
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });
}

describe('tailwind content globs', () => {
  it('list the plugin source folders', () => {
    const source = fs.readFileSync(path.join(root, 'tailwind.config.js'), 'utf8');
    expect(source).toContain('../../plugins/*/src/**/*.{ts,tsx}');
  });

  it('generate a class that only a plugin panel uses', () => {
    const appOnly = build(['--content', './index.html,./components/**/*.{ts,tsx},./services/**/*.{ts,tsx},./utils/**/*.{ts,tsx}']);
    const configured = build([]);
    expect(appOnly).not.toContain(pluginOnlyClass);
    expect(configured).toContain(pluginOnlyClass);
  }, 120000);
});
