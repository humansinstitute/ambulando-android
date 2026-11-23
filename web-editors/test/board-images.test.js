import { test, expect } from 'bun:test';
import { boardEditorElements } from '../web/sol/board-scene.js';

test('pending embedded PNG display adapter preserves source and refuses absent or invalid bytes', () => {
  const element = { id: 'image', type: 'image', status: 'pending', fileId: 'png' };
  const scene = { elements: [element], files: { png: { id: 'png', mimeType: 'image/png', dataURL: 'data:image/png;base64,iVBORw0KGgoAAA==' } } };
  const original = JSON.stringify(scene);
  expect(boardEditorElements(scene)[0].status).toBe('saved');
  expect(JSON.stringify(scene)).toBe(original);
  expect(boardEditorElements({ ...scene, files: {} })[0]).toBe(element);
  expect(boardEditorElements({ ...scene, files: { png: { ...scene.files.png, dataURL: 'https://external.invalid/image.png' } } })[0]).toBe(element);
});
