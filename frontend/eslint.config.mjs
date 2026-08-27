import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { FlatCompat } from '@eslint/eslintrc';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const compat = new FlatCompat({ baseDirectory: __dirname });

const eslintConfig = [
  ...compat.extends('next/core-web-vitals', 'next/typescript'),
  {
    ignores: ['.next/**', 'node_modules/**', 'next-env.d.ts'],
  },
  {
    rules: {
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      '@typescript-eslint/consistent-type-imports': [
        'warn',
        { prefer: 'type-imports', fixStyle: 'inline-type-imports' },
      ],
      // Money must never be rendered by hand — always MoneyDisplay / formatMoney.
      'no-restricted-syntax': [
        'warn',
        {
          selector: "BinaryExpression[operator='/'][right.value=100]",
          message:
            'Do not divide by 100 to render money. Use formatMoney()/MoneyDisplay — currency exponents vary (JPY=0, KWD=3).',
        },
      ],
    },
  },
];

export default eslintConfig;
