# PED Mobile design system

Scope: Kotlin / Jetpack Compose Android app, including login, all capture types,
manual uploads and pre-inspection tally. Preserve the established PED navy/gold
identity. UI/UX Pro Max's Minimalism & Swiss Style guidance fits this field tool;
its generic marketing-page pattern and alternate palette are not used.

- Source of truth: `ui/PedTheme.kt`, semantic Material 3 colors.
- Light: navy #172B4D; gold #E8C888; ivory #FCFBF8; white cards;
  body #1B2B44; secondary text #626B79; control outline #8993A2.
- Dark: background #111C2C; cards #19283D; text #EAF0F8;
  secondary text #BDC8D8; gold primary action.
- Bundled Inter 4.1 typography (400/500/600/700), available offline. Body 16sp; helper text 14sp; native text
  scaling. Titles wrap and headers grow with content.
- 8dp spacing rhythm; 16dp phone gutters; 720dp maximum content width; 560dp login.
- Android controls are at least 48dp tall. Use Material outlined vector icons,
  visible labels, native selected/disabled semantics and standard ripple feedback.
- Capture Evidence / Inspection Tally / Uploads are the three destinations in a navy hamburger drawer, with Account & Settings at the bottom. Capture Evidence opens first; no dashboard or bottom navigation. At widths >=1000dp with standard text scaling, the sidebar stays visible.
- Drawer rows grow with text, and the entire drawer scrolls in landscape or at large text sizes. Tally retains voyage / saved-tally subnavigation and a contextual Back action.
- Uploads combines evidence and tally records with Pending / Failed / Uploaded filters and a single manual Upload all action. Stored records remain account-scoped; server confirmations distinguish Uploaded from locally saved work.
- Save actions scroll with capture forms and never cover fields. Switching destinations retains drafts; discarding a tally is explicit.
- Capture sections: identity, photo evidence, save. Required fields and missing
  photos have inline errors; changes are retained on failed saves.
- Capture type changes, clearing and sign-out explain loss of unsaved work before
  confirmation. Navigation retains the active form; rotation retains photo drafts in a ViewModel; account-scoped atomic draft storage supports reopening after process termination.
- Offline availability, saved-on-device and server-confirmed states use words as
  well as color. Sync stays manual. Never expose endpoint paths in product cards.
- Dark mode and 200% text scale must remain usable in portrait and landscape.
  Scroll containers respect keyboard and system insets. Use native motion settings.
