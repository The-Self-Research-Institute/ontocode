/**
 * Fuzzy Membership Visualization
 * Creates visual representations of membership degrees and fuzzy concepts
 */

import { MembershipDegree, MembershipFunction, MembershipFunctionParams } from '../core/FuzzyLogic';
import { FuzzyConcept, FuzzyIndividual } from '../core/FuzzyOntology';

export interface VisualizationConfig {
  theme: 'gradient' | 'heatmap' | 'categorical';
  width: number;
  height: number;
  colors?: ColorScheme;
}

export interface ColorScheme {
  low: string;
  medium: string;
  high: string;
}

export const DEFAULT_COLOR_SCHEMES: Record<string, ColorScheme> = {
  gradient: {
    low: '#e0f7fa',
    medium: '#00acc1',
    high: '#006064'
  },
  heatmap: {
    low: '#ffeb3b',
    medium: '#ff9800',
    high: '#f44336'
  },
  categorical: {
    low: '#e8eaf6',
    medium: '#7986cb',
    high: '#3f51b5'
  }
};

/**
 * Membership degree color mapper
 */
export class ColorMapper {

  static degreeToColor(degree: MembershipDegree, scheme: ColorScheme): string {
    if (degree <= 0.33) {
      return this.interpolateColor(scheme.low, scheme.medium, degree * 3);
    } else if (degree <= 0.67) {
      return this.interpolateColor(scheme.medium, scheme.high, (degree - 0.33) * 3);
    } else {
      return this.interpolateColor(scheme.medium, scheme.high, (degree - 0.33) * 1.5);
    }
  }

  static degreeToGradient(degree: MembershipDegree): string {
    const hue = degree * 120; // 0 (red) to 120 (green)
    return `hsl(${hue}, 70%, 50%)`;
  }

  private static interpolateColor(color1: string, color2: string, factor: number): string {
    const c1 = this.hexToRgb(color1);
    const c2 = this.hexToRgb(color2);

    if (!c1 || !c2) return color1;

    const r = Math.round(c1.r + factor * (c2.r - c1.r));
    const g = Math.round(c1.g + factor * (c2.g - c1.g));
    const b = Math.round(c1.b + factor * (c2.b - c1.b));

    return `rgb(${r}, ${g}, ${b})`;
  }

  private static hexToRgb(hex: string): { r: number; g: number; b: number } | null {
    const result = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex);
    return result
      ? {
          r: parseInt(result[1], 16),
          g: parseInt(result[2], 16),
          b: parseInt(result[3], 16)
        }
      : null;
  }
}

/**
 * Membership function plotter
 */
export class MembershipFunctionPlotter {

  static plot(
    func: MembershipFunctionParams,
    range: [number, number],
    points: number = 100
  ): Array<{ x: number; y: MembershipDegree }> {
    const [min, max] = range;
    const step = (max - min) / points;
    const data: Array<{ x: number; y: MembershipDegree }> = [];

    for (let i = 0; i <= points; i++) {
      const x = min + i * step;
      const y = MembershipFunction.evaluate(x, func);
      data.push({ x, y });
    }

    return data;
  }

  static generateSVG(
    func: MembershipFunctionParams,
    range: [number, number],
    config: VisualizationConfig
  ): string {
    const data = this.plot(func, range);
    const { width, height } = config;
    const padding = 40;

    const xScale = (width - 2 * padding) / (range[1] - range[0]);
    const yScale = height - 2 * padding;

    let pathData = `M ${padding} ${height - padding}`;

    for (const point of data) {
      const x = padding + (point.x - range[0]) * xScale;
      const y = height - padding - point.y * yScale;
      pathData += ` L ${x} ${y}`;
    }

    return `
      <svg width="${width}" height="${height}" xmlns="http://www.w3.org/2000/svg">
        <defs>
          <linearGradient id="membershipGradient" x1="0%" y1="100%" x2="0%" y2="0%">
            <stop offset="0%" style="stop-color:#e0f7fa;stop-opacity:1" />
            <stop offset="50%" style="stop-color:#00acc1;stop-opacity:1" />
            <stop offset="100%" style="stop-color:#006064;stop-opacity:1" />
          </linearGradient>
        </defs>

        <!-- Axes -->
        <line x1="${padding}" y1="${padding}" x2="${padding}" y2="${height - padding}"
              stroke="#333" stroke-width="2" />
        <line x1="${padding}" y1="${height - padding}" x2="${width - padding}" y2="${height - padding}"
              stroke="#333" stroke-width="2" />

        <!-- Grid lines -->
        ${this.generateGridLines(padding, width, height)}

        <!-- Membership function curve -->
        <path d="${pathData}" fill="none" stroke="url(#membershipGradient)" stroke-width="3" />
        <path d="${pathData} L ${width - padding} ${height - padding} L ${padding} ${height - padding} Z"
              fill="url(#membershipGradient)" opacity="0.3" />

        <!-- Labels -->
        <text x="${width / 2}" y="${height - 5}" text-anchor="middle" font-size="12">Value</text>
        <text x="5" y="${height / 2}" text-anchor="middle" font-size="12" transform="rotate(-90, 5, ${height / 2})">
          Membership Degree
        </text>

        <!-- Y-axis labels -->
        <text x="${padding - 5}" y="${padding}" text-anchor="end" font-size="10">1.0</text>
        <text x="${padding - 5}" y="${height / 2}" text-anchor="end" font-size="10">0.5</text>
        <text x="${padding - 5}" y="${height - padding}" text-anchor="end" font-size="10">0.0</text>
      </svg>
    `;
  }

  private static generateGridLines(padding: number, width: number, height: number): string {
    let lines = '';
    const steps = 10;

    for (let i = 0; i <= steps; i++) {
      const y = padding + (i * (height - 2 * padding)) / steps;
      lines += `<line x1="${padding}" y1="${y}" x2="${width - padding}" y2="${y}"
                     stroke="#ddd" stroke-width="1" stroke-dasharray="2,2" />`;

      const x = padding + (i * (width - 2 * padding)) / steps;
      lines += `<line x1="${x}" y1="${padding}" x2="${x}" y2="${height - padding}"
                     stroke="#ddd" stroke-width="1" stroke-dasharray="2,2" />`;
    }

    return lines;
  }
}

/**
 * Concept hierarchy visualizer
 */
export class HierarchyVisualizer {

  static generateTreeSVG(
    rootConcept: FuzzyConcept,
    getSubConcepts: (uri: string) => FuzzyConcept[],
    config: VisualizationConfig
  ): string {
    const { width, height } = config;
    const nodeRadius = 30;
    const levelHeight = 80;

    interface TreeNode {
      concept: FuzzyConcept;
      x: number;
      y: number;
      children: TreeNode[];
    }

    const buildTree = (concept: FuzzyConcept, depth: number = 0): TreeNode => {
      const subConcepts = getSubConcepts(concept.uri);
      return {
        concept,
        x: 0,
        y: depth * levelHeight + 50,
        children: subConcepts.map(c => buildTree(c, depth + 1))
      };
    };

    const tree = buildTree(rootConcept);

    // Calculate positions
    const calculatePositions = (node: TreeNode, leftBound: number, rightBound: number) => {
      node.x = (leftBound + rightBound) / 2;

      if (node.children.length > 0) {
        const childWidth = (rightBound - leftBound) / node.children.length;
        node.children.forEach((child, i) => {
          calculatePositions(child, leftBound + i * childWidth, leftBound + (i + 1) * childWidth);
        });
      }
    };

    calculatePositions(tree, 0, width);

    // Generate SVG
    let svg = `<svg width="${width}" height="${height}" xmlns="http://www.w3.org/2000/svg">`;

    // Draw edges first
    const drawEdges = (node: TreeNode) => {
      for (const child of node.children) {
        svg += `<line x1="${node.x}" y1="${node.y}" x2="${child.x}" y2="${child.y}"
                     stroke="#999" stroke-width="2" />`;
        drawEdges(child);
      }
    };

    drawEdges(tree);

    // Draw nodes
    const drawNodes = (node: TreeNode) => {
      const avgDegree = this.calculateAverageMembership(node.concept);
      const color = ColorMapper.degreeToGradient(avgDegree);

      svg += `
        <circle cx="${node.x}" cy="${node.y}" r="${nodeRadius}"
                fill="${color}" stroke="#333" stroke-width="2" />
        <text x="${node.x}" y="${node.y + 5}" text-anchor="middle"
              font-size="12" fill="white" font-weight="bold">
          ${node.concept.label?.substring(0, 10) || 'Concept'}
        </text>
        <text x="${node.x}" y="${node.y + nodeRadius + 15}" text-anchor="middle"
              font-size="10" fill="#666">
          μ: ${avgDegree.toFixed(2)}
        </text>
      `;

      for (const child of node.children) {
        drawNodes(child);
      }
    };

    drawNodes(tree);

    svg += '</svg>';
    return svg;
  }

  private static calculateAverageMembership(concept: FuzzyConcept): MembershipDegree {
    if (concept.instances.size === 0) return 0;

    let sum = 0;
    for (const degree of concept.instances.values()) {
      sum += degree;
    }

    return sum / concept.instances.size;
  }
}

/**
 * Individual membership matrix visualizer
 */
export class MembershipMatrixVisualizer {

  static generateHTML(
    individuals: FuzzyIndividual[],
    concepts: FuzzyConcept[],
    config: VisualizationConfig
  ): string {
    const scheme = DEFAULT_COLOR_SCHEMES[config.theme];

    let html = `
      <div style="overflow: auto; max-height: ${config.height}px;">
        <table style="border-collapse: collapse; font-size: 12px;">
          <thead>
            <tr>
              <th style="border: 1px solid #ddd; padding: 8px; background: #f5f5f5; position: sticky; top: 0;">
                Individual
              </th>
    `;

    for (const concept of concepts) {
      html += `
        <th style="border: 1px solid #ddd; padding: 8px; background: #f5f5f5; writing-mode: vertical-rl;
                   transform: rotate(180deg); position: sticky; top: 0;">
          ${concept.label || concept.uri}
        </th>
      `;
    }

    html += '</tr></thead><tbody>';

    for (const individual of individuals) {
      html += `<tr><td style="border: 1px solid #ddd; padding: 8px; font-weight: bold;">${individual.label || individual.uri}</td>`;

      for (const concept of concepts) {
        const degree = individual.memberships.get(concept.uri) || 0;
        const color = ColorMapper.degreeToColor(degree, scheme);
        const textColor = degree > 0.5 ? 'white' : 'black';

        html += `
          <td style="border: 1px solid #ddd; padding: 8px; background: ${color}; color: ${textColor}; text-align: center;">
            ${degree.toFixed(2)}
          </td>
        `;
      }

      html += '</tr>';
    }

    html += '</tbody></table></div>';
    return html;
  }
}

/**
 * Radar chart for individual concept membership
 */
export class RadarChartVisualizer {

  static generateSVG(
    individual: FuzzyIndividual,
    concepts: FuzzyConcept[],
    config: VisualizationConfig
  ): string {
    const { width, height } = config;
    const centerX = width / 2;
    const centerY = height / 2;
    const radius = Math.min(width, height) / 2 - 50;

    const n = concepts.length;
    const angleStep = (2 * Math.PI) / n;

    let svg = `<svg width="${width}" height="${height}" xmlns="http://www.w3.org/2000/svg">`;

    // Draw background circles
    for (let i = 1; i <= 5; i++) {
      const r = (radius * i) / 5;
      svg += `<circle cx="${centerX}" cy="${centerY}" r="${r}" fill="none" stroke="#e0e0e0" stroke-width="1" />`;
    }

    // Draw axes
    const points: Array<{ x: number; y: number; degree: MembershipDegree }> = [];

    for (let i = 0; i < n; i++) {
      const angle = i * angleStep - Math.PI / 2;
      const degree = individual.memberships.get(concepts[i].uri) || 0;

      const x = centerX + radius * Math.cos(angle);
      const y = centerY + radius * Math.sin(angle);

      svg += `<line x1="${centerX}" y1="${centerY}" x2="${x}" y2="${y}" stroke="#ddd" stroke-width="1" />`;

      const px = centerX + radius * degree * Math.cos(angle);
      const py = centerY + radius * degree * Math.sin(angle);
      points.push({ x: px, y: py, degree });

      // Labels
      const labelX = centerX + (radius + 30) * Math.cos(angle);
      const labelY = centerY + (radius + 30) * Math.sin(angle);
      svg += `<text x="${labelX}" y="${labelY}" text-anchor="middle" font-size="10">${concepts[i].label || `C${i}`}</text>`;
    }

    // Draw membership polygon
    if (points.length > 0) {
      let pathData = `M ${points[0].x} ${points[0].y}`;
      for (let i = 1; i < points.length; i++) {
        pathData += ` L ${points[i].x} ${points[i].y}`;
      }
      pathData += ' Z';

      svg += `<path d="${pathData}" fill="rgba(0, 150, 200, 0.3)" stroke="rgba(0, 150, 200, 0.8)" stroke-width="2" />`;

      // Draw points
      for (const point of points) {
        const color = ColorMapper.degreeToGradient(point.degree);
        svg += `<circle cx="${point.x}" cy="${point.y}" r="5" fill="${color}" stroke="white" stroke-width="2" />`;
      }
    }

    svg += '</svg>';
    return svg;
  }
}
