import { describe, it, expect } from 'vitest';
import { renderEdgeTooltip, renderNodeTooltip } from '../../../plugins/graph-view-plugin/src/utils/tooltipDom';

describe('graph tooltip rendering', () => {
  it('shows node data as text and never as markup', () => {
    const host = document.createElement('div');
    renderNodeTooltip(
      host,
      { label: '<img src=x onerror=alert(1)>', type: '<b>Class</b>', description: '<script>alert(1)</script>' },
      { badgeBackground: 'rgba(0,0,0,0.1)', badgeColor: '#000' },
    );
    expect(host.querySelector('img')).toBeNull();
    expect(host.querySelector('script')).toBeNull();
    expect(host.querySelector('b')).toBeNull();
    expect(host.textContent).toContain('<img src=x onerror=alert(1)>');
    expect(host.textContent).toContain('<script>alert(1)</script>');
  });

  it('clips long descriptions', () => {
    const host = document.createElement('div');
    renderNodeTooltip(host, { label: 'a', type: 'b', description: 'x'.repeat(200) }, { badgeBackground: '', badgeColor: '' });
    expect(host.textContent).toContain('x'.repeat(140) + '…');
  });

  it('shows edge data as text and never as markup', () => {
    const host = document.createElement('div');
    renderEdgeTooltip(host, { sourceLabel: '<i>s</i>', edgeLabel: '<img src=x>', targetLabel: 't', edgeColor: '#f00' });
    expect(host.querySelector('i')).toBeNull();
    expect(host.querySelector('img')).toBeNull();
    expect(host.textContent).toBe('<i>s</i> <img src=x> t');
  });
});
