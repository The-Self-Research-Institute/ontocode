import typescriptEslint from "@typescript-eslint/eslint-plugin";
import tsParser from "@typescript-eslint/parser";

const MAX_LINES_RULE_OPTIONS = { skipBlankLines: true, skipComments: false };
const TEST_GLOBS = ["**/*.test.ts", "**/*.test.tsx", "**/*.spec.ts", "**/*.spec.tsx"];
const HOOK_GLOBS = ["hooks/**/*.ts", "custom-hook/**/*.ts", "**/hooks/**/*.ts"];

export default [{
    ignores: [
        "dist/**",
        "node_modules/**",
    ],
}, {
    files: ["**/*.ts", "**/*.tsx"],
    plugins: {
        "@typescript-eslint": typescriptEslint,
    },
    languageOptions: {
        parser: tsParser,
        ecmaVersion: 2022,
        sourceType: "module",
        parserOptions: {
            ecmaFeatures: { jsx: true },
        },
    },
    rules: {
        curly: "warn",
        eqeqeq: "warn",
        "no-throw-literal": "warn",
        semi: "warn",
    },
}, {
    files: ["**/*.tsx"],
    ignores: [...TEST_GLOBS, ...HOOK_GLOBS],
    rules: {
        "max-lines": ["warn", { max: 200, ...MAX_LINES_RULE_OPTIONS }],
    },
}, {
    files: HOOK_GLOBS,
    ignores: TEST_GLOBS,
    rules: {
        "max-lines": ["warn", { max: 100, ...MAX_LINES_RULE_OPTIONS }],
    },
}, {
    files: ["services/**/*.ts", "utils/**/*.ts", "config/**/*.ts"],
    ignores: [...TEST_GLOBS, ...HOOK_GLOBS],
    rules: {
        "max-lines": ["warn", { max: 200, ...MAX_LINES_RULE_OPTIONS }],
    },
}, {
    files: TEST_GLOBS,
    rules: {
        "max-lines": ["warn", { max: 400, ...MAX_LINES_RULE_OPTIONS }],
    },
}];
