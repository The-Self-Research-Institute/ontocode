import typescriptEslint from "@typescript-eslint/eslint-plugin";
import tsParser from "@typescript-eslint/parser";

const MAX_LINES_RULE_OPTIONS = { skipBlankLines: true, skipComments: false };

export default [{
    ignores: [
        "out/**",
        "dist/**",
        "webview-src/**",
        "node_modules/**",
    ],
}, {
    files: ["**/*.ts"],
}, {
    plugins: {
        "@typescript-eslint": typescriptEslint,
    },

    languageOptions: {
        parser: tsParser,
        ecmaVersion: 2022,
        sourceType: "module",
    },

    rules: {
        curly: "warn",
        eqeqeq: "warn",
        "no-throw-literal": "warn",
        semi: "warn",
    },
}, {
    files: ["src/**/*.ts"],
    ignores: ["src/**/*.test.ts", "src/**/*.spec.ts", "src/test/**"],
    rules: {
        "max-lines": ["warn", { max: 200, ...MAX_LINES_RULE_OPTIONS }],
    },
}, {
    files: ["src/**/*.test.ts", "src/**/*.spec.ts", "src/test/**/*.ts"],
    rules: {
        "max-lines": ["warn", { max: 400, ...MAX_LINES_RULE_OPTIONS }],
    },
}];