

export type MembershipDegree = number; // [0, 1]

export enum TNorm {
  PRODUCT = 'product',
  GODEL = 'godel',
  LUKASIEWICZ = 'lukasiewicz'
}

export enum TCoNorm {
  PROBABILISTIC = 'probabilistic',
  GODEL = 'godel',
  LUKASIEWICZ = 'lukasiewicz'
}

export class FuzzyConjunction {

  static product(a: MembershipDegree, b: MembershipDegree): MembershipDegree {
    return a * b;
  }

  static godel(a: MembershipDegree, b: MembershipDegree): MembershipDegree {
    return Math.min(a, b);
  }

  static lukasiewicz(a: MembershipDegree, b: MembershipDegree): MembershipDegree {
    return Math.max(0, a + b - 1);
  }

  static apply(a: MembershipDegree, b: MembershipDegree, norm: TNorm): MembershipDegree {
    switch (norm) {
      case TNorm.PRODUCT:
        return this.product(a, b);
      case TNorm.GODEL:
        return this.godel(a, b);
      case TNorm.LUKASIEWICZ:
        return this.lukasiewicz(a, b);
      default:
        return this.product(a, b);
    }
  }
}

export class FuzzyDisjunction {

  static probabilistic(a: MembershipDegree, b: MembershipDegree): MembershipDegree {
    return a + b - a * b;
  }

  static godel(a: MembershipDegree, b: MembershipDegree): MembershipDegree {
    return Math.max(a, b);
  }

  static lukasiewicz(a: MembershipDegree, b: MembershipDegree): MembershipDegree {
    return Math.min(1, a + b);
  }

  static apply(a: MembershipDegree, b: MembershipDegree, conorm: TCoNorm): MembershipDegree {
    switch (conorm) {
      case TCoNorm.PROBABILISTIC:
        return this.probabilistic(a, b);
      case TCoNorm.GODEL:
        return this.godel(a, b);
      case TCoNorm.LUKASIEWICZ:
        return this.lukasiewicz(a, b);
      default:
        return this.probabilistic(a, b);
    }
  }
}

export class FuzzyNegation {

  static standard(a: MembershipDegree): MembershipDegree {
    return 1 - a;
  }

  static sugeno(a: MembershipDegree, lambda: number = 1): MembershipDegree {
    return (1 - a) / (1 + lambda * a);
  }

  static yager(a: MembershipDegree, w: number = 1): MembershipDegree {
    return Math.pow(1 - Math.pow(a, w), 1 / w);
  }
}

export class FuzzyImplication {

  static kleeneDienes(a: MembershipDegree, b: MembershipDegree): MembershipDegree {
    return Math.max(1 - a, b);
  }

  static godelBrouwer(a: MembershipDegree, b: MembershipDegree): MembershipDegree {
    return a <= b ? 1 : b;
  }

  static lukasiewicz(a: MembershipDegree, b: MembershipDegree): MembershipDegree {
    return Math.min(1, 1 - a + b);
  }

  static mamdani(a: MembershipDegree, b: MembershipDegree): MembershipDegree {
    return Math.min(a, b);
  }
}

export enum MembershipFunctionType {
  TRIANGULAR = 'triangular',
  TRAPEZOIDAL = 'trapezoidal',
  GAUSSIAN = 'gaussian',
  SIGMOID = 'sigmoid',
  BELL = 'bell'
}

export interface MembershipFunctionParams {
  type: MembershipFunctionType;
  parameters: number[];
}

export class MembershipFunction {

  static triangular(x: number, params: number[]): MembershipDegree {
    const [a, b, c] = params;
    if (x <= a || x >= c) return 0;
    if (x === b) return 1;
    if (x < b) return (x - a) / (b - a);
    return (c - x) / (c - b);
  }

  static trapezoidal(x: number, params: number[]): MembershipDegree {
    const [a, b, c, d] = params;
    if (x <= a || x >= d) return 0;
    if (x >= b && x <= c) return 1;
    if (x < b) return (x - a) / (b - a);
    return (d - x) / (d - c);
  }

  static gaussian(x: number, params: number[]): MembershipDegree {
    const [mean, sigma] = params;
    return Math.exp(-Math.pow(x - mean, 2) / (2 * sigma * sigma));
  }

  static sigmoid(x: number, params: number[]): MembershipDegree {
    const [a, c] = params;
    return 1 / (1 + Math.exp(-a * (x - c)));
  }

  static bell(x: number, params: number[]): MembershipDegree {
    const [a, b, c] = params;
    return 1 / (1 + Math.pow(Math.abs((x - c) / a), 2 * b));
  }

  static evaluate(x: number, func: MembershipFunctionParams): MembershipDegree {
    switch (func.type) {
      case MembershipFunctionType.TRIANGULAR:
        return this.triangular(x, func.parameters);
      case MembershipFunctionType.TRAPEZOIDAL:
        return this.trapezoidal(x, func.parameters);
      case MembershipFunctionType.GAUSSIAN:
        return this.gaussian(x, func.parameters);
      case MembershipFunctionType.SIGMOID:
        return this.sigmoid(x, func.parameters);
      case MembershipFunctionType.BELL:
        return this.bell(x, func.parameters);
      default:
        return 0;
    }
  }
}

export class FuzzySet {
  private memberships: Map<string, MembershipDegree>;

  constructor(memberships?: Map<string, MembershipDegree>) {
    this.memberships = memberships || new Map();
  }

  getMembership(element: string): MembershipDegree {
    return this.memberships.get(element) || 0;
  }

  setMembership(element: string, degree: MembershipDegree): void {
    if (degree < 0 || degree > 1) {
      throw new Error('Membership degree must be in [0, 1]');
    }
    this.memberships.set(element, degree);
  }

  union(other: FuzzySet, conorm: TCoNorm = TCoNorm.PROBABILISTIC): FuzzySet {
    const result = new FuzzySet();
    const allElements = new Set([...this.memberships.keys(), ...other.memberships.keys()]);

    for (const element of allElements) {
      const degree1 = this.getMembership(element);
      const degree2 = other.getMembership(element);
      result.setMembership(element, FuzzyDisjunction.apply(degree1, degree2, conorm));
    }

    return result;
  }

  intersection(other: FuzzySet, norm: TNorm = TNorm.PRODUCT): FuzzySet {
    const result = new FuzzySet();
    const allElements = new Set([...this.memberships.keys(), ...other.memberships.keys()]);

    for (const element of allElements) {
      const degree1 = this.getMembership(element);
      const degree2 = other.getMembership(element);
      result.setMembership(element, FuzzyConjunction.apply(degree1, degree2, norm));
    }

    return result;
  }

  complement(): FuzzySet {
    const result = new FuzzySet();

    for (const [element, degree] of this.memberships) {
      result.setMembership(element, FuzzyNegation.standard(degree));
    }

    return result;
  }

  getAlphaCut(alpha: number): Set<string> {
    const result = new Set<string>();

    for (const [element, degree] of this.memberships) {
      if (degree >= alpha) {
        result.add(element);
      }
    }

    return result;
  }

  getSupport(): Set<string> {
    return this.getAlphaCut(0);
  }

  getCore(): Set<string> {
    return this.getAlphaCut(1);
  }

  cardinality(): number {
    let sum = 0;
    for (const degree of this.memberships.values()) {
      sum += degree;
    }
    return sum;
  }

  getAllElements(): Map<string, MembershipDegree> {
    return new Map(this.memberships);
  }
}
