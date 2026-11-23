import { expect, test } from 'bun:test';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { validatedAuthorNames, boardAuthorName } from '../web/sol/board-editor/person-labels.js';
import { normalizeBoardComments, withBoardComments } from '../web/sol/board-comments.js';

// Synthetic public identities; host-side checksum validation has its own JVM regression.
const identity = 'npub1' + 'a'.repeat(58);
const other = 'npub1' + 'b'.repeat(58);
test('late names, rename and replacement removal use only the exact author identity', () => {
  expect(boardAuthorName(identity, {})).toBe('Unknown person');
  let names = validatedAuthorNames({ [identity]: 'Alice', [other]: 'Bob' });
  expect(boardAuthorName(identity, names)).toBe('Alice');
  expect(boardAuthorName(identity.slice(0, 16), names)).toBe('Unknown person');
  names = validatedAuthorNames({ [identity]: 'Renamed' });
  expect(boardAuthorName(identity, names)).toBe('Renamed');
  expect(boardAuthorName(other, names)).toBe('Unknown person');
  expect(boardAuthorName(identity, validatedAuthorNames({}))).toBe('Unknown person');
});
test('bounded labels reject malformed and key-like values and React escapes names', () => {
  for (const name of ['', ' ', identity, 'ab'.repeat(32), 'a'.repeat(257), null, 3]) {
    expect(validatedAuthorNames({ [identity]: name })).toEqual({});
  }
  expect(validatedAuthorNames({ Alice: 'Bob', [identity.slice(0, 16)]: 'Alice' })).toEqual({});
  expect(validatedAuthorNames([])).toEqual({});
  const name = '<img src=x onerror=alert(1)> & "name"';
  const shown = boardAuthorName(identity, validatedAuthorNames({ [identity]: name }));
  const html = renderToStaticMarkup(createElement('small', { title: identity }, shown));
  expect(html).toContain('&lt;img');
  expect(html).not.toContain('<img');
  expect(html).toContain(identity);
  const many = Object.fromEntries(Array.from({ length: 2001 }, (_, n) => ['npub1' + n.toString(36).padStart(58, '0'), 'Name']));
  expect(Object.keys(validatedAuthorNames(many))).toHaveLength(2000);
});
test('profile rendering never enters comments or board snapshots', () => {
  const comments = normalizeBoardComments([{ id: 'thread', anchor: { x: 0, y: 0 }, resolved: false,
    messages: [{ id: 'message', body: 'Keep my comment', author_npub: identity, created_at: '2026-01-01T00:00:00Z' }] }]);
  const scene = withBoardComments({ type: 'excalidraw', version: 2, elements: [], appState: {}, files: {} }, comments);
  const before = JSON.stringify(scene);
  expect(boardAuthorName(comments[0].messages[0].author_npub, validatedAuthorNames({ [identity]: 'Alice' }))).toBe('Alice');
  expect(JSON.stringify(scene)).toBe(before);
  expect(JSON.stringify(scene)).not.toContain('Alice');
  expect(scene.wingman.comments[0].messages[0].author_npub).toBe(identity);
});
