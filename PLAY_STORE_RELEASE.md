# Google Play release checklist: Ace Counter Pick

Work through these in order. Items marked ✅ are already done in this project.

## 0. Before anything: decide these (they can't be undone later)
- [ ] **Package name.** It's currently `com.mlbb.counteroverlay` in `app/build.gradle.kts` (`applicationId`). A package name can never change after the first upload. Using "mlbb" in it can draw a trademark complaint, so consider something neutral like `com.yourname.counterpick`. If you change it, change only `applicationId`, not `namespace`.
- [ ] **App name.** It's currently "Ace Counter Pick". Don't put "Mobile Legends" or "MLBB" in the title or icon: Play rejects store listings that look like they come from another company.
- [ ] **Data provider permission.** The hosted API's terms say commercial use needs an agreement and the service may rate-limit or block heavy traffic. Email **hello@rone.dev**: say you're publishing a free app, give expected users, and confirm it's OK. If you add ads or paid features, this is required.
- [ ] **Hero images.** Hero and item icons are Moonton's artwork. Many fan tools use them, but Moonton can file an IP complaint that gets the app removed. Safest: keep the "Unofficial, not affiliated with Moonton" disclaimer (✅ already in the app and the privacy policy) and keep hero art out of the app icon and feature graphic (✅ the provided ones use none).

## 1. Developer account
- [ ] Create a Google Play Console account: https://play.google.com/console (one-time US$25 fee, identity verification).
- [ ] **New personal accounts must run a closed test with at least 12 testers for 14 days in a row** before production is unlocked. Start this early. Friends or your gaming group work fine: collect their Gmail addresses.

## 2. Signing key (one time, keep it safe forever)
```bash
keytool -genkeypair -v -keystore upload-keystore.jks -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000
```
- [ ] Back the `.jks` file and passwords up somewhere safe (password manager plus a cloud drive). Never commit them; `.gitignore` already excludes them ✅.
- [ ] Enable **Play App Signing** when creating the app (default). Google then holds the real app key, and yours is only the upload key, so a lost upload key can be reset.

**To build locally**, create `keystore.properties` in the project root:
```
RELEASE_STORE_FILE=upload-keystore.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=upload
RELEASE_KEY_PASSWORD=...
```
then run `gradle bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.

**To build on GitHub** (`.github/workflows/android-release.yml` ✅), add 4 repo secrets (Settings → Secrets and variables → Actions):
| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | the text in `keystore-base64.txt` made by the command below (copy ALL of it) |
| `RELEASE_STORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | `upload` |
| `RELEASE_KEY_PASSWORD` | key password |

Make `keystore-base64.txt` in the folder that holds `upload-keystore.jks`:
- **Windows (PowerShell):** `[Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\upload-keystore.jks")) | Set-Content keystore-base64.txt`
  (the `$PWD\` part matters: without it PowerShell may read the file from a different folder)
- **Mac / Linux:** `base64 -i upload-keystore.jks | tr -d '\n' > keystore-base64.txt`

Open the .txt in Notepad, Ctrl+A, Ctrl+C, paste into the secret. **Delete the .txt afterwards.**

Then push a tag: `git tag v1.2.0 && git push origin v1.2.0`. Download **app-release-aab** from the Actions run. The version code goes up automatically on each run.

## 3. Technical requirements (done in code ✅)
- ✅ Target API 36 (Android 16), required for new apps and updates since Aug 31, 2026
- ✅ Android App Bundle (.aab) release build, R8 shrinking, signed via the workflow
- ✅ Adaptive launcher icon plus monochrome themed icon
- ✅ Edge-to-edge safe main screen (Android 15+)
- ✅ Foreground service declared with type `specialUse` plus a subtype description
- ✅ Clear in-app explanation shown **before** sending the user to the "Display over other apps" setting
- ✅ Privacy policy link inside the app
- ✅ No personal data collected; cache excluded from backups
- ✅ Unit tests run in CI on every build

## 4. Privacy policy (required)
- [ ] Put your contact email in `docs/privacy-policy.html` (replace `YOUR_CONTACT_EMAIL`).
- [ ] Host it: GitHub repo → Settings → Pages → Source "Deploy from a branch", branch `main`, folder `/docs`. URL becomes `https://<user>.github.io/<repo>/privacy-policy.html`.
- [ ] Put that URL in `app/src/main/res/values/strings.xml` → `privacy_policy_url`, and in Play Console → App content → Privacy policy.

## 5. Play Console → App content (all required)
| Form | What to answer |
|---|---|
| **Privacy policy** | URL from step 4 |
| **Ads** | No, the app doesn't contain ads |
| **App access** | All functionality is available without special access |
| **Content rating** | Fill in the questionnaire: category "Reference, News, or Educational" or "Utility", no violence/sexual content/gambling, no user interaction or sharing → usually rated Everyone/PEGI 3 |
| **Target audience** | 13+ (choose 13–15, 16–17 and 18+). Don't select under 13: that triggers the Families policy |
| **Data safety** | "Does your app collect or share user data?" → **No**. Encrypted in transit → Yes (HTTPS). The app itself stores no personal data. (Third-party servers see IP addresses as part of any web request, which Google generally doesn't count as collection by your app; review Google's Data safety help page and answer for your final build.) |
| **Foreground service permissions** | Declare **Special use**. Description: *"The app shows a floating bubble over the game that the user taps to see hero counter tips. The foreground service keeps that user-started overlay alive while the user plays and shows a notification with a Close button."* Google asks for a **short video**: screen-record opening the app → bubble appears → open MLBB → tap bubble → panel → drag bubble to the X. Upload it unlisted to YouTube and paste the link. |
| **Government / financial / health apps** | No |

## 6. Store listing
- **App name** (≤30): `Ace Counter Pick`
- **Short description** (≤80): `Counter the whole enemy team: picks by lane and rank, builds and first picks.`
- **Full description** (≤4000), suggested:

> Draft smarter without leaving your game. Ace Counter Pick floats a small button over your screen. Tap it during the draft, type the enemy hero, and see:
>
> • Counter picks by lane: EXP vs EXP, Gold vs Gold, Mid, Jungle and Roam, ranked by real win-rate data
> • Counter the whole enemy team: add up to 5 enemy picks and get heroes that beat all of them
> • Filter by rank, from Epic to Mythical Glory
> • See why a hero counters: win rate in the matchup plus short tips from official skill data
> • Heroes NOT to pick into the enemy
> • In-game guide counters and heroes that pair well together
> • The most popular core item build, emblem and battle spell for any hero and lane
> • Best first picks per lane, based on the latest ranked win rates
> • Typo-friendly search with hero icons: "lucas" finds Lukas
> • Works offline with saved data; refreshes automatically
>
> Drag the bubble anywhere. Drop it on the X at the bottom of the screen to close it.
>
> Unofficial fan-made tool. Not affiliated with, endorsed or sponsored by Moonton. Mobile Legends: Bang Bang and all hero names and images are trademarks and copyright of Moonton. Hero statistics provided by Rone Arena (arena.rone.dev).

- **Graphics** (in `store/` ✅ starter versions):
  - App icon 512×512 PNG → `store/play-icon-512.png`
  - Feature graphic 1024×500 → `store/play-feature-graphic-1024x500.png`
  - **Phone screenshots: you must take these** (min 2, recommended 4–8, 16:9 or 9:16, at least 1080px on the short side). Suggested shots: hero list with search, counter picks by lane, build card, First Pick tab, bubble over the game lobby.
- **App or game:** App. **Category:** Tools.
- **Contact email:** required, shown publicly.

## 7. Release flow
1. Play Console → Create app → name, default language, App (not game), Free.
2. Finish everything in sections 4–6.
3. **Testing → Closed testing** → create a track → upload the `.aab` → add your 12+ testers' emails → send them the opt-in link. They must stay opted in for **14 days**.
4. After 14 days → **Apply for production access** (answer the short questions about your testing).
5. **Production** → create release → upload the `.aab` (or promote the tested one) → roll out. Review usually takes a few days.

## 8. After launch
- Upload `mapping.txt` (from the release workflow's **r8-mapping** artifact) with each release, so crash reports are readable.
- Watch Play Console → Android vitals for crashes and ANRs.
- Every update needs a higher version code (automatic in the workflow) and must keep meeting the yearly target-API deadline.
