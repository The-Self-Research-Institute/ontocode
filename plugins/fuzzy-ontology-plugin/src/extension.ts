/**
 * Fuzzy Ontology Plugin - Main Extension Entry Point
 * Advanced fuzzy ontology support beyond Protege
 */

import { FuzzyOntology, FuzzyConcept, FuzzyIndividual } from './core/FuzzyOntology';
import { MembershipFunctionType, TNorm, TCoNorm } from './core/FuzzyLogic';
import { FuzzySubsumptionReasoner, FuzzyQueryEngine, AlphaCutReasoner } from './reasoning/FuzzyReasoner';
import { MembershipFunctionPlotter, HierarchyVisualizer, MembershipMatrixVisualizer, RadarChartVisualizer } from './visualization/MembershipVisualizer';
import { FuzzyQueryParser, FuzzyQueryBuilder, QueryTemplates } from './query/FuzzyQueryDSL';

// Plugin state
let fuzzyOntology: FuzzyOntology | null = null;
let currentDocument: any = null;

/**
 * Plugin activation
 */
export function activate(context: any) {
  console.log('Fuzzy Ontology Plugin activated');

  // Initialize fuzzy ontology
  fuzzyOntology = new FuzzyOntology({
    tNorm: TNorm.PRODUCT,
    tCoNorm: TCoNorm.PROBABILISTIC
  });

  // Register commands
  registerCommands(context);

  // Register providers
  registerProviders(context);

  // Initialize UI components
  initializeUI(context);
}

/**
 * Register all commands
 */
function registerCommands(context: any) {
  // Enable fuzzy mode
  context.subscriptions.push({
    command: 'fuzzy.enableFuzzyMode',
    callback: () => enableFuzzyMode()
  });

  // Set membership degree
  context.subscriptions.push({
    command: 'fuzzy.setMembershipDegree',
    callback: () => setMembershipDegree()
  });

  // Visualize membership functions
  context.subscriptions.push({
    command: 'fuzzy.visualizeMembership',
    callback: () => visualizeMembership()
  });

  // Run fuzzy query
  context.subscriptions.push({
    command: 'fuzzy.runFuzzyQuery',
    callback: () => runFuzzyQuery()
  });

  // Export fuzzy OWL
  context.subscriptions.push({
    command: 'fuzzy.exportFuzzyOWL',
    callback: () => exportFuzzyOWL()
  });
}

/**
 * Register completion and hover providers
 */
function registerProviders(context: any) {
  // Fuzzy annotation completion provider
  context.subscriptions.push({
    language: 'turtle',
    provider: {
      provideCompletionItems: (document: any, position: any) => {
        return [
          {
            label: 'fuzzy:membershipDegree',
            kind: 'Property',
            insertText: 'fuzzy:membershipDegree "0.8"^^xsd:double',
            documentation: 'Specify membership degree for fuzzy class assertion'
          },
          {
            label: 'fuzzy:membershipFunction',
            kind: 'Property',
            insertText: 'fuzzy:membershipFunction fuzzy:Triangular',
            documentation: 'Specify membership function type'
          },
          {
            label: 'fuzzy:tNorm',
            kind: 'Property',
            insertText: 'fuzzy:tNorm fuzzy:Product',
            documentation: 'Specify t-norm for conjunction'
          },
          {
            label: 'fuzzy:tCoNorm',
            kind: 'Property',
            insertText: 'fuzzy:tCoNorm fuzzy:Probabilistic',
            documentation: 'Specify t-conorm for disjunction'
          }
        ];
      }
    }
  });

  // Hover provider for fuzzy annotations
  context.subscriptions.push({
    language: 'turtle',
    provider: {
      provideHover: (document: any, position: any) => {
        const line = document.lineAt(position.line).text;

        if (line.includes('fuzzy:membershipDegree')) {
          return {
            contents: [
              '**Fuzzy Membership Degree**',
              '',
              'Specifies the degree (0.0 to 1.0) to which an individual belongs to a fuzzy class.',
              '',
              'Example: `fuzzy:membershipDegree "0.75"^^xsd:double`'
            ]
          };
        }

        if (line.includes('fuzzy:tNorm')) {
          return {
            contents: [
              '**T-Norm (Fuzzy Conjunction)**',
              '',
              'Available t-norms:',
              '- `fuzzy:Product`: a * b',
              '- `fuzzy:Godel`: min(a, b)',
              '- `fuzzy:Lukasiewicz`: max(0, a + b - 1)'
            ]
          };
        }

        return null;
      }
    }
  });
}

/**
 * Initialize UI components
 */
function initializeUI(context: any) {
  // Create fuzzy ontology explorer view
  context.subscriptions.push({
    viewId: 'fuzzy-ontology-explorer',
    provider: {
      getTreeItem: (element: any) => element,
      getChildren: (element?: any) => {
        if (!fuzzyOntology) return [];

        if (!element) {
          // Root items
          return [
            { label: 'Fuzzy Concepts', collapsible: true, type: 'concepts' },
            { label: 'Fuzzy Individuals', collapsible: true, type: 'individuals' },
            { label: 'Fuzzy Queries', collapsible: true, type: 'queries' }
          ];
        }

        if (element.type === 'concepts') {
          return fuzzyOntology.getAllConcepts().map(c => ({
            label: `${c.label || c.uri} (μ: ${getAverageMembership(c).toFixed(2)})`,
            concept: c
          }));
        }

        if (element.type === 'individuals') {
          return fuzzyOntology.getAllIndividuals().map(i => ({
            label: i.label || i.uri,
            individual: i
          }));
        }

        if (element.type === 'queries') {
          return [
            { label: 'High Certainty Query', query: 'highCertainty' },
            { label: 'Uncertain Instances', query: 'uncertain' },
            { label: 'Top-K Query', query: 'topK' },
            { label: 'Custom Query...', query: 'custom' }
          ];
        }

        return [];
      }
    }
  });
}

/**
 * Enable fuzzy ontology mode
 */
async function enableFuzzyMode() {
  console.log('Enabling fuzzy ontology mode...');

  if (!fuzzyOntology) {
    fuzzyOntology = new FuzzyOntology();
  }

  // Parse current ontology and convert to fuzzy
  await parseTurtleToFuzzy();

  showNotification('Fuzzy ontology mode enabled! Use fuzzy:membershipDegree annotations.');
}

/**
 * Set membership degree for selected individual/concept
 */
async function setMembershipDegree() {
  const individualURI = await promptInput('Enter individual URI:');
  const conceptURI = await promptInput('Enter concept URI:');
  const degree = parseFloat(await promptInput('Enter membership degree (0.0 - 1.0):') || '0');

  if (degree < 0 || degree > 1) {
    showError('Membership degree must be between 0.0 and 1.0');
    return;
  }

  if (fuzzyOntology) {
    fuzzyOntology.setMembershipDegree(individualURI, conceptURI, degree);
    showNotification(`Set membership: ${individualURI} ∈ ${conceptURI} with degree ${degree}`);

    // Update visualization
    await visualizeMembership();
  }
}

/**
 * Visualize membership functions and hierarchy
 */
async function visualizeMembership() {
  if (!fuzzyOntology) {
    showError('Fuzzy ontology not initialized');
    return;
  }

  const choice = await promptChoice('What to visualize?', [
    'Membership Matrix',
    'Concept Hierarchy',
    'Individual Radar Chart',
    'Membership Function Plot'
  ]);

  const config = {
    theme: 'gradient' as const,
    width: 800,
    height: 600
  };

  let content = '';

  switch (choice) {
    case 'Membership Matrix':
      content = MembershipMatrixVisualizer.generateHTML(
        fuzzyOntology.getAllIndividuals(),
        fuzzyOntology.getAllConcepts(),
        config
      );
      break;

    case 'Concept Hierarchy':
      const rootConcept = fuzzyOntology.getAllConcepts()[0];
      if (rootConcept) {
        content = HierarchyVisualizer.generateTreeSVG(
          rootConcept,
          (uri) => fuzzyOntology!.getSubConcepts(uri),
          config
        );
      }
      break;

    case 'Individual Radar Chart':
      const individual = fuzzyOntology.getAllIndividuals()[0];
      if (individual) {
        content = RadarChartVisualizer.generateSVG(
          individual,
          fuzzyOntology.getAllConcepts(),
          config
        );
      }
      break;

    case 'Membership Function Plot':
      content = MembershipFunctionPlotter.generateSVG(
        { type: MembershipFunctionType.TRIANGULAR, parameters: [0, 5, 10] },
        [0, 10],
        config
      );
      break;
  }

  if (content) {
    showWebview('Fuzzy Membership Visualization', content);
  }
}

/**
 * Run fuzzy query
 */
async function runFuzzyQuery() {
  if (!fuzzyOntology) {
    showError('Fuzzy ontology not initialized');
    return;
  }

  const query = await promptInput('Enter fuzzy query (e.g., FIND individuals WHERE memberOf(Diabetic) >= 0.8):');

  if (!query) return;

  try {
    const result = FuzzyQueryParser.parse(query, fuzzyOntology);

    const output = `
      <h2>Query Results</h2>
      <p><strong>Query:</strong> ${query}</p>
      <p><strong>Found:</strong> ${result.count} individuals in ${result.executionTime}ms</p>

      <table border="1" cellpadding="8" style="border-collapse: collapse; width: 100%;">
        <tr style="background: #f5f5f5;">
          <th>Individual</th>
          <th>Membership Degree</th>
          <th>Visual</th>
        </tr>
        ${result.individuals.map(ind => `
          <tr>
            <td>${ind.label || ind.uri}</td>
            <td>${ind.degree.toFixed(4)}</td>
            <td>
              <div style="background: linear-gradient(to right, #4CAF50 ${ind.degree * 100}%, #ddd ${ind.degree * 100}%);
                          width: 200px; height: 20px; border: 1px solid #999;"></div>
            </td>
          </tr>
        `).join('')}
      </table>
    `;

    showWebview('Fuzzy Query Results', output);
  } catch (error: any) {
    showError(`Query error: ${error.message}`);
  }
}

/**
 * Export fuzzy ontology to Fuzzy OWL format
 */
async function exportFuzzyOWL() {
  if (!fuzzyOntology) {
    showError('Fuzzy ontology not initialized');
    return;
  }

  const fuzzyOwl = generateFuzzyOWL(fuzzyOntology);
  const filepath = await promptSaveFile('fuzzy-ontology.owl');

  if (filepath) {
    writeFile(filepath, fuzzyOwl);
    showNotification(`Fuzzy ontology exported to ${filepath}`);
  }
}

/**
 * Helper functions (placeholders for actual VSCode API calls)
 */

async function parseTurtleToFuzzy(): Promise<void> {
  // Parse current document and extract fuzzy annotations
  // This would integrate with the actual ontology parser
  console.log('Parsing Turtle to Fuzzy ontology...');
}

function getAverageMembership(concept: FuzzyConcept): number {
  if (concept.instances.size === 0) return 0;
  let sum = 0;
  for (const degree of concept.instances.values()) {
    sum += degree;
  }
  return sum / concept.instances.size;
}

function generateFuzzyOWL(ontology: FuzzyOntology): string {
  // Generate Fuzzy OWL 2 format
  let owl = `@prefix fuzzy: <http://www.ontocode.org/fuzzy#> .
@prefix owl: <http://www.w3.org/2002/07/owl#> .
@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

`;

  // Add concepts
  for (const concept of ontology.getAllConcepts()) {
    owl += `<${concept.uri}> a owl:Class .\n`;
    if (concept.label) {
      owl += `<${concept.uri}> rdfs:label "${concept.label}" .\n`;
    }
  }

  // Add fuzzy assertions
  for (const individual of ontology.getAllIndividuals()) {
    for (const [conceptURI, degree] of individual.memberships) {
      owl += `
<${individual.uri}> a <${conceptURI}> ;
  fuzzy:membershipDegree "${degree.toFixed(4)}"^^xsd:double .
`;
    }
  }

  return owl;
}

async function promptInput(message: string): Promise<string | undefined> {
  // Placeholder: would use actual VS Code input box
  return undefined;
}

async function promptChoice(message: string, choices: string[]): Promise<string | undefined> {
  // Placeholder: would use actual VS Code quick pick
  return choices[0];
}

async function promptSaveFile(defaultName: string): Promise<string | undefined> {
  // Placeholder: would use actual VS Code save dialog
  return undefined;
}

function showNotification(message: string): void {
  console.log('[Fuzzy Plugin]', message);
}

function showError(message: string): void {
  console.error('[Fuzzy Plugin]', message);
}

function showWebview(title: string, content: string): void {
  console.log('[Fuzzy Plugin] Webview:', title, content.substring(0, 100));
}

function writeFile(path: string, content: string): void {
  console.log('[Fuzzy Plugin] Write file:', path);
}

export function deactivate() {
  console.log('Fuzzy Ontology Plugin deactivated');
}
