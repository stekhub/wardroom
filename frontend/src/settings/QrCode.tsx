import { useMemo } from 'react'
import logo from '@/assets/wardroom-logo.svg'
import { qrCodeLayout, qrCodePath } from '@/settings/qrCodeLayout'

/** The light border every QR code needs around it, in modules. */
const QUIET_ZONE = 4

/**
 * A QR code with the Wardroom logo in the middle (spec 012). It is always dark on white, whatever
 * the theme, because that is what cameras read best.
 */
export function QrCode({ text, label }: { text: string; label: string }) {
  const layout = useMemo(() => qrCodeLayout(text), [text])
  const path = useMemo(() => qrCodePath(layout), [layout])
  const extent = layout.size + 2 * QUIET_ZONE
  // The logo keeps half a module of white around it.
  const logoInset = 0.5

  return (
    <svg
      role="img"
      aria-label={label}
      viewBox={`${String(-QUIET_ZONE)} ${String(-QUIET_ZONE)} ${String(extent)} ${String(extent)}`}
      className="size-56 max-w-full rounded-md"
      shapeRendering="crispEdges"
    >
      <rect x={-QUIET_ZONE} y={-QUIET_ZONE} width={extent} height={extent} fill="#ffffff" />
      <path d={path} fill="#000000" />
      <image
        href={logo}
        x={layout.logoStart + logoInset}
        y={layout.logoStart + logoInset}
        width={layout.logoSize - 2 * logoInset}
        height={layout.logoSize - 2 * logoInset}
      />
    </svg>
  )
}
