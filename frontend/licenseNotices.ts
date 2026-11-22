import fs from 'node:fs'
import path from 'node:path'
import type { Plugin } from 'vite'

/** Where the notices are published, next to the application. */
export const LICENSE_NOTICES_FILE = 'licenses/third-party-licenses.txt'

type PackageNotice = { name: string; version: string; license: string; text: string | null }

/**
 * Writes the license notices of every package whose code or files are in the built frontend into
 * one text file. Most of these licenses ask to keep the notice in every copy, and the bundler drops
 * the notices from the minified script.
 *
 * A package counts when a module of a script, or a file such as a font, comes from it. Packages
 * that only reach the stylesheet through an `@import` leave no trace in the bundle; they are
 * named in `stylesheetPackages`.
 */
export function licenseNotices(stylesheetPackages: string[]): Plugin {
  return {
    name: 'wardroom-license-notices',
    apply: 'build',
    generateBundle(_options, bundle) {
      const packageDirectories = new Set<string>(
        stylesheetPackages.map((name) =>
          fs
            .realpathSync(path.join(import.meta.dirname, 'node_modules', name))
            .replaceAll('\\', '/'),
        ),
      )
      for (const output of Object.values(bundle)) {
        const sources = output.type === 'chunk' ? output.moduleIds : output.originalFileNames
        for (const source of sources) {
          const directory = packageDirectory(source)
          if (directory !== null) {
            packageDirectories.add(directory)
          }
        }
      }
      const notices = new Map<string, PackageNotice>()
      for (const directory of packageDirectories) {
        const notice = readNotice(directory)
        notices.set(`${notice.name}@${notice.version}`, notice)
      }
      if (notices.size === 0) {
        this.error(
          'No third-party package was found in the bundle; the license notices would be empty.',
        )
      }
      this.emitFile({
        type: 'asset',
        fileName: LICENSE_NOTICES_FILE,
        source: noticesText([...notices.values()]),
      })
    },
  }
}

/**
 * The directory of the package a module or file comes from, or null for the application's own
 * sources. With pnpm the path has several `node_modules`; the package is the one after the last.
 */
export function packageDirectory(source: string): string | null {
  // Ids of virtual modules start with a zero byte; ids can carry a query after the path.
  const filePath = source.replace(/^\0/, '').split('?')[0].replaceAll('\\', '/')
  const marker = '/node_modules/'
  const start = filePath.lastIndexOf(marker)
  if (start < 0) {
    return null
  }
  const parts = filePath.slice(start + marker.length).split('/')
  const nameParts = parts[0].startsWith('@') ? 2 : 1
  if (parts.length <= nameParts) {
    return null
  }
  return filePath.slice(0, start + marker.length) + parts.slice(0, nameParts).join('/')
}

function readNotice(directory: string): PackageNotice {
  const manifest = JSON.parse(fs.readFileSync(path.join(directory, 'package.json'), 'utf8')) as {
    name: string
    version: string
    license?: string
  }
  const licenseFile = fs
    .readdirSync(directory)
    .filter((file) => /^(licen[cs]e|copying)(\..*)?$/i.test(file))
    .sort()
    .at(0)
  return {
    name: manifest.name,
    version: manifest.version,
    license: manifest.license ?? 'not stated',
    text:
      licenseFile === undefined
        ? null
        : fs.readFileSync(path.join(directory, licenseFile), 'utf8').trim(),
  }
}

export function noticesText(notices: PackageNotice[]): string {
  const sorted = [...notices].sort((first, second) => first.name.localeCompare(second.name))
  const sections = sorted.map((notice) =>
    [
      `${notice.name} ${notice.version}`,
      `License: ${notice.license}`,
      '',
      notice.text ?? 'The package contains no license file.',
    ].join('\n'),
  )
  return [
    'Third-party software in the Wardroom web application',
    '',
    'The web application of Wardroom contains the following packages. Their license notices follow.',
    '',
    ...sections.flatMap((section) => ['='.repeat(80), '', section, '']),
  ].join('\n')
}
