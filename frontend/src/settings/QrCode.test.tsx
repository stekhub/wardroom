import { render, screen } from '@testing-library/react'
import jsQR from 'jsqr'
import { expect, test } from 'vitest'
import { QrCode } from '@/settings/QrCode'
import { qrCodeLayout } from '@/settings/qrCodeLayout'

const OTPAUTH_URI =
  'otpauth://totp/Wardroom%3Aowner?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP' +
  '&issuer=Wardroom&algorithm=SHA1&digits=6&period=30'

const PIXELS_PER_MODULE = 4

/**
 * Reads the drawn QR code like a camera would: paints the squares of the path and the area of the
 * logo into a picture and decodes it.
 *
 * @param logoIsDark whether the area of the logo is painted dark or light; a real logo is partly both
 */
function decodeDrawnCode(code: HTMLElement, logoIsDark: boolean): string | undefined {
  const viewBox = (code.getAttribute('viewBox') ?? '').split(' ').map(Number)
  const left = viewBox[0] ?? Number.NaN
  const top = viewBox[1] ?? Number.NaN
  const extent = viewBox[2] ?? Number.NaN
  if (Number.isNaN(left) || Number.isNaN(top) || Number.isNaN(extent)) {
    throw new Error('The QR code has no view box')
  }
  const width = extent * PIXELS_PER_MODULE
  const picture = new Uint8ClampedArray(width * width * 4).fill(255)

  function paint(x: number, y: number, size: number, dark: boolean) {
    const firstColumn = Math.round((x - left) * PIXELS_PER_MODULE)
    const firstRow = Math.round((y - top) * PIXELS_PER_MODULE)
    const pixels = Math.round(size * PIXELS_PER_MODULE)
    for (let row = firstRow; row < firstRow + pixels; row++) {
      for (let column = firstColumn; column < firstColumn + pixels; column++) {
        const offset = (row * width + column) * 4
        picture.fill(dark ? 0 : 255, offset, offset + 3)
      }
    }
  }

  const path = code.querySelector('path')?.getAttribute('d') ?? ''
  for (const square of path.matchAll(/M(\d+) (\d+)h1v1h-1z/g)) {
    paint(Number(square[1]), Number(square[2]), 1, true)
  }
  const logo = code.querySelector('image')
  if (logo === null) {
    throw new Error('The QR code has no logo')
  }
  paint(
    Number(logo.getAttribute('x')),
    Number(logo.getAttribute('y')),
    Number(logo.getAttribute('width')),
    logoIsDark,
  )
  return jsQR(picture, width, width)?.data
}

test('the QR code shows the logo in its middle', () => {
  render(<QrCode text={OTPAUTH_URI} label="QR code for the authenticator app" />)

  const code = screen.getByRole('img', { name: 'QR code for the authenticator app' })
  const logo = code.querySelector('image')
  expect(logo).toHaveAttribute('href', expect.stringContaining('wardroom-logo'))
  const [left, , extent] = (code.getAttribute('viewBox') ?? '').split(' ').map(Number)
  const middle = Number(left) + Number(extent) / 2
  expect(Number(logo?.getAttribute('x')) + Number(logo?.getAttribute('width')) / 2).toBe(middle)
  expect(Number(logo?.getAttribute('y')) + Number(logo?.getAttribute('height')) / 2).toBe(middle)
})

test('the QR code can be read with the logo in its middle', () => {
  render(<QrCode text={OTPAUTH_URI} label="QR code" />)
  const code = screen.getByRole('img', { name: 'QR code' })

  expect(decodeDrawnCode(code, false)).toBe(OTPAUTH_URI)
  expect(decodeDrawnCode(code, true)).toBe(OTPAUTH_URI)
})

test('the QR code of a long username can be read too', () => {
  const longUri = OTPAUTH_URI.replace('owner', 'a'.repeat(64))
  render(<QrCode text={longUri} label="QR code" />)

  expect(decodeDrawnCode(screen.getByRole('img', { name: 'QR code' }), true)).toBe(longUri)
})

test('the modules below the logo are left out, and the logo covers a small part of the code', () => {
  const layout = qrCodeLayout(OTPAUTH_URI)

  expect(layout.size % 2).toBe(1)
  expect(layout.logoSize % 2).toBe(1)
  expect(layout.logoStart).toBe((layout.size - layout.logoSize) / 2)
  expect(layout.logoSize / layout.size).toBeLessThan(0.25)
  for (let row = layout.logoStart; row < layout.logoStart + layout.logoSize; row++) {
    for (let column = layout.logoStart; column < layout.logoStart + layout.logoSize; column++) {
      expect(layout.dark[row]?.[column]).toBe(false)
    }
  }
})
