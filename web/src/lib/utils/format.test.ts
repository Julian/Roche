import { describe, expect, it } from 'vitest';
import {
  formatDuration,
  formatTrackDuration,
  isDynamicCollection,
  isPublicFaviconHost,
  normalizeUrlInput,
  safeHttpHref,
  safeLocalReturnTo,
} from './format';

describe('format utilities', () => {
  it('formats durations and track timestamps', () => {
    expect(formatDuration(45)).toBe('1m');
    expect(formatDuration(600)).toBe('10m');
    expect(formatDuration(3720)).toBe('1h 2m');
    expect(formatDuration(null)).toBeNull();

    expect(formatTrackDuration(65_000)).toBe('1:05');
    expect(formatTrackDuration(240_000)).toBe('4:00');
    expect(formatTrackDuration(null)).toBe('');
  });

  it('normalizes bare host URLs cleanly', () => {
    expect(normalizeUrlInput('danluu.com/latency')).toBe('https://danluu.com/latency');
    expect(normalizeUrlInput('https://example.org/test')).toBe('https://example.org/test');
    expect(normalizeUrlInput('   ')).toBe('');
  });

  it('allows only http(s) non-local URLs in safeHttpHref', () => {
    expect(safeHttpHref('https://example.org/article')).toBe('https://example.org/article');
    expect(safeHttpHref('http://news.ycombinator.com/item?id=1')).toBe(
      'http://news.ycombinator.com/item?id=1',
    );
    expect(safeHttpHref('javascript:alert(1)')).toBeNull();
    expect(safeHttpHref('data:text/html,<script>alert(1)</script>')).toBeNull();
    expect(safeHttpHref('local-pdf://book.pdf')).toBeNull();
    expect(safeHttpHref('https://roche.local/items/1')).toBeNull();
    expect(safeHttpHref('https://sub.roche.local/items/1')).toBeNull();
    expect(safeHttpHref(null)).toBeNull();
  });

  it('detects dynamic collections via server dynamic_collection_ids or rule_json', () => {
    const dynSet = new Set(['c1']);
    expect(isDynamicCollection({ id: 'c1', rule_json: null }, dynSet)).toBe(true);
    expect(isDynamicCollection({ id: 'c2', rule_json: null }, dynSet)).toBe(false);
    expect(isDynamicCollection({ id: 'c2', rule_json: '{"kind":"book"}' })).toBe(true);
    expect(isDynamicCollection({ id: 'c3', rule_json: '   ' })).toBe(false);
    expect(isDynamicCollection({ id: 'c1', rule_json: null }, ['c1'])).toBe(true);
  });

  it('blocks private, local, loopback, and tailnet hosts from public favicon lookups', () => {
    expect(isPublicFaviconHost('https://example.com/post')).toBe('example.com');
    expect(isPublicFaviconHost('https://localhost:8080/x')).toBeNull();
    expect(isPublicFaviconHost('http://127.0.0.1/x')).toBeNull();
    expect(isPublicFaviconHost('https://my-node.tail1234.ts.net/x')).toBeNull();
    expect(isPublicFaviconHost('http://printer.local/x')).toBeNull();
    expect(isPublicFaviconHost('http://192.168.1.5/x')).toBeNull();
    expect(isPublicFaviconHost('http://[::1]/x')).toBeNull();
  });

  it('validates safe local return_to paths and blocks open redirects', () => {
    expect(safeLocalReturnTo('/inbox?kind=article')).toBe('/inbox?kind=article');
    expect(safeLocalReturnTo('//evil.example/phish')).toBe('/');
    expect(safeLocalReturnTo('https://evil.example')).toBe('/');
    expect(safeLocalReturnTo('/login')).toBe('/');
    expect(safeLocalReturnTo('/api')).toBe('/');
    expect(safeLocalReturnTo('/api/v1/items')).toBe('/');
    expect(safeLocalReturnTo('/web')).toBe('/');
    expect(safeLocalReturnTo('/web/foo')).toBe('/');
    expect(safeLocalReturnTo(null)).toBe('/');
  });
});
