import { expect, test } from 'vitest'
import { noticesText, packageDirectory } from './licenseNotices.ts'

test('a module of a package installed by pnpm belongs to the package after the last node_modules', () => {
  expect(
    packageDirectory(
      'C:\\app\\node_modules\\.pnpm\\react@19.3.1\\node_modules\\react\\cjs\\react.production.js',
    ),
  ).toBe('C:/app/node_modules/.pnpm/react@19.3.1/node_modules/react')
})

test('a scoped package keeps its scope', () => {
  expect(
    packageDirectory(
      '/app/node_modules/.pnpm/@radix-ui+react-slot@1.5.0/node_modules/@radix-ui/react-slot/dist/index.mjs',
    ),
  ).toBe('/app/node_modules/.pnpm/@radix-ui+react-slot@1.5.0/node_modules/@radix-ui/react-slot')
})

test('virtual modules and queries are read as their path', () => {
  expect(packageDirectory('\0/app/node_modules/zod/index.js?commonjs-proxy')).toBe(
    '/app/node_modules/zod',
  )
})

test('the own sources belong to no package', () => {
  expect(packageDirectory('/app/src/main.tsx')).toBeNull()
  expect(packageDirectory('\0vite/modulepreload-polyfill.js')).toBeNull()
})

test('the notices are sorted by name and say when a package has no license file', () => {
  const text = noticesText([
    { name: 'zod', version: '4.6.7', license: 'MIT', text: 'MIT License\n\nCopyright (c) Colin' },
    { name: 'aria-hidden', version: '1.2.6', license: 'MIT', text: null },
  ])

  expect(text.indexOf('aria-hidden 1.2.6')).toBeLessThan(text.indexOf('zod 4.6.7'))
  expect(text).toContain('zod 4.6.7\nLicense: MIT\n\nMIT License\n\nCopyright (c) Colin')
  expect(text).toContain('aria-hidden 1.2.6\nLicense: MIT\n\nThe package contains no license file.')
})
