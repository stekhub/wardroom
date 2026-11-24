// Sets the theme before the page is drawn, so a dark page does not flash white while it loads
// (spec 013). It is a file and not an inline script, because the Content Security Policy allows
// no inline scripts. The application takes over in src/theme/themeState.ts.
;(function () {
  var choice = null
  try {
    choice = window.localStorage.getItem('wardroom.theme')
  } catch (e) {
    // The browser blocks storage: System is used.
  }
  var dark =
    choice === 'dark' ||
    (choice !== 'light' && window.matchMedia('(prefers-color-scheme: dark)').matches)
  if (dark) {
    document.documentElement.classList.add('dark')
  }
})()
