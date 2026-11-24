import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { SettingsSectionFrame } from '@/settings/SettingsSectionFrame'
import { chooseTheme, type ThemeChoice, useTheme } from '@/theme/themeState'

const THEME_CHOICES: readonly { value: ThemeChoice; label: string }[] = [
  { value: 'light', label: 'Light' },
  { value: 'dark', label: 'Dark' },
  { value: 'system', label: 'System' },
]

/**
 * The settings section "Preferences" (spec 013). Its settings are kept in this browser and take
 * effect at once, so it has no save button.
 */
export function PreferencesSection() {
  const { choice } = useTheme()

  return (
    <SettingsSectionFrame title="Preferences">
      <p className="text-sm text-muted-foreground">
        These settings are stored in this browser. Other browsers and devices keep their own.
      </p>
      <div className="grid gap-3">
        <Label id="theme-label">Theme</Label>
        <RadioGroup
          aria-labelledby="theme-label"
          value={choice}
          onValueChange={(value) => {
            chooseTheme(value as ThemeChoice)
          }}
          className="flex flex-wrap gap-6"
        >
          {THEME_CHOICES.map((themeChoice) => (
            <div key={themeChoice.value} className="flex items-center gap-2">
              <RadioGroupItem value={themeChoice.value} id={`theme-${themeChoice.value}`} />
              <Label htmlFor={`theme-${themeChoice.value}`} className="font-normal">
                {themeChoice.label}
              </Label>
            </div>
          ))}
        </RadioGroup>
        <p className="text-sm text-muted-foreground">
          System follows the light or dark setting of your device.
        </p>
      </div>
    </SettingsSectionFrame>
  )
}
