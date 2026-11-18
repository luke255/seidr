# What's new in Seiðr

Newest first. Each version's list is what the app shows under What's new and
what its GitHub release says. Write for people using the app: what changed for
them, not how.

## 1.9.0

- Clean mode, for when there are passengers in the car: the shield in the queue (or in Android Auto) plays only the songs approved for your kids and skips the rest until you turn it off. While it's on, the queue and Now Playing say so.
- Tap an album's or playlist's art to see it full screen on its own colours. Swipe back to fade it away.
- Playlists list their songs by artist until you pick an order for one. Settings → Library → Playlist order changes that default.
- A playlist's own picture now shows on the visualiser of a device you're playing on, even one that isn't signed in.

## 1.8.1

- Play on's device list scrolls, so every device is reachable on small screens like a Fold's cover screen.

## 1.8.0

- Play on Google Cast speakers, speaker groups and TVs: they're in Play on, beside your other devices. Your phone sends the music to the speaker itself, so downloaded songs play even without the server, and the speaker never needs your login. Keep your phone on the same Wi-Fi while it plays.
- On Android TV, Seiðr stays ready in the background, so the TV is always in Play on, even when Seiðr isn't open there.

## 1.7.1

- Star albums, artists and playlists to keep them close: they gather in a Starred row on Home, newest first. The star sits at the top of each page. Starring playlists needs Navidrome.
- A playlist with a picture of its own takes its colours from it, the way an album takes them from its cover.
- Albums and playlists fade into their colours more gently.
- The visualiser has a page showing the picture of the playlist you're playing, when it has one of its own. Swipe up from the track page to see it.
- The filter rows in Library and Search fade at the edge when there are more filters to scroll to, so Shared playlists are easier to find.
- In Library, Shared now shows only other people's playlists, and turning it off shows only yours.

## 1.7.0

- Smart playlists: make them and edit their rules here, no other app needed (Navidrome). Create playlist has a Smart playlist option, and a smart playlist's ⋮ menu has Edit rules, plus Rename, Earlier versions and Delete. Rules sit in groups that match all or any of them, on title, artist, year, date added, plays, loved, other playlists and more, with a sort and a limit. Saving says how many songs now match, with Undo.
- Seiðr keeps any rule it can't edit exactly as it is, and saves only if nobody has changed the rules meanwhile. Earlier versions keeps each version of a smart playlist's rules for 90 days, and Restore puts one back. A deleted smart playlist comes back with its rules too.

## 1.6.14

- Downloaded songs keep their album art and lyrics when your server is out of reach, including while it's updating or restarting. Albums and playlists you've downloaded from keep their pictures too. Lyrics for songs you've already downloaded are fetched in the background on your next sync.
- Give a playlist a picture of its own: Change picture in the playlist's ⋮ menu (Navidrome 0.61 or later). Pinch and drag to frame it, or choose Fill or Fit; around a picture that doesn't fill the square, Seiðr adds a background in its colours. Kids see the pictures on their own playlists and their grown-up's.
- Fixed missing cover art in Android Auto and on the lock screen for songs that use their album's art.

## 1.6.13

- Seiðr is much smaller to download and update (about 14 MB, down from 49). The visualiser's presets now come straight from the projectM project's collection, once: by themselves on Wi-Fi, or when you first open the visualiser.
- Sleep timer, in Now Playing's ⋮ menu: stop the music after 15 to 90 minutes, with a slow fade, or at the end of the song.
- Save mobile data (Settings, off unless you turn it on): on mobile data, songs that aren't downloaded stream as MP3, at a bitrate that suits the connection. Downloads and Wi-Fi stay the original files. Seiðr checks your server can convert music first (Navidrome needs ffmpeg).
- Even out volume (Settings): uses your music's ReplayGain tags, with album levels when an album plays in order and song levels otherwise. Songs without tags are turned down to match. It's on by itself once most of your library is tagged (Settings shows how much is), unless you choose.
- Settings → About → Share a log: Seiðr's recent log for a bug report, with logins, server addresses and device names taken out. It shows you the whole thing before you share it.
- Home's rows have shorter names: Playlists, Shared, New, Albums and Artists.
- Albums no longer have a heart of their own, and Home's Saved albums row is gone. To keep an album, download it or add it to a playlist; Loved songs are unchanged.
- Swiping back from an album, the navigation bar's colours now follow the page as it slides away.
- On the Fold's inner screen and other wide screens, swiping back from an album, artist or playlist now slides the page away with your finger.
- If a device can't be reached when you pick it to play on, the device list now stays open and says why, so you can try again.
- Swiping back from Search or Library to Home now slides the page away too, and so does going back from Log in.
- On a device someone else is playing on, a back swipe pulls the queue down with your finger.

## 1.6.12

- Album pages take their colours from the cover, as Now Playing does: the background, buttons and highlights all match. Playlists and artists now keep your theme colour.

## 1.6.11

- Red is now a true red rather than coral, and it's the colour Seiðr uses when your phone doesn't offer one.
- Each login keeps its own colour, so a shared device (a TV, say) no longer keeps the last person's. Signed out, Seiðr is always red.
- Sorting playlists by Recent now lists the ones you've never played A–Z, after the ones you have. A playlist you make still goes to the top.

## 1.6.10

- Playlist changes made anywhere (another device, Navidrome's web page, or while Seiðr was in the background) now show straight away: the open playlist, song counts on Home and Library, and Android Auto all follow.
- Playlists created or deleted elsewhere appear or disappear within a minute while Seiðr is open.
- Albums, artists and Loved refresh by themselves after the server rescans its music.

## 1.6.9

- Kids see newly approved music within a minute while Seiðr is open, without restarting it.

## 1.6.8

- Home and Library update by themselves when music is added to or removed from the server, even if it happened while Seiðr was closed.
- Home's Most played shelves follow what you play, and Saved albums follows what you save.
- Something new at the front of a Home shelf now shows, instead of arriving just out of view.

## 1.6.7

- Home's shelves show 15 each and end in an arrow that opens the whole list in Library, in the same order.
- Home has a Most played artists shelf.
- Home's playlists are always most recent first.
- Albums can be sorted by Recently saved.

## 1.6.6

- Changing Library's order starts the list from the top.

## 1.6.5

- Now Playing follows your finger as you swipe it down, and springs back if
  you change your mind.
- Covers slide and colours change faster when the track changes.
- Sort a playlist's songs by title, artist, album, year or most played. Each
  playlist remembers its own; the playlist itself keeps its order.
- Library can sort albums and artists by most played, and remembers each
  tab's order. Ties go by artist, then album, then track.
- Home and Library share one order for playlists, now with Recent there too.
- The visualiser's track page on a TV no longer shows a faint box around the
  cover.
- A playlist's ⋮ menu is in a tidier order, with Delete last.
- Undo, straight after removing a song from a playlist (it goes back in its
  place) or deleting a playlist.
- Earlier versions, in a playlist's ⋮ menu: what it was before each change,
  from any app, for 90 days. Save one as a copy to get it back.
- Recently deleted playlists, in Settings → Library: save a copy of one
  deleted in the last 90 days, from any app.

## 1.6.4

- Shared playlists: share one of yours with everyone on the server from its
  ⋮ menu, and save a copy of someone else's to make it your own. Other
  people's playlists have their own shelf on Home, and Library shows them
  with the Shared filter.
- Visualiser: the preset page can go back to the last preset or on to the
  next. Skipping tracks and play/pause work with a page up, covers slide on
  a skip like Now Playing's (on the track page, drag the cover to skip), and
  the page dots fade away. The transport page is gone: swipes do its job.
- Download has moved into the ⋮ menu on playlists and albums.
- Downloads show one steady progress bar for the whole batch, instead of
  flashing and sitting near empty for each song.
- Skipping tracks is much smoother in Now Playing, especially on TVs.

## 1.6.3

- Android backups and phone-to-phone transfers now bring your settings,
  queue and recently played with them. Your password never goes: on the new phone,
  Seiðr asks for it with your server and username already filled in.
  Downloads start again from the Download switches.
- Adding a kid now says plainly that only Seiðr filters what they see, so
  their login itself shouldn't be given to them.
- If a phone's secure key store isn't working, Settings now says that your
  login is saved without encryption, rather than doing so silently.

## 1.6.2

- Lists sort the way your language expects: accented names sit with their
  letter, and Chinese, Japanese and Korean names follow the language's own
  order instead of character codes.

## 1.6.1

- A signed-out device now says plainly that it's ready to play, and how to
  play on it from another device. Log in is still there, just quieter.
- 19 more languages: Catalan, Chinese (Simplified and Traditional), Czech,
  Danish, Finnish, Greek, Hungarian, Japanese, Korean, Norwegian, Polish,
  Portuguese (Portugal), Romanian, Russian, Spanish (Latin America),
  Swedish, Turkish and Ukrainian. Choose one under Android's per-app
  language setting. They're machine-written: corrections are welcome.
- Clearer wording in a few places.

## 1.6.0

- **Update all your devices.** Playing on another device of yours now uses a
  stronger key, so devices on this version and on 1.5.0 won't find each
  other until both are updated. Signing in a device still works across
  versions.
- A signed-out device now asks before anyone signs it in: it shows who is
  asking, as whom and on which server, and nothing happens until you allow it.
- Your password and your kids' logins are encrypted on the device, and
  nothing is copied when you move to a new phone.
- A download the server couldn't provide is no longer saved as a broken
  track; it fails with the server's reason and tries again later.
- Free space for downloads counts space Android can clear for you.
- Kids' logins: cover art shown in the car follows the kids' lists too.
- What's new: this list, in Settings → About and on the update prompt.
- Seiðr speaks German, French, Spanish, Dutch, Brazilian Portuguese and
  Italian, following your phone's language (or Seiðr's own, in Android's
  app language settings).

## 1.5.0

- Admins can browse as one of their kids (Settings → Kids, the eye button):
  read-only, while your own login keeps playing and syncing.
- A kid's device no longer re-downloads the kids' lists on every sync, only
  when they change.
- "How kids' accounts work" explains it all in one place.

## 1.4.2

- Other people's private playlists are hidden, even for admins; Settings →
  Library can show them.
- Signing in with a login that owns Bad: playlists turns Reporting on.

## 1.4.1

- Home's Playlists shelf shows every playlist, newest activity first, so a
  new one no longer falls off the end.
- New playlists are private.

## 1.4.0

- Loved: the songs you've loved as a playlist of their own, with Play,
  Shuffle and Download. It heads your playlists and the car's Playlists tab.

## 1.3.0

- Kids' accounts: add your kids' logins in Settings → Kids and sign their
  devices in. They see and play only what your "Seidr: Kids" playlists
  allow.

## 1.2.0

- The visualiser has pages: swipe up or down for the preset, controls,
  track, lyrics, queue or logo.
- A one-time warning about flashing lights.

## 1.1.0

- Play on a signed-out device, or sign it in, from any device signed in to
  your server. Both screens show a code to check first.
- Visualiser favourites and hidden presets are kept per person.
- QR pairing is gone.

## 1.0.4

- Login fits wide screens (TVs, the Quest, landscape phones).
- The Quest's camera preview shows inside its panel.

## 1.0.3

- The sign-in banner uses Seiðr's own colours.
- On a Quest, the camera prompt waits until you've read the note about it.

## 1.0.2

- A banner on the login screen.
- Home's Playlists shelf no longer loads out of view.
- On a Quest, scanning asks for the headset cameras too.

## 1.0.1

- The visualiser's readout moved to the top left, clear of the track.

## 1.0.0

- First release as Seiðr.
