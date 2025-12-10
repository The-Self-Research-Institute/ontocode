/**
 * Fuzzy Query DSL (Domain Specific Language)
 * Provides intuitive syntax for querying fuzzy ontologies
 */

import { FuzzyOntology, FuzzyConceptExpression } from '../core/FuzzyOntology';
import { MembershipDegree, TNorm, TCoNorm } from '../core/FuzzyLogic';
import { FuzzyQueryEngine } from '../reasoning/FuzzyReasoner';

/**
 * Fuzzy Query DSL Parser
 *
 * Example queries:
 * - FIND individuals WHERE memberOf(Diabetic) >= 0.8
 * - FIND individuals WHERE memberOf(Diabetic AND Hypertensive) > 0.5
 * - SELECT TOP 10 FROM Patient ORDER BY memberOf(HighRisk) DESC
 * - GET individuals WHERE exists(hasDiagnosis, DiabetesMellitus) > 0.7
 */

export interface QueryResult {
  individuals: Array<{
    uri: string;
    degree: MembershipDegree;
    label?: string;
  }>;
  executionTime: number;
  count: number;
}

export class FuzzyQueryParser {

  private static readonly KEYWORDS = [
    'FIND', 'SELECT', 'GET', 'WHERE', 'FROM', 'ORDER', 'BY', 'ASC', 'DESC',
    'TOP', 'LIMIT', 'AND', 'OR', 'NOT', 'EXISTS', 'FORALL', 'MEMBEROF'
  ];

  /**
   * Parse and execute fuzzy query
   */
  static parse(query: string, ontology: FuzzyOntology): QueryResult {
    const startTime = Date.now();
    const normalized = query.toUpperCase().trim();

    let result: QueryResult;

    if (normalized.startsWith('FIND') || normalized.startsWith('SELECT') || normalized.startsWith('GET')) {
      result = this.parseFindQuery(query, ontology);
    } else {
      throw new Error('Invalid query: must start with FIND, SELECT, or GET');
    }

    result.executionTime = Date.now() - startTime;
    return result;
  }

  private static parseFindQuery(query: string, ontology: FuzzyOntology): QueryResult {
    const engine = new FuzzyQueryEngine(ontology);

    // Extract components
    const whereMatch = query.match(/WHERE\s+(.+?)(?:ORDER|LIMIT|$)/i);
    const orderMatch = query.match(/ORDER\s+BY\s+(\w+)\s+(ASC|DESC)/i);
    const limitMatch = query.match(/(?:TOP|LIMIT)\s+(\d+)/i);

    if (!whereMatch) {
      throw new Error('WHERE clause is required');
    }

    const condition = whereMatch[1].trim();
    const expression = this.parseCondition(condition, ontology);

    // Execute query
    const limit = limitMatch ? parseInt(limitMatch[1]) : 100;
    const results = engine.queryByExpression(expression, 0, limit);

    // Sort if needed
    if (orderMatch) {
      const orderField = orderMatch[1];
      const direction = orderMatch[2].toUpperCase();

      if (orderField.toLowerCase() === 'degree') {
        results.sort((a, b) =>
          direction === 'ASC' ? a.degree - b.degree : b.degree - a.degree
        );
      }
    }

    return {
      individuals: results.map(r => ({
        uri: r.uri,
        degree: r.degree,
        label: r.individual.label
      })),
      executionTime: 0,
      count: results.length
    };
  }

  private static parseCondition(condition: string, ontology: FuzzyOntology): FuzzyConceptExpression {
    // Handle memberOf(Concept) >= threshold
    const memberOfMatch = condition.match(/memberOf\(([^)]+)\)\s*([><=]+)\s*([\d.]+)/i);
    if (memberOfMatch) {
      const conceptExpr = memberOfMatch[1].trim();
      // Parse the concept expression (may contain AND/OR/NOT)
      return this.parseConceptExpression(conceptExpr);
    }

    // Handle exists/forall
    const existsMatch = condition.match(/exists\((\w+),\s*(\w+)\)\s*([><=]+)\s*([\d.]+)/i);
    if (existsMatch) {
      const property = existsMatch[1];
      const concept = existsMatch[2];
      return {
        type: 'existential',
        property,
        concept: { type: 'atomic', uri: concept }
      };
    }

    const forallMatch = condition.match(/forall\((\w+),\s*(\w+)\)\s*([><=]+)\s*([\d.]+)/i);
    if (forallMatch) {
      const property = forallMatch[1];
      const concept = forallMatch[2];
      return {
        type: 'universal',
        property,
        concept: { type: 'atomic', uri: concept }
      };
    }

    // Default: treat as atomic concept
    return { type: 'atomic', uri: condition.trim() };
  }

  private static parseConceptExpression(expr: string): FuzzyConceptExpression {
    // Handle AND
    if (expr.includes(' AND ')) {
      const parts = expr.split(' AND ').map(p => p.trim());
      return {
        type: 'conjunction',
        concepts: parts.map(p => this.parseConceptExpression(p)),
        norm: TNorm.PRODUCT
      };
    }

    // Handle OR
    if (expr.includes(' OR ')) {
      const parts = expr.split(' OR ').map(p => p.trim());
      return {
        type: 'disjunction',
        concepts: parts.map(p => this.parseConceptExpression(p)),
        conorm: TCoNorm.PROBABILISTIC
      };
    }

    // Handle NOT
    if (expr.startsWith('NOT ')) {
      const inner = expr.substring(4).trim();
      return {
        type: 'negation',
        concept: this.parseConceptExpression(inner)
      };
    }

    // Atomic concept
    return { type: 'atomic', uri: expr };
  }
}

/**
 * Fluent query builder for programmatic queries
 */
export class FuzzyQueryBuilder {
  private ontology: FuzzyOntology;
  private expression?: FuzzyConceptExpression;
  private minDegree: number = 0;
  private maxResults: number = 100;
  private orderByDegree: 'asc' | 'desc' = 'desc';

  constructor(ontology: FuzzyOntology) {
    this.ontology = ontology;
  }

  /**
   * Start with a concept
   */
  concept(uri: string): this {
    this.expression = { type: 'atomic', uri };
    return this;
  }

  /**
   * Add conjunction (AND)
   */
  and(uri: string): this {
    if (!this.expression) {
      throw new Error('Must start with concept()');
    }

    if (this.expression.type === 'conjunction') {
      this.expression.concepts.push({ type: 'atomic', uri });
    } else {
      this.expression = {
        type: 'conjunction',
        concepts: [this.expression, { type: 'atomic', uri }],
        norm: TNorm.PRODUCT
      };
    }

    return this;
  }

  /**
   * Add disjunction (OR)
   */
  or(uri: string): this {
    if (!this.expression) {
      throw new Error('Must start with concept()');
    }

    if (this.expression.type === 'disjunction') {
      this.expression.concepts.push({ type: 'atomic', uri });
    } else {
      this.expression = {
        type: 'disjunction',
        concepts: [this.expression, { type: 'atomic', uri }],
        conorm: TCoNorm.PROBABILISTIC
      };
    }

    return this;
  }

  /**
   * Add negation
   */
  not(): this {
    if (!this.expression) {
      throw new Error('Must have an expression to negate');
    }

    this.expression = {
      type: 'negation',
      concept: this.expression
    };

    return this;
  }

  /**
   * Add existential quantifier
   */
  exists(property: string, conceptUri: string): this {
    this.expression = {
      type: 'existential',
      property,
      concept: { type: 'atomic', uri: conceptUri }
    };

    return this;
  }

  /**
   * Add universal quantifier
   */
  forall(property: string, conceptUri: string): this {
    this.expression = {
      type: 'universal',
      property,
      concept: { type: 'atomic', uri: conceptUri }
    };

    return this;
  }

  /**
   * Set minimum membership degree threshold
   */
  threshold(degree: MembershipDegree): this {
    this.minDegree = degree;
    return this;
  }

  /**
   * Set maximum number of results
   */
  limit(count: number): this {
    this.maxResults = count;
    return this;
  }

  /**
   * Set result ordering
   */
  orderBy(direction: 'asc' | 'desc'): this {
    this.orderByDegree = direction;
    return this;
  }

  /**
   * Execute the query
   */
  execute(): QueryResult {
    if (!this.expression) {
      throw new Error('No expression specified');
    }

    const startTime = Date.now();
    const engine = new FuzzyQueryEngine(this.ontology);

    let results = engine.queryByExpression(this.expression, this.minDegree, this.maxResults);

    // Sort
    if (this.orderByDegree === 'asc') {
      results.sort((a, b) => a.degree - b.degree);
    }
    // Default is already desc from query engine

    const executionTime = Date.now() - startTime;

    return {
      individuals: results.map(r => ({
        uri: r.uri,
        degree: r.degree,
        label: r.individual.label
      })),
      executionTime,
      count: results.length
    };
  }

  /**
   * Get the generated expression (for debugging)
   */
  getExpression(): FuzzyConceptExpression | undefined {
    return this.expression;
  }
}

/**
 * Pre-defined query templates
 */
export class QueryTemplates {

  /**
   * Find highly certain instances
   */
  static highCertainty(conceptUri: string, threshold: number = 0.8): string {
    return `FIND individuals WHERE memberOf(${conceptUri}) >= ${threshold}`;
  }

  /**
   * Find uncertain instances (boundary cases)
   */
  static uncertain(conceptUri: string, minThreshold: number = 0.3, maxThreshold: number = 0.7): string {
    return `FIND individuals WHERE memberOf(${conceptUri}) >= ${minThreshold} AND memberOf(${conceptUri}) <= ${maxThreshold}`;
  }

  /**
   * Top-k most representative instances
   */
  static topK(conceptUri: string, k: number): string {
    return `SELECT TOP ${k} FROM individuals WHERE memberOf(${conceptUri}) > 0 ORDER BY degree DESC`;
  }

  /**
   * Find instances with complex condition
   */
  static complexCondition(condition: string): string {
    return `FIND individuals WHERE ${condition}`;
  }

  /**
   * Conjunctive query
   */
  static conjunction(concepts: string[], threshold: number = 0.5): string {
    const expr = concepts.join(' AND ');
    return `FIND individuals WHERE memberOf(${expr}) >= ${threshold}`;
  }

  /**
   * Disjunctive query
   */
  static disjunction(concepts: string[], threshold: number = 0.3): string {
    const expr = concepts.join(' OR ');
    return `FIND individuals WHERE memberOf(${expr}) >= ${threshold}`;
  }

  /**
   * Existential query
   */
  static existential(property: string, concept: string, threshold: number = 0.5): string {
    return `FIND individuals WHERE exists(${property}, ${concept}) >= ${threshold}`;
  }
}

/**
 * Query result formatter
 */
export class QueryResultFormatter {

  static toTable(result: QueryResult): string {
    let table = '+' + '-'.repeat(50) + '+' + '-'.repeat(15) + '+\n';
    table += '| ' + 'Individual'.padEnd(48) + ' | ' + 'Degree'.padEnd(13) + ' |\n';
    table += '+' + '-'.repeat(50) + '+' + '-'.repeat(15) + '+\n';

    for (const individual of result.individuals) {
      const label = (individual.label || individual.uri).substring(0, 47);
      const degree = individual.degree.toFixed(4);
      table += '| ' + label.padEnd(48) + ' | ' + degree.padEnd(13) + ' |\n';
    }

    table += '+' + '-'.repeat(50) + '+' + '-'.repeat(15) + '+\n';
    table += `Found ${result.count} individuals in ${result.executionTime}ms\n`;

    return table;
  }

  static toJSON(result: QueryResult): string {
    return JSON.stringify(result, null, 2);
  }

  static toCSV(result: QueryResult): string {
    let csv = 'URI,Label,Degree\n';

    for (const individual of result.individuals) {
      csv += `"${individual.uri}","${individual.label || ''}",${individual.degree}\n`;
    }

    return csv;
  }

  static toHTML(result: QueryResult): string {
    let html = '<table border="1" cellpadding="5" cellspacing="0">';
    html += '<thead><tr><th>Individual</th><th>Degree</th></tr></thead><tbody>';

    for (const individual of result.individuals) {
      const barWidth = individual.degree * 100;
      html += `
        <tr>
          <td>${individual.label || individual.uri}</td>
          <td>
            <div style="display: flex; align-items: center;">
              <div style="width: ${barWidth}%; height: 20px; background: linear-gradient(to right, #4CAF50, #8BC34A);"></div>
              <span style="margin-left: 10px;">${individual.degree.toFixed(3)}</span>
            </div>
          </td>
        </tr>
      `;
    }

    html += `</tbody></table><p>Found ${result.count} individuals in ${result.executionTime}ms</p>`;
    return html;
  }
}
