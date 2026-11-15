# Security

## Supported versions

Only the latest release. Seiðr updates itself, so fixes ship as a new
release rather than as patches to old ones.

## Reporting a vulnerability

Please report privately, through GitHub: the repository's **Security** tab,
then **Report a vulnerability**. Don't open a public issue. You'll get a
reply as soon as I've read it, and credit in the release notes if you'd
like it.

## In scope

- **The app itself**: how it stores your login, talks to your server and
  handles what the server sends back.
- **Playing on and signing in other devices**: the local-network protocol,
  its pairing codes and keys, and anything that lets someone on the same
  network control a device, read a login or sign a device in without being
  allowed to.
- **Kids' accounts**: any way for a kid's device, as Seiðr runs it, to show
  or play something the grown-up's lists don't allow.
- **Self-update**: anything that could get an APK installed that isn't the
  one published in this repository's releases.

Your Navidrome server, Android itself and the libraries Seiðr uses are out of
scope here; please report those to their own projects.

## What kids' accounts do and don't promise

Kids' accounts filter **this app**. A kid's device signed in through Seiðr
shows and plays only what the grown-up's `Seidr: Kids` playlists allow, and
fails closed when it can't tell. They don't restrict the kid's Navidrome
account: with its password, a kid could use Navidrome's web player or any
other app, which filter nothing. That's why the README says to keep the
password to yourself, and it isn't a vulnerability in Seiðr.
