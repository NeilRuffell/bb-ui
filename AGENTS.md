# Repository-wide contributor guidance

## Original BlackBerry OS 6 icon identification

The canonical, searchable reference for original numbered BBOS6 icon assets is
[`assets/bbos6/icon-index.csv`](assets/bbos6/icon-index.csv). This rule applies
to **all** BB UI components and the independent `music-app/`, not only the Music
menu.

**Whenever an original numbered BBOS6 icon is positively identified, used,
or assigned a verified new meaning, update the index in the same change.**
Do not leave the mapping solely in chat, source-code comments, screenshots,
or application-specific documentation.

- Search the index by descriptive name or keyword **before** looking through
  the numbered PNG collection or assigning a resource.
- Keep the original `assets/bbos6/icons/<number>.png` filenames and bytes
  unchanged. Android-packaged copies may use readable resource names.
- Each original filename has one index row. Update existing rows instead of
  creating duplicates; keep the rows sorted by their numeric filenames.
- Fields: `filename` (original numbered PNG), `name` (verified meaning),
  `keywords` (searchable aliases and contexts), `android_resource`
  (readable Android drawable resource name, if used; otherwise blank).
- Add only verified identifications. Do not guess meanings or assign labels to
  unidentified icons. Confirm an icon against actual BBOS6 references or a
  documented, verified mapping before recording it.
- When a mapping changes or a new one is established, update related
  implementation documentation as necessary, but keep the index canonical.

Examples:
`grep -i 'album' assets/bbos6/icon-index.csv`
`grep -i '350.png' assets/bbos6/icon-index.csv`
