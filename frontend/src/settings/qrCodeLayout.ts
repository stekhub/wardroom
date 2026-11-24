import qrcode from 'qrcode-generator'

/**
 * A QR code as it is drawn: its modules, and the square in the middle that is left free for the
 * logo (spec 012).
 */
export interface QrCodeLayout {
  /** The number of modules in each direction. */
  size: number
  /** `dark[row][column]` tells whether the module is dark. Modules below the logo are light. */
  dark: boolean[][]
  /** The first row and column of the square for the logo. */
  logoStart: number
  /** The number of modules of the square for the logo in each direction. */
  logoSize: number
}

/**
 * How much of the width the logo takes. With the highest error correction a code stays readable
 * while up to 30 % of it is covered; a square of this width covers about 5 %.
 */
const LOGO_SHARE = 0.22

/**
 * Lays out the QR code of a text, with the highest error correction, so that the logo in the
 * middle does not keep an authenticator app from reading it.
 */
export function qrCodeLayout(text: string): QrCodeLayout {
  const code = qrcode(0, 'H')
  code.addData(text, 'Byte')
  code.make()
  const size = code.getModuleCount()
  // The square has an odd number of modules, so it sits exactly in the middle of the odd-sized code.
  const logoSize = Math.floor((size * LOGO_SHARE) / 2) * 2 + 1
  const logoStart = (size - logoSize) / 2
  const dark: boolean[][] = []
  for (let row = 0; row < size; row++) {
    const darkInRow: boolean[] = []
    for (let column = 0; column < size; column++) {
      const belowLogo =
        row >= logoStart &&
        row < logoStart + logoSize &&
        column >= logoStart &&
        column < logoStart + logoSize
      darkInRow.push(!belowLogo && code.isDark(row, column))
    }
    dark.push(darkInRow)
  }
  return { size, dark, logoStart, logoSize }
}

/** The dark modules as one SVG path, one square of the size 1 per module. */
export function qrCodePath(layout: QrCodeLayout): string {
  const squares: string[] = []
  layout.dark.forEach((darkInRow, row) => {
    darkInRow.forEach((isDark, column) => {
      if (isDark) {
        squares.push(`M${String(column)} ${String(row)}h1v1h-1z`)
      }
    })
  })
  return squares.join('')
}
