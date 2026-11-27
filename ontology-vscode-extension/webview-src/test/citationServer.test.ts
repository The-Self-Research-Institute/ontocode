import { beforeEach, describe, expect, it, vi } from 'vitest';

const post = vi.fn();
let draftActive = false;

vi.mock('../services/apiClient', () => ({ default: { post: (...args: unknown[]) => post(...args) } }));
vi.mock('../utils/draftScope', () => ({
  withDraftAndUser: (url: string) => (draftActive ? `${url}?draft=true&userId=u1` : url),
}));

import { citationInsertUrl, recordCitationOnServer, standaloneCitationTurtle } from '../utils/citationServer';

const lines = [
  '',
  '###  Citation: A paper',
  '<urn:citation:smith2020> rdf:type owl:NamedIndividual ,',
  '         prov:Entity ;',
  '    dc:title "A paper" ;',
  '    dc:date "2020"^^xsd:gYear .',
  '',
];

describe('saving an inserted citation on the server', () => {
  beforeEach(() => {
    post.mockReset();
    draftActive = false;
  });

  it('declares every prefix the citation block uses, so the server can parse it on its own', () => {
    const turtle = standaloneCitationTurtle(lines);
    for (const prefix of ['rdf', 'owl', 'prov', 'dc', 'xsd', 'bibo', 'foaf']) {
      expect(turtle).toContain(`@prefix ${prefix}: <`);
    }
    expect(turtle).toContain('<urn:citation:smith2020> rdf:type owl:NamedIndividual ,');
  });

  it('posts to the public insert endpoint outside a draft', () => {
    expect(citationInsertUrl('proj-1--file-1')).toBe('/api/citations/proj-1--file-1/insert');
  });

  it('marks the request as a draft change with the user when a draft is active', () => {
    draftActive = true;
    expect(citationInsertUrl('proj-1--file-1')).toBe('/api/citations/proj-1--file-1/insert?draft=true&userId=u1');
  });

  it('sends the citation as Turtle with the user who made it', async () => {
    await recordCitationOnServer('proj-1--file-1', lines, { title: 'A paper' }, { userId: 'u1', username: 'User One' });

    expect(post).toHaveBeenCalledTimes(1);
    const [url, body] = post.mock.calls[0];
    expect(url).toBe('/api/citations/proj-1--file-1/insert');
    expect(body).toMatchObject({ format: 'turtle', metadata: { title: 'A paper' }, lineNumber: 0, userId: 'u1', username: 'User One' });
    expect(body.citation).toContain('@prefix dc: <http://purl.org/dc/elements/1.1/> .');
  });

  it('lets a failed save reach the caller so the user is told', async () => {
    post.mockRejectedValueOnce(new Error('403'));
    await expect(recordCitationOnServer('p', lines, {}, {})).rejects.toThrow('403');
  });
});
