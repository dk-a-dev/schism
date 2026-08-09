# Content rating questionnaire — Schism 1.3.0

Play Console → App content → Content rating (IARC). Category: **Utility, Productivity,
Communication or Other**. Email on the questionnaire: dev.keshwani345@gmail.com.

| Question | Answer | Why |
| --- | --- | --- |
| Violence, blood, sexual content, nudity | No | No such content anywhere in the app |
| Profanity, crude humour | No | All app copy is written in `strings.xml` and Compose screens |
| Controlled substances (drugs, alcohol, tobacco) | No | Not referenced |
| Gambling, simulated gambling, real-money contests | No | No gambling mechanic of any kind |
| Horror or fear themes | No | — |
| Does the app share the user's current location with other users? | No | The app requests no location permission |
| Does the app allow users to interact or exchange content? | Yes | Group members share expense titles, notes, participant names and item claims through a shared group |
| Does the app allow users to purchase digital goods? | Yes | Schism Plus subscription via Google Play Billing |
| Does the app contain ads? | Yes | One inline banner after the Spending/Insights summaries, removed by Schism Plus |
| Is the app a store or search engine for third-party content? | No | — |
| Does the app provide unrestricted internet access (browser/webview)? | No | Policy links open the system browser via `ACTION_VIEW`; the app embeds no browser |
| Does the app collect or share personal information? | Yes | See `data-safety.md` |
| Is the app designed for children / does it target children? | No | Target audience 18+; the app is an adult finance utility |

## Notes for the "user interaction" follow-ups

- Sharing is scoped to a group the user created or was invited into. There is no public feed, no
  discovery, no direct messaging, no profile browsing, no image or video sharing between users.
- Free-text fields shared with others: group name and information, expense title and notes,
  participant names, claim item names.
- Moderation: none is implemented. Membership is invite-only (group link / participant invite token
  / QR code), which is the mitigation to state if the questionnaire asks.
- Expected outcome: Everyone / PEGI 3 / ESRB Everyone, with an "in-app purchases" and "contains ads"
  interactive-elements flag. Confirm the generated rating in the Console before publishing.
