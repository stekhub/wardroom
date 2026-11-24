import headerLogoDark from '@/assets/wardroom-header-dark.svg'
import headerLogo from '@/assets/wardroom-header.svg'
import logoDark from '@/assets/wardroom-logo-dark.svg'
import logo from '@/assets/wardroom-logo.svg'
import { useTheme } from '@/theme/themeState'

interface LogoProps {
  alt: string
  className?: string
}

/** The logo with the lettering, in the variant of the theme (spec 013). */
export function HeaderLogo({ alt, className }: LogoProps) {
  const { theme } = useTheme()
  return (
    <img src={theme === 'dark' ? headerLogoDark : headerLogo} alt={alt} className={className} />
  )
}

/** The logo without the lettering, in the variant of the theme (spec 013). */
export function LogoMark({ alt, className }: LogoProps) {
  const { theme } = useTheme()
  return <img src={theme === 'dark' ? logoDark : logo} alt={alt} className={className} />
}
