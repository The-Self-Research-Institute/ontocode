import { describe, expect, it } from 'vitest';
import { pickFileToOpen, starterOntology } from '../utils/editorEntryFile';

const files = [
  { id: 'f-main', filename: 'pizza.owl' },
  { id: 'f-extra', filename: 'pizza-2.owl' },
];

describe('opening the editor for a project without choosing a file', () => {
  it('reopens the file the user last had open in this project', () => {
    expect(pickFileToOpen(files, 'proj-1', 'proj-1--f-extra')).toEqual(files[1]);
  });

  it('opens the first file when the last opened file belongs to another project', () => {
    expect(pickFileToOpen(files, 'proj-1', 'proj-2--f-extra')).toEqual(files[0]);
  });

  it('opens the first file when the last opened file was deleted', () => {
    expect(pickFileToOpen(files, 'proj-1', 'proj-1--f-gone')).toEqual(files[0]);
  });

  it('opens the first file when nothing was opened before', () => {
    expect(pickFileToOpen(files, 'proj-1', null)).toEqual(files[0]);
  });

  it('opens nothing, so a starter file is made, only when the project has no files', () => {
    expect(pickFileToOpen([], 'proj-1', 'proj-1--f-main')).toBeNull();
  });

  it('does not treat a project whose id starts the same as the same project', () => {
    expect(pickFileToOpen(files, 'proj-1', 'proj-10--f-extra')).toEqual(files[0]);
  });
});

describe('starter ontology for an empty project', () => {
  it('names the file after the project', () => {
    expect(starterOntology('Gene Study', []).fileName).toBe('gene-study.owl');
  });

  it('picks the next free name when the file name is taken', () => {
    expect(starterOntology('Gene Study', ['GENE-STUDY.owl', 'gene-study-2.owl']).fileName).toBe('gene-study-3.owl');
  });

  it('falls back to a generic name when the project name has no letters or digits', () => {
    expect(starterOntology('  ', []).fileName).toBe('untitled-ontology.owl');
  });

  it('declares the ontology with an IRI built from the file name', () => {
    const { content } = starterOntology('Gene Study', []);
    expect(content).toContain('<owl:Ontology rdf:about="http://example.org/ontologies/gene-study"/>');
  });
});
